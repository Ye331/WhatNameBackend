package com.whatname.iam.service.department;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.exception.GlobalExceptionHandler;
import com.whatname.iam.common.security.PhoneNumbers;
import com.whatname.iam.controller.department.DepartmentController;
import com.whatname.iam.dto.department.DepartmentRequests;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.repository.event.ExternalEventRepository;
import com.whatname.iam.service.event.*;
import com.whatname.iam.support.TestDatabase;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 临时 MySQL + 真实 HTTP；部门安全入口（管理员、HR/OA 认证、签名审计）换成测试替身。 外部事件和审计记录按留存规则只追加不删除。 */
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DepartmentBusinessIntegrationTest {
    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.whatname.iam.entity")
    @EnableJpaRepositories({"com.whatname.iam.repository.department", "com.whatname.iam.repository.event"})
    @Import({
        DepartmentService.class,
        ExternalEventService.class,
        DepartmentController.class,
        GlobalExceptionHandler.class,
    })
    static class TestApplication {
        @Bean
        TestPort testPort(JdbcTemplate jdbc) {
            return new TestPort(jdbc);
        }

        @Bean
        com.whatname.iam.service.permission.ServiceOaCaller testOaCaller(TestPort port) {
            return port.oaCaller();
        }

        @Bean
        RecoveryLockObserver recoveryLockObserver() {
            return new RecoveryLockObserver();
        }
    }

    /** Observe the actual recovery repository connection, without replacing its SQL or transaction. */
    @org.aspectj.lang.annotation.Aspect
    static class RecoveryLockObserver extends com.whatname.iam.support.RepositoryProbe {
        final ThreadLocal<CompletableFuture<Long>> nextLock = new ThreadLocal<>();
        final ThreadLocal<PausedBusinessLock> beforeBusinessLock = new ThreadLocal<>();

        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            boolean eventLock =
                    invocation.id()
                            .equals("com.whatname.iam.repository.event.ExternalEventRepository.lock");
            var pause = beforeBusinessLock.get();
            // The claim transaction performs its initial lookup and inserted-row lookup first.
            if (eventLock && pause != null && ++pause.calls == 3) {
                pause.ready.complete(null);
                assertTrue(
                        pause.release.await(8, TimeUnit.SECONDS), "paused owner release deadline");
            }
            var signal = nextLock.get();
            if (signal != null && eventLock) {
                nextLock.remove();
                try (var statement =
                                invocation.connection()
                                        .createStatement();
                        var result = statement.executeQuery("SELECT CONNECTION_ID()")) {
                    assertTrue(result.next());
                    signal.complete(result.getLong(1));
                }
            }
            return invocation.proceed();
        }
    }

    static class PausedBusinessLock {
        int calls;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        final CountDownLatch release = new CountDownLatch(1);
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.whatname.iam.entity")
    @EnableJpaRepositories({"com.whatname.iam.repository.department", "com.whatname.iam.repository.event"})
    @Import({
        DepartmentService.class,
        ExternalEventService.class,
        DepartmentController.class,
        GlobalExceptionHandler.class,
        DepartmentAccessService.class
    })
    static class DefaultApplication {}

    static class TestPort extends DepartmentAccessService {
        final JdbcTemplate jdbc;
        volatile String fault = "";
        volatile Set<Long> visible;
        volatile boolean denyRecovery;

        TestPort(JdbcTemplate jdbc) {
            super(null, null, null, null, null, null, null);
            this.jdbc = jdbc;
        }

        /** 同一测试身份也充当 OA 服务认证，复用下面的事件校验与恢复规则。 */
        com.whatname.iam.service.permission.ServiceOaCaller oaCaller() {
            return new com.whatname.iam.service.permission.ServiceOaCaller(null) {
                @Override
                public TrustedEventContext authenticate(
                        HttpServletRequest request, byte[] rawBody) {
                    throw new UnsupportedOperationException(
                            "Event fixture has no OA HTTP authentication");
                }

                @Override
                public void verifyTrustedEvent(TrustedEventContext c, String type) {
                    TestPort.this.verifyTrustedEvent(c, type);
                }

                @Override
                public void authorizeRecovery(TrustedEventContext c, String type) {
                    TestPort.this.authorizeRecovery(c, type);
                }
            };
        }

        private void reject(String stage) {
            if (fault.equals(stage))
                throw new BusinessException(
                        stage.equals("audit")
                                ? ErrorCode.AUDIT_UNAVAILABLE
                                : stage.equals("permission")
                                        ? ErrorCode.PERMISSION_DENIED
                                        : ErrorCode.INTEGRITY_FAILURE);
        }

        public Manager authenticateManager(HttpServletRequest r, String permission) {
            if (!"test-manager".equals(r.getHeader("X-Test-Identity")))
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            reject("permission");
            return new Manager("dept-test-manager");
        }

        public TrustedEventContext authenticateHr(HttpServletRequest r, String type) {
            if (!"test-hr".equals(r.getHeader("X-Test-Identity")))
                throw new BusinessException(ErrorCode.INVALID_SERVICE);
            return new TrustedEventContext("HR", "dept-test-hr", UUID.randomUUID().toString());
        }

        public void verifyTrustedEvent(TrustedEventContext c, String type) {
            if (!Set.of("dept-test-hr", "dept-test-oa").contains(c.clientId()))
                throw new BusinessException(ErrorCode.INVALID_SERVICE);
        }

        public void authorizeRecovery(TrustedEventContext c, String type) {
            if (denyRecovery) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
            verifyTrustedEvent(c, type);
        }

        public Set<Long> scope(Manager actor, String p, List<Department> all) {
            reject("permission");
            return visible == null
                    ? all.stream()
                            .map(Department::getId)
                            .collect(java.util.stream.Collectors.toSet())
                    : visible;
        }

        public void verifyOrganization(List<Department> all) {
            reject("verify");
        }

        public void verifyUsers(List<Map<String, Object>> users) {
            reject("verifyUsers");
        }

        public void prepareOrganizationChange(TrustedEventContext actor, String eventId) {
            reject("scopePrepare");
        }

        public void organizationChanged(Set<Long> ids, List<Map<String, Object>> users) {
            reject("scopeChange");
        }

        public void signAndAudit(String operation, Object actor, Map<String, Object> evidence) {
            reject("sign");
            jdbc.update(
                    "INSERT INTO"
                        + " sys_operation_log(request_id,source_system,operation_type,operation_desc)"
                        + " VALUES(?,?,?,?)",
                    "dept-" + UUID.randomUUID(),
                    "HR",
                    operation,
                    "dept isolated C/D test port");
            reject("audit");
        }

        @Override
        public void prepareManagement(Manager actor) {}

        @Override
        public void prepareDepartmentManagement(Manager actor, long departmentId) {
            prepareManagement(actor);
        }
    }

    ServletWebServerApplicationContext app;
    JdbcTemplate db;
    DepartmentService service;
    ExternalEventService events;
    TestPort port;
    ObjectMapper json;

    String prefix;
    String base;
    List<Map<String, Object>> seedBefore;
    final AtomicInteger sequence = new AtomicInteger();
    final TrustedEventContext hr = new TrustedEventContext("HR", "dept-test-hr", "test-request");
    final OffsetDateTime time = OffsetDateTime.parse("2026-10-16T12:00:00+08:00");

    @BeforeAll
    void start() {
        prefix = "T06-" + Long.toString(System.currentTimeMillis(), 36) + "-";
        String key =
                Base64.getEncoder()
                        .encodeToString(new byte[32]); // isolated test key, no production default
        byte[] hmacBytes = new byte[32];
        Arrays.fill(hmacBytes, (byte) 1);
        String hmacKey = Base64.getEncoder().encodeToString(hmacBytes);
        app =
                (ServletWebServerApplicationContext)
                        new SpringApplication(TestApplication.class)
                                .run(
                                        TestDatabase.arguments(
                                                "--server.port=18086",
                                                "--server.address=127.0.0.1",
                                                "--logging.level.root=WARN",
                                                "--spring.main.banner-mode=off"));
        db = app.getBean(JdbcTemplate.class);
        service = app.getBean(DepartmentService.class);
        events = app.getBean(ExternalEventService.class);
        port = app.getBean(TestPort.class);
        json = app.getBean(ObjectMapper.class);
        base = "http://127.0.0.1:18086/api/v1";
        seedBefore =
                db.queryForList(
                        "SELECT * FROM sys_dept WHERE dept_code LIKE 'TEST-SEED-%' ORDER BY id");
        assertEquals(3, seedBefore.size());
    }

    @AfterEach
    void resetPort() {
        port.fault = "";
        port.visible = null;
        port.denyRecovery = false;
    }

    @AfterAll
    void cleanup() {
        try {
            if (db != null) {
                // Permanent-account/source triggers are respected. Precisely retire this run's
                // fixtures.
                db.update(
                        "UPDATE sys_user_role_source SET status=0,revoked_at=CURRENT_TIMESTAMP"
                                + " WHERE status=1 AND user_id IN (SELECT id FROM sys_user WHERE"
                                + " username LIKE ?)",
                        prefix + "%");
                db.update(
                        "DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE"
                                + " username LIKE ?)",
                        prefix + "%");
                db.update("UPDATE sys_role SET status=0 WHERE role_code LIKE ?", prefix + "%");
                db.update(
                        "UPDATE sys_user SET status=3,session_version=session_version+1 WHERE"
                                + " username LIKE ? AND status<>3",
                        prefix + "%");
                db.update("UPDATE sys_dept SET status=0 WHERE dept_code LIKE ?", prefix + "%");
                assertEquals(
                        seedBefore,
                        db.queryForList(
                                "SELECT * FROM sys_dept WHERE dept_code LIKE 'TEST-SEED-%' ORDER BY"
                                        + " id"));
            }
        } finally {
            if (app != null) app.close();
        }
    }

    String event() {
        return prefix + "E" + sequence.incrementAndGet();
    }

    String code(String name) {
        return prefix + name + sequence.incrementAndGet();
    }

    DepartmentRequests.Sync sync(
            String code, String parent, long version, String manager, String event) {
        return new DepartmentRequests.Sync(
                "HR", event, version, code, "Name " + code, parent, manager, time);
    }

    long create(String code, String parent, long version) {
        return Long.parseLong(
                service.sync(hr, code, sync(code, parent, version, null, event()))
                        .get("deptId")
                        .toString());
    }

    Map<String, Object> row(long id) {
        return db.queryForMap("SELECT * FROM sys_dept WHERE id=?", id);
    }

    long user(long dept, String sg) {
        String name = prefix + "user" + sequence.incrementAndGet();
        String phone =
                "199"
                        + String.format(
                                "%08d",
                                (System.currentTimeMillis() + sequence.incrementAndGet())
                                        % 100_000_000L);
        db.update(
                "INSERT INTO"
                    + " sys_user(username,real_name,phone,password_hash,employee_no,dept_id,source,employment_type,hr_version)"
                    + " VALUES(?,?,?,?,?,?,1,1,10)",
                name,
                ("isolated test"),
                (phone),
                "test-not-login-hash",
                sg,
                dept);
        return db.queryForObject("SELECT id FROM sys_user WHERE username=?", Long.class, name);
    }

    DepartmentRequests.Merge mergeRequest(
            String source, String target, long version, String event) {
        return new DepartmentRequests.Merge("HR", event, version, source, target, time);
    }

    HttpResponse<String> http(String method, String path, Object body, String identity)
            throws Exception {
        var b =
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json");
        if (identity != null) b.header("X-Test-Identity", identity);
        b.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    void error(ErrorCode code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(BusinessException.class, work).errorCode());
    }

    @Test
    void hr01HttpCreateManagerOptionalPreservedAndOldVersionRejected() throws Exception {
        String root = code("ROOT"), child = code("SYNC");
        long rootId = create(root, "", 1);
        var body = sync(child, root, 1, null, event());
        HttpResponse<String> response =
                http("PUT", "/integrations/hr/departments/" + child, body, "test-hr");
        assertEquals(200, response.statusCode());
        long id = Long.parseLong(json.readTree(response.body()).at("/data/deptId").asText());
        assertNull(row(id).get("manager_id"));
        assertTrue(json.readTree(response.body()).at("/data/deptId").isTextual());
        String sg = prefix + "SG1";
        long manager = user(rootId, sg);
        service.sync(hr, child, sync(child, root, 2, sg, event()));
        assertEquals(manager, ((Number) row(id).get("manager_id")).longValue());
        service.sync(hr, child, sync(child, root, 3, null, event()));
        assertEquals(manager, ((Number) row(id).get("manager_id")).longValue());
        error(
                ErrorCode.INVALID_DEPT_MAPPING,
                () -> service.sync(hr, child, sync(child, root, 4, "unknown-manager", event())));
        error(
                ErrorCode.INVALID_HR_ORDER,
                () -> service.sync(hr, child, sync(child, root, 2, null, event())));
        assertEquals(3L, ((Number) row(id).get("hr_version")).longValue());
        assertEquals(response.body().contains("password"), false);
    }

    @Test
    void syncReparentsWholeSubtreeAndRejectsCycles() {
        String root = code("ROOT"), a = code("A"), b = code("B"), c = code("C");
        create(root, "", 1);
        long ai = create(a, root, 1), bi = create(b, root, 1), ci = create(c, a, 1);
        service.sync(hr, a, sync(a, b, 2, null, event()));
        assertEquals(
                row(bi).get("dept_path") + Long.toString(ai) + "/" + ci + "/",
                row(ci).get("dept_path"));
        Map<String, Object> before = row(bi);
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.sync(hr, b, sync(b, c, 2, null, event())));
        assertEquals(before, row(bi));
    }

    @Test
    void mergeVersionOnlySourceMovesPeopleChildrenAndRetainsChildFreeze() {
        String root = code("ROOT"), s = code("SRC"), t = code("DST"), c = code("CHILD");
        create(root, "", 1);
        long si = create(s, root, 10), ti = create(t, root, 40), ci = create(c, s, 3);
        long uid = user(si, prefix + "SG-MERGE");
        String role = code("ROLE");
        db.update(
                "INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",
                role,
                "dept isolated test role");
        long roleId =
                db.queryForObject("SELECT id FROM sys_role WHERE role_code=?", Long.class, role);
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", uid, roleId);
        db.update(
                "INSERT INTO"
                    + " sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id)"
                    + " VALUES(?,?,'DEPT_POSITION',?,?)",
                uid,
                roleId,
                prefix + "test-position",
                si);
        var grantsBefore = db.queryForList("SELECT * FROM sys_user_role WHERE user_id=?", uid);
        var sourcesBefore =
                db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?", uid);
        service.freeze(new DepartmentAccessService.Manager("test"), ci, true, "test child freeze");
        var beforeUser = db.queryForMap("SELECT * FROM sys_user WHERE id=?", uid);
        var request = mergeRequest(s, t, 11, event());
        Map<String, Object> result = service.merge(hr, request);
        assertEquals(true, result.get("rolesPreserved"));
        assertEquals(ti, ((Number) row(si).get("merged_into_dept_id")).longValue());
        assertEquals(11L, ((Number) row(si).get("hr_version")).longValue());
        assertEquals(40L, ((Number) row(ti).get("hr_version")).longValue());
        assertEquals(ti, ((Number) row(ci).get("parent_id")).longValue());
        assertEquals(0, ((Number) row(ci).get("status")).intValue());
        assertEquals(row(ti).get("dept_path") + Long.toString(ci) + "/", row(ci).get("dept_path"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_dept WHERE parent_id=?", Integer.class, si));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_user WHERE dept_id=?", Integer.class, si));
        var afterUser = db.queryForMap("SELECT * FROM sys_user WHERE id=?", uid);
        beforeUser.put("dept_id", ti);
        beforeUser.put("updated_at", afterUser.get("updated_at"));
        assertEquals(beforeUser, afterUser);
        assertEquals(
                grantsBefore, db.queryForList("SELECT * FROM sys_user_role WHERE user_id=?", uid));
        assertEquals(
                sourcesBefore,
                db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?", uid));
        assertEquals(result, service.merge(hr, request));
        String invalid = code("INVALID");
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.sync(hr, invalid, sync(invalid, s, 1, null, event())));
    }

    @Test
    void frozenSourceOrAncestorRequiresExplicitUnfreeze() {
        String root = code("ROOT"), s = code("SRC"), t = code("DST");
        long ri = create(root, "", 1), si = create(s, root, 10);
        create(t, "", 40);
        var actor = new DepartmentAccessService.Manager("test");
        service.freeze(actor, ri, true, "freeze root");
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.merge(hr, mergeRequest(s, t, 11, event())));
        service.freeze(actor, si, false, "unfreeze child only");
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.merge(hr, mergeRequest(s, t, 11, event())));
        service.freeze(actor, ri, false, "explicit root recovery");
        service.freeze(actor, si, true, "freeze source");
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.merge(hr, mergeRequest(s, t, 11, event())));
        service.freeze(actor, si, false, "explicit source recovery");
        service.merge(hr, mergeRequest(s, t, 11, event()));
    }

    @Test
    void selfAndDescendantMergeRejected() {
        String root = code("ROOT"), child = code("CHILD");
        long ri = create(root, "", 10);
        create(child, root, 40);
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.merge(hr, mergeRequest(root, root, 11, event())));
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.merge(hr, mergeRequest(root, child, 11, event())));
        assertNull(row(ri).get("merged_into_dept_id"));
    }

    @Test
    void dp01Dp02ScopePaginationStringsAndHttpFreeze() throws Exception {
        String root = code("ROOT"), child = code("CHILD"), outside = code("OUT");
        long ri = create(root, "", 1), ci = create(child, root, 1), oi = create(outside, "", 1);
        user(ri, prefix + "SG-LIST1");
        user(ci, prefix + "SG-LIST2");
        user(oi, prefix + "SG-OUT");
        port.visible = Set.of(ri, ci);
        JsonNode tree =
                json.readTree(
                                http(
                                                "GET",
                                                "/departments?includeDescendants=false",
                                                null,
                                                "test-manager")
                                        .body())
                        .path("data");
        assertEquals(1, tree.path("items").size());
        assertEquals("0", tree.path("items").get(0).path("parentId").asText());
        assertFalse(tree.toString().contains("deptPath"));
        assertEquals(
                2,
                json.readTree(http("GET", "/departments", null, "test-manager").body())
                        .at("/data/items")
                        .size());
        var direct =
                json.readTree(
                                http("GET", "/departments/" + ri + "/users", null, "test-manager")
                                        .body())
                        .path("data");
        assertEquals(1, direct.path("total").asInt());
        var descendants =
                json.readTree(
                                http(
                                                "GET",
                                                "/departments/"
                                                        + ri
                                                        + "/users?includeDescendants=true&pageSize=1&page=2",
                                                null,
                                                "test-manager")
                                        .body())
                        .path("data");
        assertEquals(2, descendants.path("total").asInt());
        assertEquals(1, descendants.path("items").size());
        assertTrue(descendants.path("items").get(0).path("userId").isTextual());
        port.visible = Set.of(ri);
        assertEquals(
                1,
                json.readTree(
                                http(
                                                "GET",
                                                "/departments/"
                                                        + ri
                                                        + "/users?includeDescendants=true",
                                                null,
                                                "test-manager")
                                        .body())
                        .at("/data/total")
                        .asInt());
        assertEquals(
                403,
                http("GET", "/departments/" + oi + "/users", null, "test-manager").statusCode());
        assertEquals(
                400,
                http("GET", "/departments/" + ri + "/users?pageSize=501", null, "test-manager")
                        .statusCode());
        assertEquals(
                200,
                http(
                                "POST",
                                "/departments/" + ri + "/freeze",
                                Map.of("reason", "HTTP test"),
                                "test-manager")
                        .statusCode());
        assertEquals(0, ((Number) row(ri).get("status")).intValue());
        assertEquals(
                200,
                http(
                                "POST",
                                "/departments/" + ri + "/unfreeze",
                                Map.of("reason", "HTTP restore"),
                                "test-manager")
                        .statusCode());
        port.fault = "permission";
        assertEquals(
                403,
                http(
                                "POST",
                                "/departments/" + ri + "/unfreeze",
                                Map.of("reason", "deny"),
                                "test-manager")
                        .statusCode());
        assertEquals(401, http("GET", "/departments", null, null).statusCode());
    }

    @Test
    void signaturesAuditAndScopeFailureRollBackMergeAndKeepPending() {
        for (String stage : List.of("verify", "verifyUsers", "scopeChange", "sign", "audit")) {
            String root = code("ROOT"), s = code("SRC"), t = code("DST");
            create(root, "", 1);
            long si = create(s, root, 10),
                    ti = create(t, root, 40),
                    uid = user(si, prefix + "SG-F" + sequence.incrementAndGet());
            var before = row(si);
            String event = event();
            long audits = db.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Long.class);
            port.fault = stage;
            error(
                    stage.equals("audit")
                            ? ErrorCode.AUDIT_UNAVAILABLE
                            : ErrorCode.INTEGRITY_FAILURE,
                    () -> service.merge(hr, mergeRequest(s, t, 11, event)));
            assertEquals(before, row(si));
            assertEquals(
                    si,
                    db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?", Long.class, uid));
            assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", Integer.class, event));
            assertEquals(
                    audits,
                    db.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Long.class));
            assertEquals(40L, ((Number) row(ti).get("hr_version")).longValue());
            port.fault = "";
        }
    }

    @Test
    void freezeAuditFailureRollsBackWithoutAccountChanges() {
        String r = code("ROOT");
        long id = create(r, "", 1), u = user(id, prefix + "SG-FREEZE");
        var before = row(id);
        var user = db.queryForMap("SELECT * FROM sys_user WHERE id=?", u);
        port.fault = "audit";
        error(
                ErrorCode.AUDIT_UNAVAILABLE,
                () ->
                        service.freeze(
                                new DepartmentAccessService.Manager("test"),
                                id,
                                true,
                                "failed freeze"));
        assertEquals(before, row(id));
        assertEquals(user, db.queryForMap("SELECT * FROM sys_user WHERE id=?", u));
    }

    @Test
    void sameEventConcurrentAppliesExactlyOnceAndDifferentContentConflicts() throws Exception {
        String code = code("CONCURRENT");
        var request = sync(code, "", 1, null, event());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Object>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++)
            futures.add(
                    pool.submit(
                            () -> {
                                go.await();
                                try {
                                    return service.sync(hr, code, request);
                                } catch (BusinessException e) {
                                    return e.errorCode();
                                }
                            }));
        go.countDown();
        for (Future<Object> future : futures) {
            Object r = future.get(30, TimeUnit.SECONDS);
            assertTrue(r instanceof Map || r == ErrorCode.VERSION_CONFLICT);
        }
        pool.shutdownNow();
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_dept WHERE dept_code=?", Integer.class, code));
        assertEquals(service.sync(hr, code, request), service.sync(hr, code, request));
        error(
                ErrorCode.IDEMPOTENCY_CONFLICT,
                () ->
                        service.sync(
                                hr,
                                code,
                                new DepartmentRequests.Sync(
                                        "HR",
                                        request.eventId(),
                                        1L,
                                        code,
                                        "changed",
                                        "",
                                        null,
                                        time)));
        assertTrue(
                db.queryForObject(
                                "SELECT payload_json FROM sys_external_event WHERE"
                                        + " event_id=?",
                                String.class,
                                request.eventId())
                        .startsWith("{"));
    }



    @Test
    void concurrentReparentingCannotCreateCycle() throws Exception {
        String r = code("ROOT"), a = code("A"), b = code("B");
        create(r, "", 1);
        long ai = create(a, r, 1), bi = create(b, r, 1);
        var ar = sync(a, b, 2, null, event());
        var br = sync(b, a, 2, null, event());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<?> f1 =
                pool.submit(
                        () -> {
                            try {
                                go.await();
                                service.sync(hr, a, ar);
                            } catch (Exception expected) {
                            }
                        });
        Future<?> f2 =
                pool.submit(
                        () -> {
                            try {
                                go.await();
                                service.sync(hr, b, br);
                            } catch (Exception expected) {
                            }
                        });
        go.countDown();
        f1.get(35, TimeUnit.SECONDS);
        f2.get(35, TimeUnit.SECONDS);
        pool.shutdownNow();
        assertFalse(
                ((Number) row(ai).get("parent_id")).longValue() == bi
                        && ((Number) row(bi).get("parent_id")).longValue() == ai);
        assertTrue(
                ((Number) row(ai).get("hr_version")).longValue() == 2
                        || ((Number) row(bi).get("hr_version")).longValue() == 2);
    }

    @Test
    void mergedAssignmentRejectedAndSourceSpoofRejected() {
        String s = code("SRC"), t = code("DST");
        long si = create(s, "", 10);
        create(t, "", 40);
        service.merge(hr, mergeRequest(s, t, 11, event()));
        var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> tx.execute(status -> service.requireAssignableDepartment(si)));
        String child = code("CHILD");
        error(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> service.sync(hr, child, sync(child, s, 1, null, event())));
        error(
                ErrorCode.INVALID_SERVICE,
                () ->
                        service.sync(
                                new TrustedEventContext("HR", "forged", "test"),
                                t,
                                sync(t, "", 41, null, event())));
    }

    @Test
    void hr05RealHttpAndMergedNodesRemainVisibleAsEmptySources() throws Exception {
        String s = code("SRC"), t = code("DST");
        long si = create(s, "", 10), ti = create(t, "", 40);
        port.visible = Set.of(si, ti);
        var response =
                http("POST", "/integrations/hr/merges", mergeRequest(s, t, 11, event()), "test-hr");
        assertEquals(200, response.statusCode());
        assertTrue(json.readTree(response.body()).at("/data/rolesPreserved").asBoolean());
        var items =
                json.readTree(http("GET", "/departments", null, "test-manager").body())
                        .at("/data/items");
        JsonNode source = null;
        for (JsonNode item : items)
            if (item.path("deptId").asText().equals(Long.toString(si))) source = item;
        assertNotNull(source);
        assertTrue(source.path("merged").asBoolean());
        assertEquals(Long.toString(ti), source.path("mergedIntoDeptId").asText());
        assertEquals(
                0,
                json.readTree(
                                http(
                                                "GET",
                                                "/departments/"
                                                        + si
                                                        + "/users?includeDescendants=true",
                                                null,
                                                "test-manager")
                                        .body())
                        .at("/data/total")
                        .asInt());
    }

    @Test
    void defaultProductionAssemblyRefusesAllSixHttpEntrypoints() throws Exception {
        // 最终应用缺少必需模块时应拒绝启动，不再装配阶段占位服务。
        assertThrows(
                org.springframework.beans.factory.UnsatisfiedDependencyException.class,
                () ->
                        new SpringApplication(DefaultApplication.class)
                                .run(
                                        TestDatabase.arguments(
                                                "--server.port=0",
                                                "--logging.level.root=OFF",
                                                "--spring.main.banner-mode=off")));
    }



    private void assertRedactedEventFailureDiagnostics() {
        var logger =
                (ch.qos.logback.classic.Logger)
                        org.slf4j.LoggerFactory.getLogger(ExternalEventService.class);
        var captured =
                new ch.qos.logback.core.read.ListAppender<
                        ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            var oa = new TrustedEventContext("OA", "dept-test-oa", "diagnostic-test");
            String savedEvent = event();
            var saved =
                    new ExternalEventService.Command(
                            savedEvent,
                            "OA_DECISION",
                            null,
                            "USER",
                            "test-subject",
                            null,
                            Map.of("decision", "APPROVE"));
            error(
                    ErrorCode.AUTH_STATE_UNAVAILABLE,
                    () ->
                            events.execute(
                                    oa,
                                    saved,
                                    () -> {
                                        throw new IllegalStateException("private-payload-marker");
                                    }));
            assertEquals("PENDING_RECOVERY", eventState(savedEvent).get("processing_status"));

            var delegate = app.getBean(ExternalEventRepository.class);
            var failing =
                    mock(
                            ExternalEventRepository.class,
                            org.mockito.AdditionalAnswers.delegatesTo(delegate));
            doThrow(new IllegalStateException("private-record-marker"))
                    .when(failing)
                    .failed(anyLong(), anyString(), anyString());
            var unreliable =
                    new ExternalEventService(
                            failing,

                            port,
                            json,
                            app.getBean(PlatformTransactionManager.class),
                            port.oaCaller());
            String retainedEvent = event();
            var retained =
                    new ExternalEventService.Command(
                            retainedEvent,
                            "OA_DECISION",
                            null,
                            "USER",
                            "test-subject",
                            null,
                            Map.of("decision", "APPROVE"));
            error(
                    ErrorCode.AUTH_STATE_UNAVAILABLE,
                    () ->
                            unreliable.execute(
                                    oa,
                                    retained,
                                    () -> {
                                        throw new IllegalArgumentException(
                                                "private-payload-marker");
                                    }));
            assertEquals("PROCESSING", eventState(retainedEvent).get("processing_status"));

            String diagnostics =
                    captured.list.stream()
                            .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                            .filter(message -> message.startsWith("External event "))
                            .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(
                    diagnostics.contains("failure=IllegalStateException state=PENDING_RECOVERY"));
            assertTrue(
                    diagnostics.contains(
                            "failure=IllegalArgumentException"
                                    + " recordFailure=IllegalStateException"));
            assertFalse(diagnostics.contains("private-payload-marker"));
            assertFalse(diagnostics.contains("private-record-marker"));
            assertFalse(diagnostics.contains(savedEvent));
            assertFalse(diagnostics.contains(retainedEvent));
        } finally {
            logger.detachAppender(captured);
            captured.stop();
        }
    }










    ExternalEventService.Command interleavingCommand(
            String event, String dept, String request, String nonce) {
        return new ExternalEventService.Command(
                event,
                "OA_DECISION",
                null,
                "DEPT",
                dept,
                null,
                Map.of("decision", "APPROVE", "version", 11, "requestId", request, "nonce", nonce));
    }

    Map<String, Object> eventState(String event) {
        return db.queryForMap(
                "SELECT"
                    + " processing_status,processing_stage,result_data,result_summary,payload_digest"
                    + " FROM sys_external_event WHERE source_system='OA' AND event_id=?",
                event);
    }

    int auditCount(String event) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE source_system='OA' AND"
                        + " external_event_id=? AND operation_type='EVENT_EVENT_APPLY'",
                Integer.class,
                event);
    }

    int signatureCount(long id) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM sys_data_signature WHERE data_type='EVENT_DEPT' AND"
                        + " data_id=?",
                Integer.class,
                id);
    }

    Map<String, Object> applyInterleavingFact(long id, String event) {
        assertTrue(
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .isActualTransactionActive());
        assertEquals(
                Connection.TRANSACTION_SERIALIZABLE,
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .getCurrentTransactionIsolationLevel());
        assertEquals(
                1,
                db.update(
                        "UPDATE sys_dept SET hr_version=hr_version+1,dept_name=? WHERE id=?",
                        event,
                        id));
        // Explicit test evidence only: this fixed 88-character marker is NOT a real signature.
        assertEquals(
                1,
                db.update(
                        "INSERT INTO sys_data_signature(data_type,data_id,signature,key_id)"
                                + " VALUES('EVENT_DEPT',?,?,'event-test')",
                        id,
                        "T".repeat(88)));
        assertEquals(
                1,
                db.update(
                        "INSERT INTO"
                            + " sys_operation_log(request_id,source_system,external_event_id,operation_type,target_type,target_id,evidence,operation_desc)"
                            + " VALUES(?,'OA',?,'EVENT_EVENT_APPLY','DEPT',?,JSON_OBJECT('beforeVersion',10,'afterVersion',11),'event"
                            + " test evidence; not D audit')",
                        event,
                        event,
                        id));
        return Map.of(
                "deptId",
                Long.toString(id),
                "eventId",
                event,
                "version",
                11L,
                "processingStatus",
                "APPLIED");
    }

    void assertInterleavingCommitted(long id, String event, Map<String, Object> result)
            throws Exception {
        var tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // The caller has no transaction: these reads cannot see the callback's uncommitted state.
        var state =
                tx.execute(
                        status -> {
                            assertEquals(11L, ((Number) row(id).get("hr_version")).longValue());
                            assertEquals(event, row(id).get("dept_name"));
                            assertEquals(1, auditCount(event));
                            assertEquals(1, signatureCount(id));
                            assertEquals(
                                    "T".repeat(88),
                                    db.queryForObject(
                                            "SELECT signature FROM sys_data_signature WHERE"
                                                    + " data_type='EVENT_DEPT' AND data_id=?",
                                            String.class,
                                            id));
                            var audit =
                                    db.queryForMap(
                                            "SELECT target_id,evidence FROM sys_operation_log WHERE"
                                                + " source_system='OA' AND external_event_id=? AND"
                                                + " operation_type='EVENT_EVENT_APPLY'",
                                            event);
                            assertEquals(id, ((Number) audit.get("target_id")).longValue());
                            assertEquals(
                                    json.valueToTree(
                                            Map.of("beforeVersion", 10, "afterVersion", 11)),
                                    assertDoesNotThrow(
                                            () -> json.readTree((String) audit.get("evidence"))));
                            return eventState(event);
                        });
        assertEquals("APPLIED", state.get("processing_status"));
        assertEquals("COMPLETED", state.get("processing_stage"));
        assertEquals(semanticResult(result), json.readTree((String) state.get("result_data")));
    }

    JsonNode semanticResult(Object value) throws Exception {
        return json.readTree(json.writeValueAsString(value));
    }

    static class LostTestDelivery extends RuntimeException {}

    static void discardCommittedDelivery(java.util.function.Supplier<Map<String, Object>> call) {
        Objects.requireNonNull(call.get());
        throw new LostTestDelivery();
    }

    @Test
    void frozenAncestorAndHiddenMergeDestinationArePresentedWithinScope() {
        String root = code("ROOT"), child = code("CHILD"), target = code("HIDDEN");
        long ri = create(root, "", 10), ci = create(child, root, 1), ti = create(target, "", 40);
        var actor = new DepartmentAccessService.Manager("test");
        service.freeze(actor, ri, true, "ancestor presentation");
        service.freeze(actor, ci, false, "child cannot undo ancestor");
        port.visible = Set.of(ci);
        Map<?, ?> item = (Map<?, ?>) ((List<?>) service.tree(actor, true).get("items")).getFirst();
        assertEquals(1, item.get("status"));
        assertEquals(true, item.get("effectivelyFrozen"));
        assertEquals("0", item.get("parentId"));
        assertFalse(item.containsKey("deptPath"));
        port.visible = null;
        service.freeze(actor, ri, false, "authorized ancestor recovery");
        service.merge(hr, mergeRequest(root, target, 11, event()));
        port.visible = Set.of(ri);
        item = (Map<?, ?>) ((List<?>) service.tree(actor, true).get("items")).getFirst();
        assertEquals(true, item.get("merged"));
        assertNull(item.get("mergedIntoDeptId"));
        assertEquals(0L, service.users(actor, ri, true, 1, 20).get("total"));
        assertEquals(ti, ((Number) row(ci).get("parent_id")).longValue());
    }

    @Test
    void eventCallbackWritesAndAppliedRollBackOnUnsafeResult() {
        var oa = new TrustedEventContext("OA", "dept-test-oa", "transaction-test");
        String event = event(), probe = code("TX-PROBE");
        var command =
                new ExternalEventService.Command(
                        event,
                        "OA_DECISION",
                        null,
                        "USER",
                        "test-subject",
                        null,
                        Map.of("decision", "APPROVE"));
        error(
                ErrorCode.INVALID_ARGUMENT,
                () ->
                        events.execute(
                                oa,
                                command,
                                () -> {
                                    assertTrue(
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .isActualTransactionActive());
                                    assertEquals(
                                            java.sql.Connection.TRANSACTION_SERIALIZABLE,
                                            org.springframework.transaction.support
                                                    .TransactionSynchronizationManager
                                                    .getCurrentTransactionIsolationLevel());
                                    db.update(
                                            "INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",
                                            probe,
                                            "transaction rollback probe");
                                    return Map.of("accessToken", "must never be retained");
                                }));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_role WHERE role_code=?", Integer.class, probe));
        assertEquals(0, db.queryForObject(
                "SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", Integer.class, event));
    }


}
