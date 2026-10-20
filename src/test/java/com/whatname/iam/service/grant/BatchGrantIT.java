package com.whatname.iam.service.grant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.dto.admin.AdminDtos;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {"spring.config.import=", "iam.grant-batch.recovery.enabled=false"})
@AutoConfigureMockMvc
@Import(GrantIT.Integrations.class)
@Testcontainers
class BatchGrantIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("unified_permission").withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--default-time-zone=+08:00")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db", "01_schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schema.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired GrantIT.TestState state;
    @Autowired AdminService admins;
    @TestBean(name = "grantBatchExecutor", methodName = "testExecutor", enforceOverride = true) Executor executor;
    private QueueExecutor dispatch;
    static Executor testExecutor() { return new QueueExecutor(); }
    private static final AtomicLong NEXT = new AtomicLong(10000);
    private long base;
    private long id(long offset) { return base + offset; }
    private String sid(long offset) { return Long.toString(id(offset)); }

    @BeforeEach void seed() {
        dispatch = (QueueExecutor) executor;
        base = NEXT.getAndAdd(10000);
        state.actor.set(id(1));
        state.failure = null;
        state.deniedPermission = null;
        state.afterGrantCommit = null;
        dispatch.tasks.clear();
        dispatch.reject = false;
        var data = new AdminTestFixture();
        for (var d : data.departments.values()) {
            List<String> path = new ArrayList<>();
            for (long current = d.id(); current != 0; current = data.departments.get(current).parentId()) path.addFirst(sid(current));
            jdbc.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path,status) VALUES(?,?,?,?,?,?)",
                    id(d.id()), d.parentId() == 0 ? 0 : id(d.parentId()), "D" + id(d.id()), "batch test department",
                    "/" + String.join("/", path) + "/", d.status());
        }
        for (var u : data.users.values()) insertUser(id(u.id()), id(u.deptId()), u.status());
        for (var r : data.roles.values()) jdbc.update("INSERT INTO sys_role(id,role_code,role_name,role_type,status) VALUES(?,?,?,?,?)",
                id(r.id()), "R" + id(r.id()), "batch test role", r.roleType(), r.status());
        for (var a : data.appointments.values()) jdbc.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,appointed_by,status,delegable_revision) VALUES(?,?,?,?,?,?)",
                id(a.userId()), a.adminLevel(), id(a.deptId()), a.appointedBy() == null ? null : id(a.appointedBy()), a.status(), a.delegableRevision());
        for (var d : data.delegations) jdbc.update("INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by) VALUES(?,?,?)",
                id(d.adminUserId()), id(d.roleId()), id(d.configuredBy()));
    }
    @AfterEach void clearTestContext() {
        state.actor.remove();
        state.failure = null;
        state.deniedPermission = null;
        state.afterGrantCommit = null;
    }

    private void insertUser(long user, long dept, int status) {
        jdbc.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status) VALUES(?,?,?,?,?,?,?,?,?)",
                user, "batch_user_" + user, "test-only-ciphertext", "test-only-ciphertext", String.format("%064x", user),
                "test-only-unusable-hash", dept, 3, status);
    }
    private Map<String, Object> target(long user, String ref) {
        return Map.of("userId", sid(user), "sourceType", "MANUAL", "sourceRef", ref);
    }
    private Map<String, Object> request(List<?> targets) {
        return new LinkedHashMap<>(Map.of("operation", "GRANT", "roleId", sid(100), "targets", targets, "reason", "batch test"));
    }
    private MvcResult submit(String key, Object body) throws Exception {
        return mvc.perform(post("/api/v1/grants/user-roles/batch").header("Idempotency-Key", key)
                .contentType("application/json").content(json.writeValueAsString(body))).andReturn();
    }
    private JsonNode data(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        return json.readTree(result.getResponse().getContentAsString()).get("data");
    }
    private String accepted(String key, Object body) throws Exception {
        var result = submit(key, body);
        var value = data(result, 202);
        assertThat(json.readTree(result.getResponse().getContentAsString()).get("code").asText()).isEqualTo("ACCEPTED");
        assertThat(value.get("processingStatus").asText()).isEqualTo("PENDING");
        return value.get("batchId").asText();
    }
    private JsonNode query(String batch, int page, int size) throws Exception {
        return data(mvc.perform(get("/api/v1/grants/batches/" + batch).param("page", "" + page).param("pageSize", "" + size)).andReturn(), 200);
    }
    private long count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, id(6)); }
    private long revision() { return jdbc.queryForObject("SELECT grant_revision FROM sys_user WHERE id=?", Long.class, id(6)); }
    private void runTasks() throws Exception {
        try (var pool = Executors.newSingleThreadExecutor()) {
            Runnable task;
            while ((task = dispatch.tasks.poll()) != null) pool.submit(task).get(180, TimeUnit.SECONDS);
        }
    }

    @Test void acceptsDurablyThenAppliesOnBackgroundThreadAndReplaysWithoutMutation() throws Exception {
        var body = request(List.of(target(6, "WORK"), target(6, "WORK"), target(5, "OTHER")));
        String batch = accepted("roundtrip", body);
        assertThat(count("sys_user_role_source")).isZero();
        assertThat(query(batch, 1, 20).get("pending").asInt()).isEqualTo(2);
        runTasks();
        var done = query(batch, 1, 1);
        assertThat(done.get("processingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(done.get("succeeded").asInt()).isEqualTo(2);
        assertThat(done.get("total").asInt()).isEqualTo(2);
        assertThat(done.get("items")).hasSize(1);
        assertThat(query(batch, 2, 1).get("items").get(0).get("userId").asText()).isEqualTo(sid(6));
        assertThat(data(submit("roundtrip", request(List.of(target(5, "OTHER"), target(6, "WORK")))), 200).get("batchId").asText()).isEqualTo(batch);
        assertThat(dispatch.tasks).isEmpty();
        assertThat(revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isEqualTo(1);
    }

    @Test void partialFailureRetainsOtherSuccessAndSameKeyDoesNotRetryFailedTarget() throws Exception {
        var body = request(List.of(target(6, "GOOD"), target(999, "MISSING")));
        String batch = accepted("partial", body);
        runTasks();
        assertThat(jdbc.queryForObject("SELECT processing_status FROM sys_grant_batch WHERE id=?", String.class, batch)).isEqualTo("PARTIAL_SUCCESS");
        assertThat(jdbc.queryForObject("SELECT result_code FROM sys_grant_batch_item WHERE batch_id=? AND user_id=?", String.class, batch, id(999)))
                .isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(count("sys_user_role_source")).isEqualTo(1);
        insertUser(id(999), id(21), 1);
        data(submit("partial", body), 200);
        assertThat(dispatch.tasks).isEmpty();
        String retry = accepted("partial-fixed", request(List.of(target(999, "MISSING"))));
        runTasks();
        assertThat(query(retry, 1, 20).get("succeeded").asInt()).isEqualTo(1);
    }

    @Test void changedPayloadAndConflictingDuplicateTargetsAreRejectedWithoutNewTasks() throws Exception {
        accepted("conflict", request(List.of(target(6, "FIRST"))));
        assertThat(submit("conflict", request(List.of(target(6, "SECOND")))).getResponse().getStatus()).isEqualTo(409);
        assertThat(submit("duplicates", request(List.of(target(6, "A"), target(6, "B")))).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_grant_batch WHERE actor_user_id=?", Long.class, id(1))).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"dryRun", "numericId", "unknown", "empty", "invalidOperation", "nullTarget", "wrongSource", "longReason", "stringBoolean", "revokeFields"})
    void rejectsMalformedRequestsWithoutCreatingTasks(String variant) throws Exception {
        var body = request(List.of(target(6, "CHECK")));
        switch (variant) {
            case "dryRun" -> body.put("dryRun", true);
            // 应用序列化器会将 Long 输出为 JSON 字符串；此处用 Integer 发送真正的 JSON 数值。
            case "numericId" -> body.put("roleId", Math.toIntExact(id(100)));
            case "unknown" -> body.put("actorId", sid(1));
            case "empty" -> body.put("targets", List.of());
            case "invalidOperation" -> body.put("operation", "grant");
            case "nullTarget" -> body.put("targets", Arrays.asList((Object) null));
            case "wrongSource" -> body.put("targets", List.of(Map.of("userId", sid(6), "sourceType", "OA_APPROVAL", "sourceRef", "OA")));
            case "longReason" -> body.put("reason", "x".repeat(513));
            case "stringBoolean" -> body.put("dryRun", "false");
            case "revokeFields" -> body.put("operation", "REVOKE");
        }
        assertThat(submit("bad", body).getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_grant_batch WHERE actor_user_id=?", Long.class, id(1))).isZero();
    }

    @Test void keyAndPageValidationAndDefaultIdentityFailClosed() throws Exception {
        var body = request(List.of(target(6, "CHECK")));
        assertThat(mvc.perform(post("/api/v1/grants/user-roles/batch").contentType("application/json")
                .content(json.writeValueAsString(body))).andReturn().getResponse().getStatus()).isEqualTo(400);
        for (String key : List.of(" ", "x".repeat(129), "中文")) assertThat(submit(key, body).getResponse().getStatus()).isEqualTo(400);
        String batch = accepted("pages", body);
        for (String[] page : List.of(new String[]{"0", "20"}, new String[]{"1", "501"}, new String[]{"1", "0"})) {
            assertThat(mvc.perform(get("/api/v1/grants/batches/" + batch).param("page", page[0]).param("pageSize", page[1]))
                    .andReturn().getResponse().getStatus()).isEqualTo(400);
        }
        state.actor.remove();
        assertThat(submit("anonymous", body).getResponse().getStatus()).isEqualTo(401);
        assertThat(mvc.perform(get("/api/v1/grants/batches/" + batch)).andReturn().getResponse().getStatus()).isEqualTo(401);
    }

    @ParameterizedTest @ValueSource(strings = {"SIGN", "AUDIT"})
    void evidenceFailureRollsBackFactsAndAppliedStatus(String failure) throws Exception {
        String batch = accepted("evidence", request(List.of(target(6, "ROLLBACK"))));
        state.failure = failure;
        runTasks();
        assertThat(query(batch, 1, 20).get("processingStatus").asText()).isEqualTo("FAILED");
        assertThat(count("sys_user_role_source")).isZero();
        assertThat(count("sys_user_role")).isZero();
        assertThat(revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_id=?", Long.class, id(6))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isZero();
    }

    @Test void revokeOnlyNamedSourceAndKeepIndependentSource() throws Exception {
        String first = accepted("first", request(List.of(target(6, "FIRST"))));
        runTasks();
        String source = query(first, 1, 20).get("items").get(0).get("grantSourceId").asText();
        accepted("second", request(List.of(target(6, "SECOND"))));
        runTasks();
        var body = request(List.of(Map.of("userId", sid(6), "grantSourceId", source)));
        body.put("operation", "REVOKE");
        String revoke = accepted("revoke", body);
        runTasks();
        assertThat(query(revoke, 1, 20).get("succeeded").asInt()).isEqualTo(1);
        assertThat(count("sys_user_role")).isEqualTo(1);
        assertThat(count("sys_user_role_source")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM sys_user_role_source WHERE id=?", Integer.class, source)).isZero();
        assertThat(revision()).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT JSON_UNQUOTE(JSON_EXTRACT(result_data,'$.permissionRevision')) FROM sys_grant_batch_item WHERE batch_id=?",
                String.class, revoke)).isEqualTo("v4");
    }

    @Test void nonretryableAppliedWriteFailureRollsBackBusinessAndBecomesFailed() throws Exception {
        String batch = accepted("applied-failure", request(List.of(target(6, "ROLLBACK"))));
        jdbc.execute("ALTER TABLE sys_grant_batch_item ADD CONSTRAINT test_fail_applied CHECK (batch_id<>"
                + batch + " OR processing_status<>'APPLIED')");
        try { runTasks(); } finally { jdbc.execute("ALTER TABLE sys_grant_batch_item DROP CHECK test_fail_applied"); }
        assertThat(query(batch, 1, 20).get("failed").asInt()).isEqualTo(1);
        assertThat(count("sys_user_role_source")).isZero();
        assertThat(count("sys_user_role")).isZero();
        assertThat(revision()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_id=?", Long.class, id(6))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isZero();
        data(submit("applied-failure", request(List.of(target(6, "ROLLBACK")))), 200);
        assertThat(dispatch.tasks).isEmpty();
    }

    @Test void itemInsertionFailureRollsBackTaskAndNeverDispatches() throws Exception {
        jdbc.execute("ALTER TABLE sys_grant_batch_item ADD CONSTRAINT test_fail_batch_insert CHECK (user_id<>" + id(6) + ")");
        try { assertThat(submit("insert-failure", request(List.of(target(6, "CHECK")))).getResponse().getStatus()).isEqualTo(503); }
        finally { jdbc.execute("ALTER TABLE sys_grant_batch_item DROP CHECK test_fail_batch_insert"); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_grant_batch WHERE actor_user_id=?", Long.class, id(1))).isZero();
        assertThat(dispatch.tasks).isEmpty();
    }

    @Test void qualificationIsRecheckedAfterSubmissionAndQueryFiltersBeforePaging() throws Exception {
        String batch = accepted("scope", request(List.of(target(5, "OUTSIDE"), target(6, "INSIDE"))));
        runTasks();
        state.actor.set(id(2));
        var view = query(batch, 1, 1);
        assertThat(view.get("total").asInt()).isEqualTo(1);
        assertThat(view.get("succeeded").asInt()).isEqualTo(1);
        assertThat(view.get("items").get(0).get("userId").asText()).isEqualTo(sid(6));
        String pending = accepted("revoked-actor", request(List.of(target(6, "LATER"))));
        jdbc.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id(2));
        runTasks();
        state.actor.set(id(1));
        assertThat(query(pending, 1, 20).get("items").get(0).get("code").asText()).isEqualTo("PERMISSION_DENIED");
        assertThat(revision()).isEqualTo(2);
    }

    @Test void rejectedDispatchLeavesDurablePendingAndReplayDoesNotDispatchAgain() throws Exception {
        dispatch.reject = true;
        var body = request(List.of(target(6, "PENDING")));
        String batch = accepted("dispatch-failure", body);
        assertThat(query(batch, 1, 20).get("pending").asInt()).isEqualTo(1);
        dispatch.reject = false;
        data(submit("dispatch-failure", body), 200);
        assertThat(dispatch.tasks).isEmpty();
        assertThat(count("sys_user_role_source")).isZero();
    }

    @Test void concurrentSameKeyCreatesOneTaskAndDuplicateWorkerDoesNotApplyTwice() throws Exception {
        var start = new CountDownLatch(1);
        var body = request(List.of(target(6, "ONCE")));
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<MvcResult> action = () -> { state.actor.set(id(1)); start.await(); try { return submit("race", body); } finally { state.actor.remove(); } };
            var a = pool.submit(action); var b = pool.submit(action); start.countDown();
            var responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
            assertThat(responses).extracting(r -> r.getResponse().getStatus()).containsExactlyInAnyOrder(202, 200);
            assertThat(dispatch.tasks).hasSize(1);
            var work = dispatch.tasks.remove();
            var x = pool.submit(work); var y = pool.submit(work);
            x.get(30, TimeUnit.SECONDS); y.get(30, TimeUnit.SECONDS);
        }
        assertThat(count("sys_user_role_source")).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isEqualTo(1);
    }

    // 防止执行时沿用提交时的旧资格，或因后续失败回滚此前已提交的任务项。
    @ParameterizedTest @ValueSource(strings = {"REVOKE", "SHRINK"})
    void authorityChangeBetweenItemsPreservesCommittedSuccess(String change) throws Exception {
        insertUser(id(7), id(21), 1);
        state.actor.set(id(2));
        String batch = accepted("mid-flight", request(List.of(target(6, "FIRST"), target(7, "SECOND"))));
        var firstCommitted = new CountDownLatch(1);
        var continueBatch = new CountDownLatch(1);
        state.afterGrantCommit = user -> {
            if (user == id(6)) {
                firstCommitted.countDown();
                try {
                    if (!continueBatch.await(30, TimeUnit.SECONDS)) throw new AssertionError("batch coordination timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        };
        try (var pool = Executors.newSingleThreadExecutor()) {
            var running = pool.submit(dispatch.tasks.remove());
            try {
                assertThat(firstCommitted.await(30, TimeUnit.SECONDS)).as("first item committed").isTrue();
                assertThat(jdbc.queryForObject("SELECT processing_status FROM sys_grant_batch_item WHERE batch_id=? AND user_id=?",
                        String.class, batch, id(6))).isEqualTo("APPLIED");
                state.actor.set(id(1));
                if ("REVOKE".equals(change)) {
                    admins.revoke(sid(2), new AdminDtos.RevokeRequest("mid-flight revocation"));
                } else {
                    admins.replaceDelegableRoles(sid(2), new AdminDtos.DelegationRequest(List.of(), "v1", "mid-flight shrink"));
                }
            } finally {
                continueBatch.countDown();
            }
            running.get(30, TimeUnit.SECONDS);
        } finally {
            state.afterGrantCommit = null;
        }
        var result = query(batch, 1, 20);
        assertThat(result.get("processingStatus").asText()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.get("succeeded").asInt()).isEqualTo(1);
        assertThat(result.get("failed").asInt()).isEqualTo(1);
        assertThat(result.get("items").get(1).get("code").asText())
                .isEqualTo("REVOKE".equals(change) ? "PERMISSION_DENIED" : "ROLE_NOT_DELEGABLE");
        assertThat(count("sys_user_role_source")).isEqualTo(1);
        assertThat(count("sys_user_role")).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_id=?", Long.class, id(6))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT grant_revision FROM sys_user WHERE id=?", Long.class, id(7))).isEqualTo(1);
        for (String table : List.of("sys_user_role_source", "sys_user_role")) {
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, id(7))).isZero();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(7))).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_id=?", Long.class, id(7))).isZero();
    }

    // 防止将幂等键错误地视为全局唯一，或在重放查询中忽略当前操作者。
    @Test void sameKeyIsIsolatedByInitiator() throws Exception {
        var body = request(List.of(target(6, "SHARED")));
        String first = accepted("shared-key", body);
        state.actor.set(id(2));
        String second = accepted("shared-key", body);
        assertThat(second).isNotEqualTo(first);
        assertThat(data(submit("shared-key", body), 200).get("batchId").asText()).isEqualTo(second);
        state.actor.set(id(1));
        assertThat(data(submit("shared-key", body), 200).get("batchId").asText()).isEqualTo(first);
        assertThat(dispatch.tasks).hasSize(2);
        runTasks();
        assertThat(query(first, 1, 20).get("succeeded").asInt()).isEqualTo(1);
        assertThat(query(second, 1, 20).get("succeeded").asInt()).isEqualTo(1);
        assertThat(count("sys_user_role_source")).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isEqualTo(1);
    }

    // 防止使用旧管理范围，或在按当前范围过滤之前统计数量和分页。
    @Test void queryUsesChangedScopeAndHidesBatchWhenNoTargetsRemainVisible() throws Exception {
        String batch = accepted("scope-change", request(List.of(target(3, "FIRST"), target(4, "SECOND"), target(6, "THIRD"))));
        runTasks();
        state.actor.set(id(2));
        assertThat(query(batch, 1, 1).get("total").asInt()).isEqualTo(3);
        // 仅在隔离测试数据中模拟可信的组织或任命变更。
        jdbc.update("UPDATE sys_admin SET dept_id=? WHERE user_id=?", id(22), id(2));
        var restricted = query(batch, 1, 1);
        assertThat(restricted.get("total").asInt()).isEqualTo(1);
        assertThat(restricted.get("succeeded").asInt()).isEqualTo(1);
        assertThat(restricted.get("failed").asInt()).isZero();
        assertThat(restricted.get("pending").asInt()).isZero();
        assertThat(restricted.get("items")).hasSize(1);
        assertThat(restricted.get("items").get(0).get("userId").asText()).isEqualTo(sid(4));
        assertThat(query(batch, 2, 1).get("items")).isEmpty();
        jdbc.update("UPDATE sys_admin SET dept_id=? WHERE user_id=?", id(30), id(2));
        var hidden = mvc.perform(get("/api/v1/grants/batches/" + batch)).andReturn();
        assertThat(hidden.getResponse().getStatus()).isEqualTo(404);
        assertThat(json.readTree(hidden.getResponse().getContentAsString()).get("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
    }

    // 防止重放请求提前返回，绕过当前写权限或查看权限检查。
    @ParameterizedTest @ValueSource(strings = {"IAM_GRANTS_WRITE", "IAM_GRANTS_VIEW"})
    void replayRequiresCurrentFunctionPermissions(String permission) throws Exception {
        var body = request(List.of(target(6, "PERMISSIONS")));
        String batch = accepted("permission-change", body);
        runTasks();
        state.deniedPermission = permission;
        try {
            var replay = submit("permission-change", body);
            assertThat(replay.getResponse().getStatus()).isEqualTo(403);
            assertThat(json.readTree(replay.getResponse().getContentAsString()).get("code").asText()).isEqualTo("PERMISSION_DENIED");
        } finally {
            state.deniedPermission = null;
        }
        assertThat(dispatch.tasks).isEmpty();
        assertThat(data(submit("permission-change", body), 200).get("batchId").asText()).isEqualTo(batch);
        assertThat(revision()).isEqualTo(2);
        assertThat(count("sys_user_role_source")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Long.class, id(6))).isEqualTo(1);
    }

    @Test void batchLargerThanTwoThousandExecutesAndPaginates() throws Exception {
        List<Map<String, Object>> targets = new ArrayList<>();
        List<Object[]> users = new ArrayList<>();
        for (int i = 1000; i < 3001; i++) {
            long user = id(i);
            users.add(new Object[]{user, "bulk_" + user, "test-only", "test-only", String.format("%064x", user), "test-only", id(21)});
            targets.add(target(i, "LOAD-" + i));
        }
        jdbc.batchUpdate("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status) VALUES(?,?,?,?,?,?,?,3,1)", users);
        long started = System.nanoTime();
        String batch = accepted("large", request(targets));
        runTasks();
        var result = query(batch, 5, 500);
        assertThat(result.get("processingStatus").asText()).isEqualTo("COMPLETED");
        assertThat(result.get("total").asInt()).isEqualTo(2001);
        assertThat(result.get("succeeded").asInt()).isEqualTo(2001);
        assertThat(result.get("items")).hasSize(1);
        assertThat(result.get("items").get(0).get("userId").asText()).isEqualTo(sid(3000));
        System.out.printf("BATCH_2001_ELAPSED_MS=%d%n", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    static class QueueExecutor implements Executor {
        final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        volatile boolean reject;
        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException("test dispatch rejection");
            tasks.add(command);
        }
    }
}
