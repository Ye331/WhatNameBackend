package com.whatname.iam.service.oa;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.event.TrustedEventContext;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;

import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Actual OA HTTP, A events, C scopes/grants, D data signatures/audit and MySQL. Only D's
 * not-yet-delivered request authentication is replaced in this context.
 */
@SpringBootTest(
        classes = {com.whatname.iam.IamApplication.class, OaDecisionIT.Configuration.class},
        properties = "spring.config.import=")
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
class OaDecisionIT {
    @TestConfiguration
    static class Configuration {
        @Bean
        @Primary
        Caller oaFixtureCaller() {
            return new Caller();
        }

        @Bean
        Fault oaFault() {
            return new Fault();
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
                            "/docker-entrypoint-initdb.d/01.sql");

    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519");

    static KeyPair keys(String algorithm) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("RSA")) generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException(error);
        }
    }

    static String base64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> base64(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> base64(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "oa-history-test");
        r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "oa-jwt-test");
        r.add("iam.security.data-signing.key-id", () -> "oa-test");
        r.add("iam.security.data-signing.private-key", () -> base64(ED.getPrivate().getEncoded()));
        r.add(
                "iam.security.data-signing.public-keys.oa-test",
                () -> base64(ED.getPublic().getEncoded()));
    }

    static class Caller extends ServiceOaCaller {
        final ThreadLocal<TrustedEventContext> issued = new ThreadLocal<>();
        volatile String client;

        Caller() {
            super(null);
        }

        public TrustedEventContext authenticate(
                jakarta.servlet.http.HttpServletRequest request, byte[] body) {
            if (!"fixture-only".equals(request.getHeader("X-Test-Oa"))
                    || !client.equals(request.getHeader("X-Client-Id"))
                    || !"POST".equals(request.getMethod())
                    || !"/api/v1/integrations/oa/decisions".equals(request.getRequestURI()))
                throw new BusinessException(INVALID_SERVICE);
            var context = new TrustedEventContext("OA", client, UUID.randomUUID().toString());
            issued.set(context);
            return context;
        }

        public void verifyTrustedEvent(TrustedEventContext context, String type) {
            if (context == null
                    || context != issued.get()
                    || !"OA_DECISION".equals(type)
                    || !"OA".equals(context.sourceSystem()))
                throw new BusinessException(INVALID_SERVICE);
        }
    }

    @org.aspectj.lang.annotation.Aspect
    static class Fault extends com.whatname.iam.support.RepositoryProbe {
        volatile String point, hit;
        volatile CountDownLatch ready, release;

        public Object intercept(Invocation call) throws Throwable {
            var statement = call;
            String id = statement.id();
            Object result = call.proceed();
            if (id.endsWith("ExternalEventRepository.applied") && ready != null) {
                ready.countDown();
                if (!release.await(15, TimeUnit.SECONDS))
                    throw new AssertionError("fixture release timeout");
            }
            if (point != null
                    && ((id.endsWith("SignatureRepository.save")
                                    && point.equals(((Map<?, ?>) call.parameters()).get("type")))
                            || (id.endsWith("AuditRepository.insert")
                                    && point.equals(
                                            ((Map<?, ?>) call.parameters()).get("operation")))
                            || id.endsWith("." + point))) {
                hit = point;
                throw new IllegalStateException("fixture persistence failure");
            }
            return result;
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired DataSignatureService signatures;
    @Autowired ObjectMapper json;
    @Autowired MockMvc http;
    @Autowired PlatformTransactionManager manager;
    @Autowired Caller caller;
    @Autowired com.whatname.iam.service.grant.GrantHistoryService history;
    @Autowired com.whatname.iam.common.security.JwtTokenService jwt;
    @Autowired PermissionService permissions;
    @Autowired Fault fault;
    @Autowired OaDecisionService service;
    static final AtomicLong NEXT = new AtomicLong(1000);
    long b;
    TransactionTemplate tx;

    long id(int n) {
        return b + n;
    }

    String sid(int n) {
        return Long.toString(id(n));
    }

    String event() {
        return "OA-" + b;
    }

    String approval() {
        return "APP-" + b;
    }

    @BeforeEach
    void seed() {
        b = NEXT.getAndAdd(1000);
        caller.client = "OA" + b;
        caller.issued.remove();
        fault.point = null;
        fault.hit = null;
        fault.ready = null;
        fault.release = null;
        tx = new TransactionTemplate(manager);
        db.update(
                "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                        + " VALUES(?,0,?,'Fixture',?),(?,?,?,'Fixture',?),(?,0,?,'Fixture',?)",
                id(1),
                "D" + id(1),
                "/" + id(1) + "/",
                id(2),
                id(1),
                "D" + id(2),
                "/" + id(1) + "/" + id(2) + "/",
                id(3),
                "D" + id(3),
                "/" + id(3) + "/");
        for (int u : List.of(10, 20))
            db.update(
                    "INSERT INTO"
                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                        + " VALUES(?,?,'fixture',?,'fixture',?,3,?,1)",
                    id(u),
                    "U" + id(u),
                    String.format("138%08d", id(u)),
                    id(2),
                    u == 10 ? 3 : 1);
        db.update(
                "INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,1,?,0)",
                id(10),
                id(1));
        for (int r : List.of(30, 31, 32))
            db.update(
                    "INSERT INTO sys_role(id,role_code,role_name,role_type)"
                            + " VALUES(?,?,'Fixture',?)",
                    id(r),
                    "R" + id(r),
                    r == 30 ? 1 : r == 31 ? 2 : 3);
        db.update(
                "INSERT INTO"
                    + " sys_service_identity(id,client_id,credential_hash,public_key,key_id,allowed_role_ids,allowed_dept_ids,allowed_operations,allowed_scope)"
                    + " VALUES(?,?,?,?,'fixture-key',?,?,?,'OA_DECISION')",
                id(40),
                caller.client,
                "fixture-fingerprint",
                base64(ED.getPublic().getEncoded()),
                jsonArray(sid(30), sid(31), sid(32)),
                jsonArray(sid(1)),
                jsonArray("GRANT", "REVOKE"));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(ORGANIZATION, 0);
                    for (int d : List.of(1, 2, 3)) signatures.sign(DEPARTMENT, id(d));
                    for (int u : List.of(10, 20)) {
                        signatures.sign(USER_IDENTITY, id(u));
                        signatures.sign(USER_GRANTS, id(u));
                    }
                    for (int r : List.of(30, 31, 32)) signatures.sign(ROLE_MENUS, id(r));
                    signatures.sign(ADMIN, id(10));
                    signatures.sign(ADMIN_DELEGABLE_ROLES, id(10));
                    signatures.sign(SERVICE_IDENTITY, id(40));
                });
    }

    @AfterEach
    void clear() {
        caller.issued.remove();
        fault.point = null;
        if (fault.release != null) fault.release.countDown();
    }

    String jsonArray(String... values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    Map<String, Object> grant() {
        var result = new LinkedHashMap<String, Object>();
        result.put("sourceSystem", "OA");
        result.put("eventId", event());
        result.put("approvalId", approval());
        result.put("approvalStatus", "APPROVED");
        result.put("operation", "GRANT");
        result.put("targetUserId", sid(20));
        result.put("roleId", sid(30));
        result.put("approvedAt", "2026-10-19T10:00:00+08:00");
        return result;
    }

    Map<String, Object> revoke(String source) {
        var request = grant();
        request.put("eventId", event() + "-revoke");
        request.put("approvalId", "REVOKE-" + b);
        request.put("operation", "REVOKE");
        request.put("grantSourceId", source);
        return request;
    }

    com.fasterxml.jackson.databind.JsonNode send(Map<String, Object> request, int expected)
            throws Exception {
        return sendRaw(json.writeValueAsString(request), expected);
    }

    com.fasterxml.jackson.databind.JsonNode sendRaw(String request, int expected) throws Exception {
        var response =
                http.perform(
                                post("/api/v1/integrations/oa/decisions")
                                        .contentType("application/json")
                                        .header("X-Test-Oa", "fixture-only")
                                        .header("X-Client-Id", caller.client)
                                        .content(request))
                        .andExpect(status().is(expected))
                        .andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    long count(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    long revision() {
        return count("SELECT grant_revision FROM sys_user WHERE id=?", id(20));
    }

    long logs() {
        return count(
                "SELECT COUNT(*) FROM sys_operation_log WHERE target_id=? AND"
                        + " operation_type='OA_DECISION'",
                id(20));
    }

    String eventState(String event) {
        return db.queryForObject(
                "SELECT processing_status FROM sys_external_event WHERE source_system='OA' AND"
                        + " event_id=?",
                String.class,
                event);
    }

    void verifiable() {
        tx.executeWithoutResult(
                s -> {
                    signatures.verify(USER_IDENTITY, id(20));
                    signatures.verify(USER_GRANTS, id(20));
                });
    }

    void independent(String type, String ref) {
        db.update(
                "INSERT INTO"
                    + " sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id,granted_by)"
                    + " VALUES(?,?,?,?,?,?)",
                id(20),
                id(30),
                type,
                ref,
                type.equals("DEPT_POSITION") ? id(2) : null,
                id(10));
        if (count(
                        "SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",
                        id(20),
                        id(30))
                == 0)
            db.update(
                    "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                    id(20),
                    id(30),
                    id(10));
        tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
    }

    Map<String, Object> facts() {
        return Map.of(
                "person",
                db.queryForList(
                        "SELECT dept_id,status,grant_revision,session_version FROM sys_user WHERE"
                                + " id=?",
                        id(20)),
                "sources",
                db.queryForList(
                        "SELECT * FROM sys_user_role_source WHERE user_id=? ORDER BY id", id(20)),
                "roles",
                db.queryForList(
                        "SELECT * FROM sys_user_role WHERE user_id=? ORDER BY role_id", id(20)),
                "signatures",
                db.queryForList(
                        "SELECT data_type,signature FROM sys_data_signature WHERE data_id=? ORDER"
                                + " BY data_type",
                        id(20)));
    }

    @Test
    void gr04ReadsActualOaGrantAndRevokeButSkipsReplayAndChangedFalseAudits() throws Exception {
        var request = grant();
        String source = send(request, 200).at("/data/grantSourceId").asText();
        send(request, 200);
        request.put("eventId", event() + "-same-approval");
        send(request, 200);
        send(revoke(source), 200);
        long reader =
                com.whatname.iam.service.grant.GrantHistoryQueries.bootstrap(
                        db, signatures, manager, permissions, b, id(1));
        var result =
                com.whatname.iam.service.grant.GrantHistoryQueries.read(
                        history, jwt, reader, id(20), null, 1, 20);
        assertThat(result.total()).isEqualTo(2);
        assertThat(result.items())
                .extracting(com.whatname.iam.dto.grant.GrantDtos.HistoryItem::operation)
                .containsExactly("REVOKE", "GRANT");
        assertThat(result.items())
                .extracting(com.whatname.iam.dto.grant.GrantDtos.HistoryItem::grantSourceId)
                .containsOnly(source);
        assertThat(result.items().getFirst().eventId())
                .isNotEqualTo(result.items().getLast().eventId());
    }

    @Test
    void grantUsesServiceScopeDespiteFormerApplicantsDepartureAndProducesSignedFacts()
            throws Exception {
        var result = send(grant(), 200).path("data");
        assertThat(result.path("eventId").asText()).isEqualTo(event());
        assertThat(result.path("processingStatus").asText()).isEqualTo("APPLIED");
        assertThat(result.path("idempotentReplay").asBoolean()).isFalse();
        assertThat(result.path("grantSourceId").asText()).matches("[1-9][0-9]*");
        assertThat(
                        db.queryForMap(
                                "SELECT source_type,source_ref,status FROM sys_user_role_source"
                                        + " WHERE user_id=?",
                                id(20)))
                .containsEntry("source_type", "OA_APPROVAL")
                .containsEntry("source_ref", approval())
                .containsEntry("status", 1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " granted_by IS NULL AND source_dept_id IS NULL",
                                id(20)))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND granted_by"
                                        + " IS NULL",
                                id(20)))
                .isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(logs()).isEqualTo(1);
        assertThat(eventState(event())).isEqualTo("APPLIED");
        assertThat(
                        db.queryForObject(
                                "SELECT event_version FROM sys_external_event WHERE event_id=?",
                                Long.class,
                                event()))
                .isNull();
        verifiable();
    }

    @Test
    void sameEventAndSameApprovalDifferentEventsNeverCreateAnotherSourceOrRevision()
            throws Exception {
        var request = grant();
        var first = send(request, 200).path("data");
        var replay = send(request, 200).path("data");
        assertThat(replay.path("idempotentReplay").asBoolean()).isTrue();
        assertThat(replay.path("grantSourceId")).isEqualTo(first.path("grantSourceId"));
        assertThat(logs()).isEqualTo(1);
        request.put("eventId", event() + "-same-approval");
        var duplicate = send(request, 200).path("data");
        assertThat(duplicate.path("grantSourceId")).isEqualTo(first.path("grantSourceId"));
        assertThat(count("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(logs()).isEqualTo(2);
        verifiable();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void exactRevokePreservesIndependentSourcesThenLastOaRevokeSignsEmptySet(boolean byApproval)
            throws Exception {
        independent("MANUAL", "manual");
        independent("DEPT_POSITION", "position");
        String source = send(grant(), 200).at("/data/grantSourceId").asText();
        var request = revoke(source);
        if (byApproval) {
            request.remove("grantSourceId");
            request.put("originalApprovalId", approval());
        }
        send(request, 200);
        assertThat(
                        db.queryForList(
                                "SELECT source_type FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1 ORDER BY source_type",
                                String.class,
                                id(20)))
                .containsExactly("DEPT_POSITION", "MANUAL");
        assertThat(count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id(20)))
                .isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                    + " status=0 AND revoked_by IS NULL AND revoked_at IS NOT NULL",
                                id(20)))
                .isEqualTo(1);
        assertThat(revision()).isEqualTo(3);
        verifiable();
    }

    @Test
    void lastOaSourceRevokeHasVerifiableEmptySetAndNeverResurrectsOldApproval() throws Exception {
        String source = send(grant(), 200).at("/data/grantSourceId").asText();
        send(revoke(source), 200);
        assertThat(count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id(20))).isZero();
        verifiable();
        var again = revoke(source);
        again.put("eventId", event() + "-revoke-again");
        send(again, 200);
        assertThat(revision()).isEqualTo(3);
        var restore = grant();
        restore.put("eventId", event() + "-restore");
        assertThat(send(restore, 409).path("code").asText()).isEqualTo("DUPLICATE_RESOURCE");
        assertThat(revision()).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id(20))).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "role",
                "operation",
                "department",
                "super",
                "disabled-role",
                "suspended-user",
                "terminated-user",
                "frozen",
                "merged-root",
                "service-off"
            })
    void currentScopeAndEligibilityRefuseUnauthorizedChanges(String kind) throws Exception {
        var request = grant();
        switch (kind) {
            case "role" ->
                    db.update(
                            "UPDATE sys_service_identity SET allowed_role_ids='[]' WHERE id=?",
                            id(40));
            case "operation" ->
                    db.update(
                            "UPDATE sys_service_identity SET allowed_operations='[\"REVOKE\"]'"
                                    + " WHERE id=?",
                            id(40));
            case "department" ->
                    db.update(
                            "UPDATE sys_service_identity SET allowed_dept_ids=? WHERE id=?",
                            jsonArray(sid(3)),
                            id(40));
            case "super" -> request.put("roleId", sid(32));
            case "disabled-role" -> db.update("UPDATE sys_role SET status=0 WHERE id=?", id(30));
            case "suspended-user" -> db.update("UPDATE sys_user SET status=2 WHERE id=?", id(20));
            case "terminated-user" -> db.update("UPDATE sys_user SET status=3 WHERE id=?", id(20));
            case "frozen" -> db.update("UPDATE sys_dept SET status=0 WHERE id=?", id(1));
            case "merged-root" -> {
                db.update("UPDATE sys_dept SET merged_into_dept_id=? WHERE id=?", id(3), id(2));
                db.update(
                        "UPDATE sys_service_identity SET allowed_dept_ids=? WHERE id=?",
                        jsonArray(sid(2)),
                        id(40));
            }
            case "service-off" ->
                    db.update("UPDATE sys_service_identity SET status=0 WHERE id=?", id(40));
        }
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(SERVICE_IDENTITY, id(40));
                    signatures.sign(ROLE_MENUS, id(30));
                    signatures.sign(USER_IDENTITY, id(20));
                    signatures.sign(DEPARTMENT, id(1));
                    signatures.sign(DEPARTMENT, id(2));
                    signatures.sign(ORGANIZATION, 0);
                });
        int expected = kind.equals("service-off") ? 401 : 403;
        send(request, expected);
        assertThat(revision()).isEqualTo(1);
        assertThat(logs()).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event())).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "insertOaSource",
                "insertOaEffectiveRole",
                "USER_IDENTITY",
                "USER_GRANTS",
                "OA_DECISION",
                "applied"
            })
    void failuresAfterPersistenceRollBackFactsSignaturesAuditAndApplied(String point)
            throws Exception {
        var before = facts();
        fault.point = point;
        send(grant(), 503);
        assertThat(fault.hit).isEqualTo(point);
        assertThat(facts()).isEqualTo(before);
        assertThat(logs()).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event())).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "numeric-id",
                "overflow",
                "leading-zero",
                "missing-time",
                "local-time",
                "not-approved",
                "lowercase-operation",
                "wrong-source",
                "blank-approval",
                "grant-with-revoke-ref",
                "revoke-without-ref",
                "revoke-with-both-refs",
                "unknown",
                "trailing-json",
                "duplicate-field",
                "numeric-time",
                "null-revoke-ref",
                "both-refs-one-null"
            })
    void strictContractRejectsInvalidOrAmbiguousApprovalContentBeforeReceipt(String kind)
            throws Exception {
        var request = grant();
        switch (kind) {
            case "overflow" -> request.put("roleId", "9223372036854775808");
            case "leading-zero" -> request.put("roleId", "030");
            case "missing-time" -> request.remove("approvedAt");
            case "local-time" -> request.put("approvedAt", "2026-10-19T10:00:00");
            case "not-approved" -> request.put("approvalStatus", "REJECTED");
            case "lowercase-operation" -> request.put("operation", "grant");
            case "wrong-source" -> request.put("sourceSystem", "HR");
            case "blank-approval" -> request.put("approvalId", " ");
            case "grant-with-revoke-ref" -> request.put("originalApprovalId", "APP-other");
            case "revoke-without-ref" -> request.put("operation", "REVOKE");
            case "revoke-with-both-refs" -> {
                request.put("operation", "REVOKE");
                request.put("grantSourceId", "1");
                request.put("originalApprovalId", "APP-old");
            }
            case "unknown" -> request.put("applicantUserId", sid(10));
            case "null-revoke-ref" -> request.put("grantSourceId", null);
            case "both-refs-one-null" -> {
                request.put("operation", "REVOKE");
                request.put("grantSourceId", "1");
                request.put("originalApprovalId", null);
            }
        }
        String raw = json.writeValueAsString(request);
        if (kind.equals("numeric-id"))
            raw =
                    raw.replace(
                            "\"targetUserId\":\"" + sid(20) + "\"", "\"targetUserId\":" + sid(20));
        if (kind.equals("numeric-time"))
            raw =
                    raw.replace(
                            "\"approvedAt\":\"2026-10-19T10:00:00+08:00\"",
                            "\"approvedAt\":1760840000");
        if (kind.equals("trailing-json")) raw += " {}";
        if (kind.equals("duplicate-field"))
            raw = raw.substring(0, raw.length() - 1) + ",\"approvalStatus\":\"APPROVED\"}";
        assertThat(sendRaw(raw, 400).path("code").asText()).isEqualTo("INVALID_ARGUMENT");
        assertThat(count("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event()))
                .isZero();
        assertThat(revision()).isEqualTo(1);
        assertThat(logs()).isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "service-missing",
                "service-tamper",
                "grants-missing",
                "identity-missing",
                "inconsistent",
                "bad-config"
            })
    void integrityFailureNeverGetsRepairedByOaWrite(String kind) throws Exception {
        switch (kind) {
            case "service-missing" ->
                    db.update(
                            "DELETE FROM sys_data_signature WHERE data_type='SERVICE_IDENTITY' AND"
                                    + " data_id=?",
                            id(40));
            case "service-tamper" ->
                    db.update(
                            "UPDATE sys_service_identity SET allowed_role_ids='[]' WHERE id=?",
                            id(40));
            case "grants-missing" ->
                    db.update(
                            "DELETE FROM sys_data_signature WHERE data_type='USER_GRANTS' AND"
                                    + " data_id=?",
                            id(20));
            case "identity-missing" ->
                    db.update(
                            "DELETE FROM sys_data_signature WHERE data_type='USER_IDENTITY' AND"
                                    + " data_id=?",
                            id(20));
            case "inconsistent" -> {
                db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id(20), id(30));
                tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
            }
            case "bad-config" -> {
                db.update(
                        "UPDATE sys_service_identity SET allowed_role_ids='[123]' WHERE id=?",
                        id(40));
                tx.executeWithoutResult(s -> signatures.sign(SERVICE_IDENTITY, id(40)));
            }
        }
        var before = facts();
        assertThat(send(grant(), 503).path("code").asText()).isEqualTo("INTEGRITY_FAILURE");
        assertThat(facts()).isEqualTo(before);
        assertThat(logs()).isZero();
        assertThat(count("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event())).isZero();
        if (kind.equals("service-missing"))
            assertThat(
                            count(
                                    "SELECT COUNT(*) FROM sys_data_signature WHERE"
                                            + " data_type='SERVICE_IDENTITY' AND data_id=?",
                                    id(40)))
                    .isZero();
    }

    @Test
    void wrongSourceTypeAndWrongRoleCannotBeRevokedBySourceId() throws Exception {
        independent("MANUAL", "manual");
        String manual =
                db.queryForObject(
                        "SELECT id FROM sys_user_role_source WHERE user_id=?",
                        String.class,
                        id(20));
        assertThat(send(revoke(manual), 404).path("code").asText())
                .isEqualTo("GRANT_SOURCE_NOT_FOUND");
        String oa = send(grant(), 200).at("/data/grantSourceId").asText();
        var wrong = revoke(oa);
        wrong.put("eventId", event() + "-wrong-role");
        wrong.put("roleId", sid(31));
        assertThat(send(wrong, 404).path("code").asText()).isEqualTo("GRANT_SOURCE_NOT_FOUND");
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isEqualTo(2);
        verifiable();
    }

    @Test
    void originalApprovalLookupNeverFallsBackToCurrentRevokeApproval() throws Exception {
        send(grant(), 200);
        var request = revoke("1");
        request.remove("grantSourceId");
        request.put("approvalId", approval());
        request.put("originalApprovalId", "missing-old-approval");
        assertThat(send(request, 404).path("code").asText()).isEqualTo("GRANT_SOURCE_NOT_FOUND");
        assertThat(revision()).isEqualTo(2);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isEqualTo(1);
    }

    @Test
    void successfulEventsConflictButRolledBackEventsCanBeRetriedAfterCorrection() throws Exception {
        var request = grant();
        send(request, 200);
        request.put("roleId", sid(31));
        assertThat(send(request, 409).path("code").asText()).isEqualTo("IDEMPOTENCY_CONFLICT");
        request.put("eventId", event() + "-failure");
        request.put("approvalId", approval() + "-failure");
        fault.point = "USER_GRANTS";
        send(request, 503);
        fault.point = null;
        send(request, 200);
        assertThat(eventState(event() + "-failure")).isEqualTo("APPLIED");
        var applied = facts();
        send(request, 200);
        assertThat(facts()).isEqualTo(applied);
        assertThat(logs()).isEqualTo(2);
    }

    @Test
    void replayCannotBypassCurrentServiceScope() throws Exception {
        var request = grant();
        send(request, 200);
        db.update("UPDATE sys_service_identity SET allowed_role_ids='[]' WHERE id=?", id(40));
        tx.executeWithoutResult(s -> signatures.sign(SERVICE_IDENTITY, id(40)));
        assertThat(send(request, 403).path("code").asText()).isEqualTo("OA_SCOPE_DENIED");
        assertThat(eventState(event())).isEqualTo("APPLIED");
        assertThat(revision()).isEqualTo(2);
        assertThat(logs()).isEqualTo(1);
    }

    @Test
    void constructedContextAndMissingRequestProofNeverReceiveEvents() {
        var request = json.convertValue(grant(), com.whatname.iam.dto.oa.OaDtos.Decision.class);
        assertThatThrownBy(
                        () ->
                                service.decide(
                                        new TrustedEventContext(
                                                "OA", caller.client, "invented-request"),
                                        request))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(INVALID_SERVICE));
        assertThat(count("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event()))
                .isZero();
    }

    void observeWait() {
        var root =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(
                        () ->
                                root.queryForObject(
                                                "SELECT COUNT(*) FROM"
                                                        + " performance_schema.data_lock_waits",
                                                Long.class)
                                        > 0);
    }

    @Test
    void simultaneousDifferentEventsForOneApprovalUseOneSource() throws Exception {
        fault.ready = new CountDownLatch(1);
        fault.release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> send(grant(), 200));
            try {
                assertThat(fault.ready.await(10, TimeUnit.SECONDS)).isTrue();
                var request = grant();
                request.put("eventId", event() + "-concurrent");
                var second = pool.submit(() -> send(request, 200));
                // The coursework service serializes events in this instance before taking DB locks.
                assertThat(second.isDone()).isFalse();
                fault.release.countDown();
                assertThat(second.get(15, TimeUnit.SECONDS).at("/data/grantSourceId"))
                        .isEqualTo(first.get(15, TimeUnit.SECONDS).at("/data/grantSourceId"));
            } finally {
                fault.release.countDown();
            }
        }
        assertThat(revision()).isEqualTo(2);
        assertThat(logs()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isEqualTo(1);
        verifiable();
    }

    @Test
    void auditRetainsApprovedContentAndExecutionEligibilityUnderDSeal() throws Exception {
        send(grant(), 200);
        String encoded =
                db.queryForObject(
                        "SELECT evidence FROM sys_operation_log WHERE target_id=? AND"
                                + " operation_type='OA_DECISION'",
                        String.class,
                        id(20));
        var tree = json.readTree(encoded);
        assertThat(tree.path("schema").asText()).isEqualTo("C_OA_CHANGE_V1");
        assertThat(tree.path("changed").asBoolean()).isTrue();
        assertThat(tree.at("/decision/approvalId").asText()).isEqualTo(approval());
        assertThat(tree.at("/origin/clientId").asText()).isEqualTo(caller.client);
        assertThat(tree.at("/serviceScope/target/deptId").asLong()).isEqualTo(id(2));
        assertThat(tree.at("/serviceScope/target/status").asInt()).isEqualTo(1);
        assertThat(tree.at("/serviceScope/role/roleType").asInt()).isEqualTo(1);
        assertThat(tree.at("/serviceScope/role/status").asInt()).isEqualTo(1);
        assertThat(
                        count(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE target_id=? AND"
                                        + " user_id IS NULL",
                                id(20)))
                .isEqualTo(1);
        assertThat(encoded)
                .doesNotContain(
                        "fixture-fingerprint", "password_hash", "ciphertext", "private-key");
        db.update("UPDATE sys_role SET status=0 WHERE id=?", id(30));
        db.update("UPDATE sys_user SET status=3 WHERE id=?", id(20));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(ROLE_MENUS, id(30));
                    signatures.sign(USER_IDENTITY, id(20));
                });
        assertThat(
                        db.queryForObject(
                                "SELECT evidence FROM sys_operation_log WHERE target_id=? AND"
                                        + " operation_type='OA_DECISION'",
                                String.class,
                                id(20)))
                .isEqualTo(encoded);
        var evidence =
                json.convertValue(
                        tree,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        var seal = (Map<String, Object>) evidence.remove("seal");
        tx.executeWithoutResult(s -> assertThat(signatures.verifySeal(evidence, seal)).isTrue());
    }

    @Test
    void configuredManagementRoleDoesNotRequireFormerApplicantsHumanAppointment() throws Exception {
        var request = grant();
        request.put("roleId", sid(31));
        send(request, 200);
        assertThat(revision()).isEqualTo(2);
        verifiable();
    }

    @Test
    void approvalLookupUsesTheSameDatabaseKeyComparisonAsItsUniqueConstraint() throws Exception {
        var first = send(grant(), 200).path("data");
        var request = grant();
        request.put("eventId", event() + "-case");
        request.put("approvalId", approval().toLowerCase(Locale.ROOT));
        assertThat(send(request, 200).at("/data/grantSourceId"))
                .isEqualTo(first.path("grantSourceId"));
        assertThat(revision()).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "revokeOaSource",
                "deleteEffectiveRole",
                "USER_IDENTITY",
                "USER_GRANTS",
                "OA_DECISION",
                "applied"
            })
    void revokePersistenceFailuresPreservePriorAppliedGrant(String point) throws Exception {
        String source = send(grant(), 200).at("/data/grantSourceId").asText();
        var before = facts();
        fault.point = point;
        send(revoke(source), 503);
        assertThat(fault.hit).isEqualTo(point);
        assertThat(facts()).isEqualTo(before);
        assertThat(logs()).isEqualTo(1);
        assertThat(eventState(event())).isEqualTo("APPLIED");
        assertThat(count("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event() + "-revoke")).isZero();
    }

    @Test
    void exhaustedRevisionNeverLeavesPartialApprovalSource() throws Exception {
        db.update("UPDATE sys_user SET grant_revision=9223372036854775807 WHERE id=?", id(20));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(USER_IDENTITY, id(20));
                    signatures.sign(USER_GRANTS, id(20));
                });
        var before = facts();
        assertThat(send(grant(), 503).path("code").asText()).isEqualTo("AUTH_STATE_UNAVAILABLE");
        assertThat(facts()).isEqualTo(before);
        assertThat(logs()).isZero();
    }

    @Test
    void approvedRevokeCanRemoveHistoricalSourceForInactiveTargetWithinCurrentScope()
            throws Exception {
        String source = send(grant(), 200).at("/data/grantSourceId").asText();
        db.update("UPDATE sys_user SET status=3 WHERE id=?", id(20));
        tx.executeWithoutResult(s -> signatures.sign(USER_IDENTITY, id(20)));
        send(revoke(source), 200);
        assertThat(count("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(3);
        assertThat(count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id(20))).isZero();
        assertThat(revision()).isEqualTo(3);
        verifiable();
    }

    @Test
    void waitingOaUsesCurrentRevokedServiceConfiguration() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var change =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                db.queryForList(
                                                        "SELECT user_id FROM sys_admin ORDER BY"
                                                                + " user_id FOR UPDATE");
                                                locked.countDown();
                                                try {
                                                    if (!release.await(15, TimeUnit.SECONDS))
                                                        throw new AssertionError("fixture timeout");
                                                } catch (InterruptedException e) {
                                                    throw new AssertionError(e);
                                                }
                                                db.update(
                                                        "UPDATE sys_service_identity SET"
                                                            + " allowed_role_ids='[]' WHERE id=?",
                                                        id(40));
                                                signatures.sign(SERVICE_IDENTITY, id(40));
                                            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var waiting = pool.submit(() -> send(grant(), 403));
                observeWait();
                release.countDown();
                change.get(15, TimeUnit.SECONDS);
                assertThat(waiting.get(15, TimeUnit.SECONDS).path("code").asText())
                        .isEqualTo("OA_SCOPE_DENIED");
            } finally {
                release.countDown();
            }
        }
        assertThat(revision()).isEqualTo(1);
        assertThat(logs()).isZero();
    }
}
