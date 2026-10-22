package com.whatname.iam.service.permission;

import static com.whatname.iam.service.permission.ServiceRequestSignature.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.event.TrustedEventContext;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end acceptance: the REAL production service-request authentication (ServiceOaCaller /
 * ServiceApiCaller / ServiceRequestAuthenticator) through the full HTTP chain into login
 * (JWT/session), admin scope and permission checks (RBAC0, data signatures, audit), against a real
 * MySQL. No request-authentication stub — every trusted-service call verifies an Ed25519 signature
 * over the canonical message and consumes a nonce. The only MyBatis interceptor injects persistence
 * failures to prove rollback/anti-replay behaviour.
 */
@SpringBootTest(
        classes = {com.whatname.iam.IamApplication.class, TrustedServiceE2EIT.TestConfig.class},
        properties = "spring.config.import=")
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
class TrustedServiceE2EIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean
        Fault persistenceFault() {
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
                            "/docker-entrypoint-initdb.d/01_schema.sql");

    // RSA for the JWT bearer, Ed25519 for data-fact signing (both test keys),
    // a separate Ed25519 pair for the trusted-service request signature under test.
    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519"), SVC = keys("Ed25519");

    static KeyPair keys(String algorithm) {
        try {
            var g = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("RSA")) g.initialize(2048);
            return g.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> b64(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> b64(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "e2e");
        r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "e2e-jwt");
        r.add("iam.security.data-signing.key-id", () -> "e2e-sign");
        r.add("iam.security.data-signing.private-key", () -> b64(ED.getPrivate().getEncoded()));
        r.add(
                "iam.security.data-signing.public-keys.e2e-sign",
                () -> b64(ED.getPublic().getEncoded()));
        r.add("iam.security.data-key", () -> b64(new byte[32]));
    }

    /**
     * Injects a persistence failure after a named MyBatis update statement, to prove that a later
     * business rollback cannot resurrect an already-consumed request nonce.
     */
    @Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class Fault implements Interceptor {
        volatile String point, hit;

        public Object intercept(Invocation call) throws Throwable {
            String id = ((MappedStatement) call.getArgs()[0]).getId();
            Object result = call.proceed();
            if (point != null && id.endsWith("." + point)) {
                hit = point;
                throw new IllegalStateException("fixture persistence failure");
            }
            return result;
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc http;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSignatureService signatures;
    @Autowired JwtTokenService jwt;
    @Autowired PermissionService permissions;
    @Autowired ServiceOaCaller oaCaller;
    @Autowired Fault fault;

    static final AtomicLong NEXT = new AtomicLong(1000);
    static final String OA_PATH = "/api/v1/integrations/oa/decisions";
    static final String API_PATH = "/api/v1/auth/check";
    static final String USERS_PATH = "/api/v1/users";

    long b;
    String oaClient, apiClient;
    String publicKeyB64, fingerprint;
    TransactionTemplate tx;

    long id(int n) {
        return b + n;
    }

    String sid(int n) {
        return Long.toString(id(n));
    }

    String event() {
        return "OA-E2E-" + b;
    }

    String approval() {
        return "APP-E2E-" + b;
    }

    @BeforeEach
    void seed() {
        b = NEXT.getAndAdd(1000);
        fault.point = null;
        fault.hit = null;
        tx = new TransactionTemplate(manager);
        oaClient = "oa-e2e-" + b;
        apiClient = "api-e2e-" + b;
        publicKeyB64 = b64(SVC.getPublic().getEncoded());
        fingerprint =
                ServiceRequestSignature.sha256Hex(publicKeyB64.getBytes(StandardCharsets.UTF_8));

        // Organization: root(1) with a leaf(2) and a sibling root(3).
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
        // Users: 10 = level-1 admin (root), 11 = level-2 admin (leaf), 20 = target (root).
        for (int u : List.of(10, 11, 20))
            db.update(
                    "INSERT INTO"
                        + " sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized)"
                        + " VALUES(?,?,'fixture','fixture',?,'fixture',?,3,1,1)",
                    id(u),
                    "U" + id(u),
                    String.format("%064x", id(u)),
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
                "INSERT INTO sys_application(id,app_code,app_name) VALUES(?,?,'Fixture')",
                id(40),
                "E2E" + b);
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
        // Real service identities: OA_DECISION (app-unbound) and API_AUTH (app-bound), one shared
        // Ed25519 key.
        db.update(
                "INSERT INTO"
                    + " sys_service_identity(id,client_id,credential_hash,public_key,key_id,allowed_role_ids,allowed_dept_ids,allowed_operations,allowed_scope,status)"
                    + " VALUES(?,?,?,?,?,?,?,?,'OA_DECISION',1)",
                id(70),
                oaClient,
                fingerprint,
                publicKeyB64,
                "oa-key",
                jsonArray(sid(30)),
                jsonArray(sid(1)),
                jsonArray("GRANT", "REVOKE"));
        db.update(
                "INSERT INTO"
                    + " sys_service_identity(id,app_id,client_id,credential_hash,public_key,key_id,allowed_scope,status)"
                    + " VALUES(?,?,?,?,?,?,'API_AUTH',1)",
                id(71),
                id(40),
                apiClient,
                fingerprint,
                publicKeyB64,
                "api-key");

        tx.executeWithoutResult(
                s -> {
                    signatures.sign(ORGANIZATION, 0);
                    for (long d : List.of(id(1), id(2), id(3))) signatures.sign(DEPARTMENT, d);
                    for (long a : List.of(id(10), id(11))) {
                        signatures.sign(ADMIN, a);
                        signatures.sign(ADMIN_DELEGABLE_ROLES, a);
                    }
                    for (long r : List.of(id(30), id(31))) signatures.sign(ROLE_MENUS, r);
                    for (long u : List.of(id(10), id(11), id(20))) {
                        signatures.sign(USER_IDENTITY, u);
                        signatures.sign(USER_GRANTS, u);
                    }
                    signatures.sign(RESOURCE_APPLICATION, id(40));
                    signatures.sign(RESOURCE_API, id(60));
                    for (int m = 50; m <= 56; m++) {
                        signatures.sign(RESOURCE_MENU, id(m));
                        signatures.sign(MENU_APIS, id(m));
                    }
                    signatures.sign(SERVICE_IDENTITY, id(70));
                    signatures.sign(SERVICE_IDENTITY, id(71));
                });
        // Resolve the RBAC0 management application to this fixture's app, per the integration
        // tests.
        ReflectionTestUtils.setField(
                (Object) AopTestUtils.getUltimateTargetObject(permissions),
                "managementApplication",
                "E2E" + b);
    }

    @AfterEach
    void clear() {
        fault.point = null;
        fault.hit = null;
    }

    String jsonArray(String... values) {
        try {
            return json.writeValueAsString(values);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- trusted-service request helpers -------------------------------------------------

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String signatureFor(
            String clientId,
            String keyId,
            long ts,
            String nonce,
            String method,
            String path,
            byte[] body) {
        String message =
                ServiceRequestSignature.message(
                        clientId,
                        keyId,
                        ts,
                        nonce,
                        method,
                        path,
                        ServiceRequestSignature.sha256Hex(body));
        try {
            java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
            signer.initSign(SVC.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
            signed(
                    String clientId,
                    String keyId,
                    long ts,
                    String nonce,
                    String method,
                    String path,
                    byte[] body) {
        return request(HttpMethod.valueOf(method), path)
                .header(CLIENT_ID_HEADER, clientId)
                .header(KEY_ID_HEADER, keyId)
                .header(TIMESTAMP_HEADER, Long.toString(ts))
                .header(NONCE_HEADER, nonce)
                .header(
                        SIGNATURE_HEADER,
                        signatureFor(clientId, keyId, ts, nonce, method, path, body));
    }

    private JsonNode sendOa(byte[] body, String nonce, long ts, String requestId, int expected)
            throws Exception {
        var builder =
                signed(oaClient, "oa-key", ts, nonce, "POST", OA_PATH, body)
                        .contentType("application/json")
                        .content(body);
        if (requestId != null) builder.header("X-Request-Id", requestId);
        var response = http.perform(builder).andExpect(status().is(expected)).andReturn();
        return json.readTree(response.getResponse().getContentAsString());
    }

    private Map<String, Object> grantMap() {
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

    private byte[] grantBytes() throws Exception {
        return json.writeValueAsBytes(grantMap());
    }

    private String bearer(long userId) {
        return "Bearer " + jwt.issue(userId, 1);
    }

    private JsonNode apiCheck(long userId, String method, String route, int expected)
            throws Exception {
        byte[] body = json.writeValueAsBytes(Map.of("httpMethod", method, "routeTemplate", route));
        var builder =
                signed(
                                apiClient,
                                "api-key",
                                now(),
                                "n-api-" + UUID.randomUUID(),
                                "POST",
                                API_PATH,
                                body)
                        .contentType("application/json")
                        .header("Authorization", bearer(userId))
                        .content(body);
        var response = http.perform(builder).andReturn();
        String content = response.getResponse().getContentAsString();
        if (response.getResponse().getStatus() != expected)
            System.err.println(
                    "DIAG apiCheck user="
                            + userId
                            + " expected="
                            + expected
                            + " got="
                            + response.getResponse().getStatus()
                            + " body="
                            + content);
        assertThat(response.getResponse().getStatus()).isEqualTo(expected);
        return json.readTree(content);
    }

    private boolean apiAllowed(long userId) throws Exception {
        return apiCheck(userId, "GET", "/records/{id}", 200).at("/data/allowed").asBoolean();
    }

    // ---- RBAC0 fixture: grant/revoke business role 30 on user 20
    // ---------------------------------

    private void grantRole30ToUser20() {
        db.update(
                "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                id(20),
                id(30),
                id(10));
        db.update(
                "INSERT INTO"
                        + " sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by)"
                        + " VALUES(?,?,'MANUAL','rbac-fixture',?)",
                id(20),
                id(30),
                id(10));
        tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
    }

    /**
     * History is permanent: deactivate the source, drop the effective role, re-sign the empty set.
     */
    private void revokeRole30FromUser20() {
        db.update(
                "UPDATE sys_user_role_source SET status=0, revoked_at=NOW() WHERE user_id=? AND"
                        + " role_id=? AND status=1",
                id(20),
                id(30));
        db.update("DELETE FROM sys_user_role WHERE user_id=? AND role_id=?", id(20), id(30));
        tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
    }

    private long externalEvents() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM sys_external_event WHERE source_system='OA' AND event_id=?",
                Long.class,
                event());
    }

    // ---- the 17 scenarios --------------------------------------------------------------

    @Test
    void legalTrustedOaRequestGrantsAndApplies() throws Exception {
        var result = sendOa(grantBytes(), "nonce-legal", now(), "rid-legal", 200).path("data");
        assertThat(result.path("eventId").asText()).isEqualTo(event());
        assertThat(result.path("processingStatus").asText()).isEqualTo("APPLIED");
        assertThat(result.path("idempotentReplay").asBoolean()).isFalse();
        assertThat(result.path("grantSourceId").asText()).matches("[1-9][0-9]*");
        assertThat(
                        db.queryForMap(
                                "SELECT source_type,source_ref FROM sys_user_role_source WHERE"
                                        + " user_id=?",
                                id(20)))
                .containsEntry("source_type", "OA_APPROVAL")
                .containsEntry("source_ref", approval());
        assertThat(
                        db.queryForObject(
                                "SELECT grant_revision FROM sys_user WHERE id=?",
                                Long.class,
                                id(20)))
                .isEqualTo(2);
    }

    @Test
    void illegalSignatureRejected() throws Exception {
        byte[] body = grantBytes();
        long ts = now();
        String wrong =
                Base64.getEncoder()
                        .encodeToString(
                                new byte[64]); // a well-formed but invalid Ed25519 signature
        var response =
                http.perform(
                                post(OA_PATH)
                                        .contentType("application/json")
                                        .header(CLIENT_ID_HEADER, oaClient)
                                        .header(KEY_ID_HEADER, "oa-key")
                                        .header(TIMESTAMP_HEADER, Long.toString(ts))
                                        .header(NONCE_HEADER, "nonce-bad-sig")
                                        .header(SIGNATURE_HEADER, wrong)
                                        .content(body))
                        .andExpect(status().is(401))
                        .andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_SERVICE");
        assertThat(externalEvents()).isZero();
    }

    @Test
    void bodyPathMethodTamperRejected() throws Exception {
        byte[] signedBody = grantBytes();
        // Body tamper: sign one body, ship a genuinely different one.
        var altered = grantMap();
        altered.put("eventId", event() + "-altered");
        byte[] tampered = json.writeValueAsBytes(altered);
        var bodyRequest =
                signed(oaClient, "oa-key", now(), "nonce-body", "POST", OA_PATH, signedBody)
                        .contentType("application/json")
                        .content(tampered);
        http.perform(bodyRequest).andExpect(status().is(401));
        // Path tamper: sign the path without a query, but the real request carries one.
        long ts = now();
        var pathRequest =
                post(OA_PATH)
                        .queryParam("extra", "1")
                        .contentType("application/json")
                        .header(CLIENT_ID_HEADER, oaClient)
                        .header(KEY_ID_HEADER, "oa-key")
                        .header(TIMESTAMP_HEADER, Long.toString(ts))
                        .header(NONCE_HEADER, "nonce-path")
                        .header(
                                SIGNATURE_HEADER,
                                signatureFor(
                                        oaClient,
                                        "oa-key",
                                        ts,
                                        "nonce-path",
                                        "POST",
                                        OA_PATH,
                                        signedBody))
                        .content(signedBody);
        http.perform(pathRequest).andExpect(status().is(401));
        // Method tamper: sign as GET, but the request is POST.
        long mts = now();
        var methodRequest =
                post(OA_PATH)
                        .contentType("application/json")
                        .header(CLIENT_ID_HEADER, oaClient)
                        .header(KEY_ID_HEADER, "oa-key")
                        .header(TIMESTAMP_HEADER, Long.toString(mts))
                        .header(NONCE_HEADER, "nonce-method")
                        .header(
                                SIGNATURE_HEADER,
                                signatureFor(
                                        oaClient,
                                        "oa-key",
                                        mts,
                                        "nonce-method",
                                        "GET",
                                        OA_PATH,
                                        signedBody))
                        .content(signedBody);
        http.perform(methodRequest).andExpect(status().is(401));
        assertThat(externalEvents()).isZero();
    }

    @Test
    void expiredTimestampRejected() throws Exception {
        sendOa(grantBytes(), "nonce-expired", now() - 400, null, 401);
        assertThat(externalEvents()).isZero();
    }

    @Test
    void duplicateNonceRejected() throws Exception {
        byte[] body = grantBytes();
        long ts = now();
        sendOa(body, "nonce-dup", ts, null, 200);
        sendOa(body, "nonce-dup", ts, null, 401);
    }

    @Test
    void concurrentSameNonceExactlyOneSucceeds() throws Exception {
        byte[] body = grantBytes();
        long ts = now();
        String nonce = "nonce-race";
        try (var pool = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger(), rejected = new AtomicInteger();
            Callable<Void> attempt =
                    () -> {
                        ready.countDown();
                        start.await();
                        var builder =
                                signed(oaClient, "oa-key", ts, nonce, "POST", OA_PATH, body)
                                        .contentType("application/json")
                                        .content(body);
                        int status = http.perform(builder).andReturn().getResponse().getStatus();
                        if (status == 200) accepted.incrementAndGet();
                        else if (status == 401) rejected.incrementAndGet();
                        return null;
                    };
            Future<?> f1 = pool.submit(attempt), f2 = pool.submit(attempt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
            assertThat(accepted.get()).isEqualTo(1);
            assertThat(rejected.get()).isEqualTo(1);
        }
    }

    @Test
    void disabledServiceIdentityImmediatelyRejected() throws Exception {
        db.update("UPDATE sys_service_identity SET status=0 WHERE id=?", id(70));
        tx.executeWithoutResult(s -> signatures.sign(SERVICE_IDENTITY, id(70)));
        sendOa(grantBytes(), "nonce-disabled", now(), null, 401);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?",
                                Long.class,
                                id(20)))
                .isZero();
    }

    @Test
    void keyIdMismatchRejected() throws Exception {
        byte[] body = grantBytes();
        var response =
                http.perform(
                                signed(
                                                oaClient,
                                                "wrong-key",
                                                now(),
                                                "nonce-keyid",
                                                "POST",
                                                OA_PATH,
                                                body)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().is(401))
                        .andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_SERVICE");
    }

    @Test
    void appIdBindingMismatchRejected() throws Exception {
        // An app-unbound OA identity cannot act as an app-bound API caller.
        byte[] body =
                json.writeValueAsBytes(
                        Map.of("httpMethod", "GET", "routeTemplate", "/records/{id}"));
        var response =
                http.perform(
                                signed(
                                                oaClient,
                                                "oa-key",
                                                now(),
                                                "nonce-appid",
                                                "POST",
                                                API_PATH,
                                                body)
                                        .contentType("application/json")
                                        .header("Authorization", bearer(id(20)))
                                        .content(body))
                        .andExpect(status().is(401))
                        .andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_SERVICE");
    }

    @Test
    void scopeMismatchRejected() throws Exception {
        // An API_AUTH identity cannot be used for the OA_DECISION scope.
        byte[] body = grantBytes();
        var response =
                http.perform(
                                signed(
                                                apiClient,
                                                "api-key",
                                                now(),
                                                "nonce-scope",
                                                "POST",
                                                OA_PATH,
                                                body)
                                        .contentType("application/json")
                                        .content(body))
                        .andExpect(status().is(401))
                        .andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("INVALID_SERVICE");
        assertThat(externalEvents()).isZero();
    }

    @Test
    void forgedOaContextRejectedBeforeBusinessDecision() {
        assertThatThrownBy(
                        () ->
                                oaCaller.verifyTrustedEvent(
                                        new TrustedEventContext(
                                                "OA", oaClient, "never-authenticated"),
                                        "OA_DECISION"))
                .isInstanceOf(com.whatname.iam.common.exception.BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE);
    }

    @Test
    void serviceIdentityFactTamperingRejected() throws Exception {
        db.update(
                "UPDATE sys_service_identity SET public_key=? WHERE id=?",
                b64(keys("Ed25519").getPublic().getEncoded()),
                id(70));
        sendOa(grantBytes(), "nonce-identity-tamper", now(), null, 401);
        assertThat(externalEvents()).isZero();
    }

    @Test
    void rbac0PassButNoM5ScopeRejected() throws Exception {
        // Admin 11 (level-2, dept 2) holds IAM_USERS_VIEW (RBAC0 pass) but user 20 is in dept 1
        // (out of admin scope).
        var response =
                http.perform(
                                get(USERS_PATH + "/" + sid(20) + "/permissions")
                                        .param("appCode", "E2E" + b)
                                        .header("Authorization", bearer(id(11))))
                        .andExpect(status().is(404))
                        .andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("RESOURCE_NOT_FOUND");
    }

    @Test
    void m5PassButNoRbac0Rejected() throws Exception {
        // Admin 10 (level-1, covers all depts = scope pass) loses IAM_USERS_VIEW (RBAC0 fail).
        db.update("DELETE FROM sys_role_permission WHERE role_id=? AND menu_id=?", id(31), id(52));
        tx.executeWithoutResult(s -> signatures.sign(ROLE_MENUS, id(31)));
        var response =
                http.perform(
                                get(USERS_PATH + "/" + sid(20) + "/permissions")
                                        .param("appCode", "E2E" + b)
                                        .header("Authorization", bearer(id(10))))
                        .andExpect(status().is(403))
                        .andReturn();
        assertThat(json.readTree(response.getResponse().getContentAsString()).path("code").asText())
                .isEqualTo("PERMISSION_DENIED");
    }

    @Test
    void permissionRevokedImmediatelyFails() throws Exception {
        grantRole30ToUser20();
        assertThat(apiAllowed(id(20))).isTrue();
        revokeRole30FromUser20();
        assertThat(apiAllowed(id(20))).isFalse();
    }

    @Test
    void menuDisabledInvalidatesPermission() throws Exception {
        grantRole30ToUser20();
        assertThat(apiAllowed(id(20))).isTrue();
        // Disabling the business menu removes the related API permission, without touching the
        // account.
        db.update("UPDATE sys_menu SET status=0 WHERE id=?", id(56));
        tx.executeWithoutResult(s -> signatures.sign(RESOURCE_MENU, id(56)));
        assertThat(apiAllowed(id(20))).isFalse();
    }

    @Test
    void businessRollbackDoesNotRestoreNonce() throws Exception {
        byte[] body = grantBytes();
        long ts = now();
        fault.point = "insertOaSource";
        sendOa(body, "nonce-rollback", ts, null, 503);
        assertThat(fault.hit).isEqualTo("insertOaSource");
        fault.point = null;
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_service_nonce WHERE client_id=? AND"
                                        + " nonce=?",
                                Long.class,
                                oaClient,
                                "nonce-rollback"))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?",
                                Long.class,
                                id(20)))
                .isZero();
        // The same signed request cannot be replayed: the nonce is already consumed.
        sendOa(body, "nonce-rollback", ts, null, 401);
    }

    @Test
    void auditRecordAndEventRequestIdTraceable() throws Exception {
        String requestId = "trace-e2e-" + b;
        String nonce = "nonce-audit";
        sendOa(grantBytes(), nonce, now(), requestId, 200);
        var row =
                db.queryForMap(
                        "SELECT id,request_id,external_event_id,evidence FROM sys_operation_log"
                                + " WHERE operation_type='OA_DECISION' AND target_id=?",
                        id(20));
        assertThat(row.get("external_event_id")).isEqualTo(event());
        assertThat((String) row.get("request_id")).matches("[A-Za-z0-9._-]{1,64}");
        var evidence = json.readTree((String) row.get("evidence"));
        assertThat(evidence.path("schema").asText()).isEqualTo("C_OA_CHANGE_V1");
        assertThat(evidence.at("/origin/sourceSystem").asText()).isEqualTo("OA");
        assertThat(evidence.at("/origin/requestId").asText()).isEqualTo(nonce);
        assertThat(evidence.at("/origin/eventId").asText()).isEqualTo(event());
        assertThat(evidence.at("/origin/clientId").asText()).isEqualTo(oaClient);
        assertThat(((Number) row.get("id")).longValue()).isPositive();
    }

    @Test
    void dependencySecurityStateUnavailableFailsClosed() throws Exception {
        db.update(
                "DELETE FROM sys_data_signature WHERE data_type='SERVICE_IDENTITY' AND data_id=?",
                id(70));
        var error = sendOa(grantBytes(), "nonce-failclosed", now(), null, 401);
        assertThat(error.path("code").asText()).isEqualTo("INVALID_SERVICE");
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?",
                                Long.class,
                                id(20)))
                .isZero();
        assertThat(externalEvents()).isZero();
    }
}
