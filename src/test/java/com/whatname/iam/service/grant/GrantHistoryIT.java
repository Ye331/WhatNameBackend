package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.config.*;
import com.whatname.iam.common.exception.*;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;

import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 真实的管理员、JWT、签名审计与隔离 MySQL；查询拦截器控制候选分页后的并发范围变化。 */
@SpringBootTest(
        classes = {com.whatname.iam.IamApplication.class, GrantHistoryIT.Application.class},
        properties = "spring.config.import=")
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
class GrantHistoryIT {
    @TestConfiguration
    static class Application {
        @Bean
        Failure failure() {
            return new Failure();
        }
    }

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(
                            DockerImageName.parse(
                                            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("unified_permission")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                    .withCommand("--default-time-zone=+08:00")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("db/schema.sql").toAbsolutePath()),
                            "/docker-entrypoint-initdb.d/01_schema.sql");

    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519");

    static KeyPair keys(String algorithm) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("RSA")) generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> encoded(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> encoded(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "c-stage2");
        r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "test-jwt");
        r.add("iam.security.data-signing.key-id", () -> "test-sign");
        r.add("iam.security.data-signing.private-key", () -> encoded(ED.getPrivate().getEncoded()));
        r.add(
                "iam.security.data-signing.public-keys.test-sign",
                () -> encoded(ED.getPublic().getEncoded()));
    }

    @org.aspectj.lang.annotation.Aspect
    static class Failure extends com.whatname.iam.support.RepositoryProbe {
        volatile Runnable afterHistoryPage;

        public Object intercept(Invocation call) throws Throwable {
            String id = call.id();
            Object result = call.proceed();
            if (id.endsWith("GrantHistoryRepository.candidates") && afterHistoryPage != null) {
                Runnable action = afterHistoryPage;
                afterHistoryPage = null;
                action.run();
            }
            return result;
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired GrantService grants;
    @Autowired AuditService auditService;
    @Autowired DataSignatureService signatures;
    @Autowired PermissionService permissions;
    @Autowired JwtTokenService jwt;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired Failure failure;
    @Autowired GrantHistoryService historyService;
    @Autowired com.whatname.iam.service.audit.AuditQueryService auditQueries;
    @Autowired BatchService batchService;
    @Autowired com.whatname.iam.repository.grant.BatchRepository batches;
    static final AtomicLong NEXT = new AtomicLong(1000);
    long b;
    String bearer;
    TransactionTemplate tx;

    long id(int offset) {
        return b + offset;
    }

    String sid(int offset) {
        return Long.toString(id(offset));
    }

    @BeforeEach
    void seed() {
        b = NEXT.getAndAdd(1000);
        failure.afterHistoryPage = null;
        tx = new TransactionTemplate(manager);
        db.update(
                "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                        + " VALUES(?,0,?,'Fixture','/')",
                id(1),
                "D" + b);
        db.update(
                "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                        + " VALUES(?,?,?,'Fixture','/')",
                id(2),
                id(1),
                "L" + b);
        for (int u : List.of(10, 11, 20))
            db.update(
                    "INSERT INTO"
                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                        + " VALUES(?,?, 'fixture',?,'fixture',?,3,1,1)",
                    id(u),
                    "U" + id(u),
                    String.format("138%08d", id(u)),
                    id(u == 11 ? 2 : 1));
        db.update(
                "INSERT INTO sys_admin(user_id,admin_level,dept_id,status)"
                        + " VALUES(?,1,?,1),(?,2,?,1)",
                id(10),
                id(1),
                id(11),
                id(2));
        db.update(
                "INSERT INTO sys_role(id,role_code,role_name,role_type)"
                        + " VALUES(?,?,'Business',1),(?,?,'Manager',2)",
                id(30),
                "R" + b,
                id(31),
                "M" + b);
        db.update(
                "INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                        + " VALUES(?,?,?)",
                id(11),
                id(30),
                id(10));
        db.update(
                "INSERT INTO sys_application(id,app_code,app_name) VALUES(?,?,'Fixture')",
                id(40),
                "C" + b);
        int n = 50;
        for (String code :
                List.of(
                        "IAM_GRANTS_WRITE",
                        "IAM_GRANTS_VIEW",
                        "IAM_USERS_VIEW",
                        "IAM_ADMINS_WRITE",
                        "IAM_ADMINS_VIEW",
                        "IAM_GRANTS_DELEGATE",
                        "BUSINESS_VIEW")) {
            db.update(
                    "INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type)"
                            + " VALUES(?,?,0,?,'Fixture',2)",
                    id(n),
                    id(40),
                    code);
            db.update(
                    "INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)",
                    id(n == 56 ? 30 : 31),
                    id(n++));
        }
        db.update(
                "INSERT INTO"
                    + " sys_api(id,app_id,api_code,api_name,api_path,canonical_path,http_method)"
                    + " VALUES(?,?,'READ','Fixture','/records/{id}','/records/{}','GET')",
                id(60),
                id(40));
        db.update("INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?)", id(56), id(60));
        for (int u : List.of(10, 11)) {
            db.update(
                    "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                    id(u),
                    id(31),
                    id(10));
            db.update(
                    "INSERT INTO"
                        + " sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by)"
                        + " VALUES(?,?,'MANUAL','bootstrap',?)",
                    id(u),
                    id(31),
                    id(10));
        }
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(ORGANIZATION, 0);
                    for (long d : db.queryForList("SELECT id FROM sys_dept", Long.class))
                        signatures.sign(DEPARTMENT, d);
                    for (long a : db.queryForList("SELECT user_id FROM sys_admin", Long.class)) {
                        signatures.sign(ADMIN, a);
                        signatures.sign(ADMIN_DELEGABLE_ROLES, a);
                    }
                    for (long r : db.queryForList("SELECT id FROM sys_role", Long.class))
                        signatures.sign(ROLE_MENUS, r);
                    for (long u : db.queryForList("SELECT id FROM sys_user", Long.class))
                        signatures.sign(USER_IDENTITY, u);
                    for (int u : List.of(10, 11, 20)) signatures.sign(USER_GRANTS, id(u));
                    signatures.sign(RESOURCE_APPLICATION, id(40));
                    signatures.sign(RESOURCE_API, id(60));
                    for (int m = 50; m <= 56; m++) {
                        signatures.sign(RESOURCE_MENU, id(m));
                        signatures.sign(MENU_APIS, id(m));
                    }
                });
        org.springframework.test.util.ReflectionTestUtils.setField(
                (Object)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                permissions),
                "managementApplication",
                "C" + b);
        bearer = "Bearer " + jwt.issue(id(10), 1);
        bind(bearer);
    }

    static void bind(String authorization) {
        var request = new MockHttpServletRequest();
        if (authorization != null) request.addHeader("Authorization", authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void cleanup() {
        failure.afterHistoryPage = null;
        RequestContextHolder.resetRequestAttributes();
    }

    long count(String table) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, id(20));
    }

    String grant(int user, String type, String ref) {
        long revision =
                db.queryForObject(
                        "SELECT grant_revision FROM sys_user WHERE id=?", Long.class, id(user));
        return grants.grant(
                        new GrantRequest(
                                sid(user),
                                sid(30),
                                type,
                                ref,
                                "DEPT_POSITION".equals(type) ? sid(1) : null,
                                "historical reason",
                                "v" + revision))
                .grantSourceId();
    }

    void revoke(int user, String source) {
        long revision =
                db.queryForObject(
                        "SELECT grant_revision FROM sys_user WHERE id=?", Long.class, id(user));
        grants.revoke(
                new RevokeRequest(sid(user), sid(30), source, "historical revoke", "v" + revision));
    }

    org.springframework.test.web.servlet.ResultActions query(String... pairs) throws Exception {
        var request = get("/api/v1/grants/history").header("Authorization", bearer);
        for (int n = 0; n < pairs.length; n += 2) request.param(pairs[n], pairs[n + 1]);
        return http.perform(request);
    }

    com.fasterxml.jackson.databind.JsonNode history(String... pairs) throws Exception {
        return json.readTree(
                        query(pairs)
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString())
                .get("data");
    }

    @Test
    void historyRetainsBothTransitionsWhileGr05ContainsOneInactiveSource() throws Exception {
        String source = grant(20, "MANUAL", "MANUAL-1");
        revoke(20, source);
        var result = history("userId", sid(20), "roleId", sid(30));
        assertThat(result.get("total").asLong()).isEqualTo(2);
        assertThat(result.get("items")).hasSize(2);
        assertThat(result.get("items").get(0).get("operation").asText()).isEqualTo("REVOKE");
        assertThat(result.get("items").get(1).get("operation").asText()).isEqualTo("GRANT");
        assertThat(result.get("items").get(0).get("grantSourceId").asText()).isEqualTo(source);
        assertThat(result.get("items").get(1).get("grantSourceId").asText()).isEqualTo(source);
        assertThat(result.get("items").get(0).properties()).hasSize(6);
        assertThat(result.get("items").get(0).get("eventId").asText()).matches("[1-9][0-9]*");
        assertThat(result.get("items").get(0).get("occurredAt").asText()).endsWith("+08:00");
        assertThat(grants.sources(sid(20), sid(30)).sources()).hasSize(1);
        assertThat(grants.sources(sid(20), sid(30)).sources().getFirst().active()).isFalse();
    }

    @Test
    void historicalAuthorsLossOfAppointmentAndDisabledRoleDoNotEraseHistory() throws Exception {
        grant(20, "DEPT_POSITION", "POSITION-1");
        tx.executeWithoutResult(
                s -> {
                    db.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id(10));
                    signatures.sign(ADMIN, id(10));
                    db.update("UPDATE sys_role SET status=0 WHERE id=?", id(30));
                    signatures.sign(ROLE_MENUS, id(30));
                    db.update("UPDATE sys_menu SET status=0 WHERE id=?", id(56));
                    signatures.sign(RESOURCE_MENU, id(56));
                    db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        bearer = "Bearer " + jwt.issue(id(11), 1);
        bind(bearer);
        assertThat(history("userId", sid(20)).get("total").asInt()).isEqualTo(1);
    }

    @Test
    void currentTargetScopeFiltersBeforeCountingAndPagingRegardlessOfAuthorScope()
            throws Exception {
        grant(20, "MANUAL", "VISIBLE");
        grant(10, "MANUAL", "OUTSIDE");
        tx.executeWithoutResult(
                s -> {
                    db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        bearer = "Bearer " + jwt.issue(id(11), 1);
        bind(bearer);
        var page = history("pageSize", "1");
        assertThat(page.get("total").asInt()).isEqualTo(1);
        assertThat(page.get("items").get(0).get("userId").asText()).isEqualTo(sid(20));
        assertThat(history("page", "2", "pageSize", "1").get("items")).isEmpty();
        assertThat(history("userId", sid(10)).get("total").asInt()).isZero();
        tx.executeWithoutResult(
                s -> {
                    db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(1), id(20));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        assertThat(history().get("total").asInt()).isZero();
    }

    @Test
    void currentSourceWithoutRetainedAuditNeverBecomesAnInventedHistoryEvent() throws Exception {
        db.update(
                "INSERT INTO"
                        + " sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by)"
                        + " VALUES(?,?,'MANUAL','LEGACY',?)",
                id(20),
                id(30),
                id(10));
        db.update(
                "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                id(20),
                id(30),
                id(10));
        tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
        assertThat(grants.sources(sid(20), sid(30)).sources()).hasSize(1);
        assertThat(history("userId", sid(20)).get("total").asInt()).isZero();
    }

    @Test
    void visibleLegacyUnsealedGrantAuditFailsExplicitlyRatherThanInventingVerifiedHistory()
            throws Exception {
        db.update(
                "INSERT INTO"
                    + " sys_operation_log(user_id,request_id,operation_type,target_type,target_id,status,evidence)"
                    + " VALUES(?,'legacy','USER_ROLE_GRANT','USER',?,1,'{}')",
                id(10),
                id(20));
        query("userId", sid(20))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTEGRITY_FAILURE"));
    }

    Map<String, Object> source(long source, String type, int state) {
        var result = new LinkedHashMap<String, Object>();
        result.put("id", source);
        result.put("userId", id(20));
        result.put("roleId", id(30));
        result.put("sourceType", type);
        result.put("sourceRef", "HIST-" + source);
        result.put("sourceDeptId", type.equals("DEPT_POSITION") ? id(1) : null);
        result.put("status", state);
        result.put("grantedBy", type.equals("OA_APPROVAL") ? null : id(10));
        result.put("revokedBy", null);
        result.put("createdAt", "2026-10-01T10:00:00");
        result.put("revokedAt", state == 0 ? "2026-10-02T11:00:00" : null);
        return result;
    }

    Map<String, Object> snapshot(long revision, List<Map<String, Object>> sources) {
        return Map.of(
                "userId",
                id(20),
                "grantRevision",
                revision,
                "sources",
                sources,
                "roles",
                sources.stream()
                        .filter(s -> s.get("status").equals(1))
                        .map(s -> s.get("roleId"))
                        .distinct()
                        .toList());
    }

    void historicalAudit(
            String operation,
            String schema,
            List<Map<String, Object>> before,
            List<Map<String, Object>> after,
            Map<String, Object> extra) {
        var retained = new LinkedHashMap<String, Object>(extra);
        retained.put("schema", schema);
        retained.put("before", snapshot(1, before));
        retained.put("after", snapshot(before.equals(after) ? 1 : 2, after));
        tx.executeWithoutResult(
                s ->
                        auditService.change(
                                operation,
                                operation.startsWith("USER_") ? id(10) : null,
                                "USER",
                                id(20),
                                operation.startsWith("USER_") ? null : "EVENT-" + b,
                                retained));
    }

    @Test
    void oneSealedHrEventExpandsToTwoSourcesBeforePagingAndRoleCounting() throws Exception {
        historicalAudit(
                "HR_GRANTS_REVOKE_ALL",
                "C_HR_GRANT_CHANGE_V1",
                List.of(source(901, "MANUAL", 1), source(902, "DEPT_POSITION", 1)),
                List.of(source(901, "MANUAL", 0), source(902, "DEPT_POSITION", 0)),
                Map.of(
                        "revokedSourceIds",
                        List.of(901, 902),
                        "origin",
                        Map.of("sourceSystem", "HR", "eventId", "EVENT-" + b)));
        var first = history("userId", sid(20), "pageSize", "1");
        var second = history("userId", sid(20), "pageSize", "1", "page", "2");
        assertThat(first.get("total").asLong()).isEqualTo(2);
        assertThat(second.get("total").asLong()).isEqualTo(2);
        assertThat(first.at("/items/0/grantSourceId").asText()).isEqualTo("901");
        assertThat(second.at("/items/0/grantSourceId").asText()).isEqualTo("902");
        assertThat(first.at("/items/0/eventId")).isEqualTo(second.at("/items/0/eventId"));
        assertThat(second.at("/items/0/operation").asText()).isEqualTo("REVOKE");
    }

    Map<String, Object> decision(String operation) {
        return Map.of(
                "operation",
                operation,
                "grantSourceId",
                903,
                "targetUserId",
                id(20),
                "roleId",
                id(30));
    }

    @Test
    void unchangedOaDecisionIsNotAHistoryMutation() throws Exception {
        var unchanged = List.of(source(903, "OA_APPROVAL", 1));
        historicalAudit(
                "OA_DECISION",
                "C_OA_CHANGE_V1",
                unchanged,
                unchanged,
                Map.of(
                        "changed",
                        false,
                        "decision",
                        decision("GRANT"),
                        "origin",
                        Map.of("sourceSystem", "OA", "eventId", "EVENT-" + b)));
        assertThat(history("userId", sid(20)).get("total").asInt()).isZero();
    }

    @Test
    void oaHistoryUsesRetainedSourcesEvenWhenNoCurrentSourceRowExists() throws Exception {
        historicalAudit(
                "OA_DECISION",
                "C_OA_CHANGE_V1",
                List.of(),
                List.of(source(903, "OA_APPROVAL", 1)),
                Map.of(
                        "changed",
                        true,
                        "decision",
                        decision("GRANT"),
                        "origin",
                        Map.of("sourceSystem", "OA", "eventId", "EVENT-" + b)));
        historicalAudit(
                "OA_DECISION",
                "C_OA_CHANGE_V1",
                List.of(source(903, "OA_APPROVAL", 1)),
                List.of(source(903, "OA_APPROVAL", 0)),
                Map.of(
                        "changed",
                        true,
                        "decision",
                        decision("REVOKE"),
                        "origin",
                        Map.of("sourceSystem", "OA", "eventId", "EVENT-" + b)));
        assertThat(count("sys_user_role_source")).isZero();
        var result = history("userId", sid(20));
        assertThat(result.get("total").asInt()).isEqualTo(2);
        assertThat(result.at("/items/0/operation").asText()).isEqualTo("REVOKE");
        assertThat(result.at("/items/1/operation").asText()).isEqualTo("GRANT");
        assertThat(result.at("/items/1/occurredAt").asText())
                .isEqualTo("2026-10-01T10:00:00+08:00");
    }

    void manualAudit(long source, int role) {
        var item = source(source, "MANUAL", 1);
        item.put("roleId", id(role));
        historicalAudit(
                "USER_ROLE_GRANT",
                "C_CHANGE_V1",
                List.of(),
                List.of(item),
                Map.of("sourceId", source, "roleId", id(role)));
    }

    @Test
    void filtersUseSealedSourceTimeAndRoleBeforePageSelection() throws Exception {
        manualAudit(901, 30);
        manualAudit(902, 31);
        manualAudit(903, 30);
        var page =
                history(
                        "userId",
                        sid(20),
                        "roleId",
                        sid(30),
                        "page",
                        "2",
                        "pageSize",
                        "1",
                        "from",
                        "2026-10-01T02:00:00Z",
                        "to",
                        "2026-10-01T10:00:00+08:00");
        assertThat(page.get("total").asInt()).isEqualTo(2);
        assertThat(page.at("/items/0/grantSourceId").asText()).isEqualTo("901");
        assertThat(
                        history("userId", sid(20), "from", "2026-10-01T10:00:01+08:00")
                                .get("total")
                                .asInt())
                .isZero();
        assertThat(history("userId", sid(20), "roleId", sid(999)).get("total").asInt()).isZero();
        assertThat(history("userId", sid(999)).get("items")).isEmpty();
        assertThat(history("userId", sid(20), "page", "1000000", "pageSize", "500").get("items"))
                .isEmpty();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "userId=0",
                "userId=01",
                "userId=-1",
                "userId=9223372036854775808",
                "roleId=bad",
                "roleId=",
                "page=0",
                "page=1000001",
                "page=2147483648",
                "pageSize=0",
                "pageSize=501",
                "from=2026-10-01",
                "from=2026-10-01T10:00:00",
                "from=2026-02-30T10:00:00Z",
                "to=bad"
            })
    void invalidQueryNeverReturnsHistory(String input) throws Exception {
        String[] pair = input.split("=", -1);
        query(pair)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void reversedTimeRangeIsRejected() throws Exception {
        query("from", "2026-10-02T10:00:00Z", "to", "2026-10-01T10:00:00Z")
                .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "malformed",
                "expired",
                "old",
                "logout",
                "appointment",
                "function",
                "identity"
            })
    void currentReadQualificationIsRequiredOnEveryRequest(String kind) throws Exception {
        grant(20, "MANUAL", "READ-AUTH");
        assertThat(history("userId", sid(20)).get("total").asInt()).isEqualTo(1);
        switch (kind) {
            case "malformed" -> bearer = "Bearer bad";
            case "expired" ->
                    bearer =
                            "Bearer "
                                    + new JwtTokenService(
                                                    encoded(RSA.getPrivate().getEncoded()),
                                                    encoded(RSA.getPublic().getEncoded()),
                                                    "c-stage2",
                                                    "iam",
                                                    "test-jwt",
                                                    Clock.fixed(
                                                            Instant.now().minusSeconds(7200),
                                                            ZoneOffset.UTC))
                                            .issue(id(10), 1);
            case "old" -> bearer = "Bearer " + jwt.issue(id(10), 2);
            case "logout" ->
                    db.update(
                            "INSERT INTO sys_session_invalidation(user_id,jti,expires_at,reason)"
                                    + " VALUES(?,?,DATE_ADD(NOW(),INTERVAL 1 HOUR),'LOGOUT')",
                            id(10),
                            jwt.verifyBearer(bearer).jti());
            case "appointment" ->
                    tx.executeWithoutResult(
                            s -> {
                                db.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id(10));
                                signatures.sign(ADMIN, id(10));
                            });
            case "function" ->
                    tx.executeWithoutResult(
                            s -> {
                                db.update("UPDATE sys_menu SET status=0 WHERE id=?", id(51));
                                signatures.sign(RESOURCE_MENU, id(51));
                            });
            case "identity" ->
                    db.update(
                            "UPDATE sys_user SET grant_revision=grant_revision+1 WHERE id=?",
                            id(10));
        }
        query("userId", sid(20))
                .andExpect(
                        status().is(
                                        kind.equals("identity")
                                                ? 503
                                                : Set.of("appointment", "function").contains(kind)
                                                        ? 403
                                                        : 401));
    }

    @Test
    void missingBearerIsRejected() throws Exception {
        http.perform(get("/api/v1/grants/history")).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"seal", "actor", "target", "sourceSystem", "externalEvent", "status"})
    void alteredBodyOrMetadataIsNeverVerifiedHistory(String kind) throws Exception {
        grant(20, "MANUAL", "TAMPER");
        String evidence = "evidence",
                actor = "user_id",
                target = "target_id",
                system = "source_system",
                event = "external_event_id",
                state = "status";
        switch (kind) {
            case "seal" -> evidence = "JSON_SET(evidence,'$.reason','altered')";
            case "actor" -> actor = Long.toString(id(11));
            case "target" -> target = Long.toString(id(11));
            case "sourceSystem" -> system = "'ALTERED'";
            case "externalEvent" -> event = "'ALTERED'";
            case "status" -> state = "0";
        }
        db.update(
                "INSERT INTO"
                    + " sys_operation_log(user_id,request_id,source_system,external_event_id,operation_type,target_type,target_id,status,evidence)"
                    + " SELECT "
                        + actor
                        + ",request_id,"
                        + system
                        + ","
                        + event
                        + ",operation_type,target_type,"
                        + target
                        + ","
                        + state
                        + ","
                        + evidence
                        + " FROM sys_operation_log WHERE target_id=? AND"
                        + " operation_type='USER_ROLE_GRANT' LIMIT 1",
                id(20));
        query("userId", sid(kind.equals("target") ? 11 : 20))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTEGRITY_FAILURE"));
    }

    @Test
    void invisibleMalformedAuditIsExcludedBeforeEvidenceParsingAndCount() throws Exception {
        grant(20, "MANUAL", "VISIBLE");
        db.update(
                "INSERT INTO"
                    + " sys_operation_log(user_id,request_id,operation_type,target_type,target_id,status,evidence)"
                    + " VALUES(?,'outside','USER_ROLE_GRANT','USER',?,1,'{}')",
                id(10),
                id(10));
        tx.executeWithoutResult(
                s -> {
                    db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        bearer = "Bearer " + jwt.issue(id(11), 1);
        bind(bearer);
        assertThat(history("pageSize", "1").get("total").asInt()).isEqualTo(1);
    }

    @Test
    void badVisibleEvidenceOutsideRequestedPageStillCannotProduceATrustedTotal() throws Exception {
        db.update(
                "INSERT INTO"
                    + " sys_operation_log(user_id,request_id,operation_type,target_type,target_id,status,evidence)"
                    + " VALUES(?,'bad-old','USER_ROLE_GRANT','USER',?,1,'{}')",
                id(10),
                id(20));
        manualAudit(901, 30);
        query("userId", sid(20), "pageSize", "1").andExpect(status().isServiceUnavailable());
    }

    @Test
    void moreThanOneCandidateChunkUsesOneScopeSnapshotAndTheNextRequestSeesMovement()
            throws Exception {
        for (int n = 0; n < 201; n++) manualAudit(1000 + n, 30);
        tx.executeWithoutResult(
                s -> {
                    db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        bearer = "Bearer " + jwt.issue(id(11), 1);
        bind(bearer);
        failure.afterHistoryPage =
                () -> {
                    var write = new TransactionTemplate(manager);
                    write.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_REQUIRES_NEW);
                    write.executeWithoutResult(
                            s -> {
                                db.update(
                                        "UPDATE sys_user SET dept_id=? WHERE id=?", id(1), id(20));
                                signatures.sign(USER_IDENTITY, id(20));
                            });
                };
        var result = history("userId", sid(20), "page", "201", "pageSize", "1");
        assertThat(result.get("total").asInt()).isEqualTo(201);
        assertThat(result.at("/items/0/grantSourceId").asText()).isEqualTo("1000");
        assertThat(history("userId", sid(20)).get("total").asInt()).isZero();
    }

    @Test
    void anOuterReadSnapshotCannotLendOldScopeToGr04() {
        grant(20, "MANUAL", "NEW-SNAPSHOT");
        tx.executeWithoutResult(
                s -> {
                    db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        bearer = "Bearer " + jwt.issue(id(11), 1);
        bind(bearer);
        tx.setReadOnly(true);
        tx.executeWithoutResult(
                s -> {
                    assertThat(
                                    db.queryForObject(
                                            "SELECT dept_id FROM sys_user WHERE id=?",
                                            Long.class,
                                            id(20)))
                            .isEqualTo(id(2));
                    var write = new TransactionTemplate(manager);
                    write.setPropagationBehavior(
                            org.springframework.transaction.TransactionDefinition
                                    .PROPAGATION_REQUIRES_NEW);
                    write.executeWithoutResult(
                            w -> {
                                db.update(
                                        "UPDATE sys_user SET dept_id=? WHERE id=?", id(1), id(20));
                                signatures.sign(USER_IDENTITY, id(20));
                            });
                    assertThat(historyService.history(sid(20), null, null, null, 1, 20).total())
                            .isZero();
                });
    }



    @ParameterizedTest
    @ValueSource(
            strings = {
                "schema",
                "sourceId",
                "userId",
                "revision",
                "roles",
                "reactivation",
                "deleted"
            })
    void evenAValidSealCannotTurnUnsupportedOrContradictorySnapshotsIntoHistory(String kind)
            throws Exception {
        var retained = new LinkedHashMap<String, Object>();
        retained.put("schema", kind.equals("schema") ? "LEGACY_V0" : "C_CHANGE_V1");
        retained.put("roleId", id(30));
        retained.put("sourceId", kind.equals("sourceId") ? 999 : 901);
        var before = new LinkedHashMap<>(snapshot(1, List.of()));
        var after =
                new LinkedHashMap<>(
                        snapshot(
                                kind.equals("revision") ? 1 : 2,
                                List.of(source(901, "MANUAL", 1))));
        switch (kind) {
            case "userId" -> after.put("userId", id(11));
            case "roles" -> after.put("roles", List.of());
            case "reactivation" -> before.put("sources", List.of(source(901, "MANUAL", 0)));
            case "deleted" -> {
                before = snapshotCopy(1, List.of(source(901, "MANUAL", 1)));
                after = snapshotCopy(2, List.of());
            }
        }
        retained.put("before", before);
        retained.put("after", after);
        tx.executeWithoutResult(
                s ->
                        auditService.change(
                                "USER_ROLE_GRANT", id(10), "USER", id(20), null, retained));
        query("userId", sid(20))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTEGRITY_FAILURE"));
    }

    LinkedHashMap<String, Object> snapshotCopy(long revision, List<Map<String, Object>> sources) {
        return new LinkedHashMap<>(snapshot(revision, sources));
    }

    @Test
    void gr04AndDHistoryEvidenceShareTheRetainedSealVerifier() throws Exception {
        String source = grant(20, "MANUAL", "D-EVIDENCE");
        revoke(20, source);
        String event = history("userId", sid(20)).at("/items/1/eventId").asText();
        assertThatThrownBy(() -> auditQueries.evidence(bearer, event))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(PERMISSION_DENIED));
        db.update(
                "INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type)"
                        + " VALUES(?,?,0,'IAM_AUDIT_VIEW','Fixture',2)",
                id(57),
                id(40));
        db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)", id(31), id(57));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(RESOURCE_APPLICATION, id(40));
                    signatures.sign(RESOURCE_MENU, id(57));
                    signatures.sign(MENU_APIS, id(57));
                    signatures.sign(ROLE_MENUS, id(31));
                });
        var proof = auditQueries.evidence(bearer, event);
        assertThat(proof.get("integrityVerified")).isEqualTo(true);
        assertThat(((Map<?, ?>) proof.get("evidence")).get("reason"))
                .isEqualTo("historical reason");
    }

    @Test
    void untrustedCurrentTargetMembershipCannotCreateVisibility() throws Exception {
        grant(20, "MANUAL", "CURRENT-SCOPE");
        bearer = "Bearer " + jwt.issue(id(11), 1);
        bind(bearer);
        assertThat(history("userId", sid(20)).get("total").asInt()).isZero();
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
        query("userId", sid(20))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("INTEGRITY_FAILURE"));
        tx.executeWithoutResult(s -> signatures.sign(USER_IDENTITY, id(20)));
    }
}
