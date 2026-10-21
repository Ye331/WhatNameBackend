package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.event.TrustedEventContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;
import static org.junit.jupiter.api.Assertions.*;

/** Trusted-service request authentication against a real MySQL: valid, tampered, unknown client,
 * cross-scope, expired, duplicate nonce, concurrency race, and business-rollback nonce survival. */
@SpringBootTest(classes = ServiceRequestAuthenticationIT.TestApp.class, properties = "spring.config.import=")
@Testcontainers
class ServiceRequestAuthenticationIT {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan("com.whatname.iam.mapper.service")
    @Import({ServiceRequestAuthenticator.class, NonceStore.class, ServiceApiCaller.class, ServiceOaCaller.class})
    static class TestApp { }

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"))
            .withDatabaseName("unified_permission")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/01_schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01.sql");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    private static final KeyPair CLIENT_KEYS = keys();
    private static KeyPair keys() {
        try { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    private static final String OA_CLIENT = "oa-fixture-client";
    private static final String OA_KEY = "oa-fixture-key";
    private static final String API_CLIENT = "api-fixture-client";
    private static final String API_KEY = "api-fixture-key";
    private static final String OA_PATH = "/api/v1/integrations/oa/decisions";
    private static final String API_PATH = "/api/v1/auth/check";
    private static final byte[] OA_BODY = "{\"eventId\":\"e1\",\"approvedAt\":\"2024-01-01T00:00:00Z\"}".getBytes(StandardCharsets.UTF_8);
    private static final byte[] API_BODY = "{\"httpMethod\":\"GET\",\"routeTemplate\":\"/x\"}".getBytes(StandardCharsets.UTF_8);

    @Autowired JdbcTemplate db;
    @Autowired ServiceOaCaller oaCaller;
    @Autowired ServiceApiCaller apiCaller;
    @Autowired PlatformTransactionManager transactionManager;

    private String publicKeyB64;
    private String fingerprint;
    private long appId;

    @BeforeEach void seed() {
        publicKeyB64 = Base64.getEncoder().encodeToString(CLIENT_KEYS.getPublic().getEncoded());
        fingerprint = ServiceRequestSignature.sha256Hex(publicKeyB64.getBytes(StandardCharsets.UTF_8));
        db.update("DELETE FROM sys_service_nonce");
        db.update("DELETE FROM sys_service_identity");
        db.update("DELETE FROM sys_application");
        appId = insert("INSERT INTO sys_application(app_code, app_name) VALUES('api-app','Fixture')");
        db.update("INSERT INTO sys_service_identity(client_id, credential_hash, public_key, key_id, allowed_role_ids, allowed_dept_ids, allowed_operations, allowed_scope, status) VALUES(?,?,?,?,?,?,?,'OA_DECISION',1)",
                OA_CLIENT, fingerprint, publicKeyB64, OA_KEY, "[\"1\"]", "[\"1\"]", "[\"GRANT\",\"REVOKE\"]");
        db.update("INSERT INTO sys_service_identity(app_id, client_id, credential_hash, public_key, key_id, allowed_scope, status) VALUES(?,?,?,?,?,'API_AUTH',1)",
                appId, API_CLIENT, fingerprint, publicKeyB64, API_KEY);
    }

    @Test void validOaRequestAuthenticates() {
        Req req = signed(OA_CLIENT, OA_KEY, now(), "nonce-valid-oa", "POST", OA_PATH, null, OA_BODY);
        TrustedEventContext ctx = oaCaller.authenticate(req.request(), req.body());
        assertEquals("OA", ctx.sourceSystem());
        assertEquals(OA_CLIENT, ctx.clientId());
        assertEquals("nonce-valid-oa", ctx.requestId());
    }

    @Test void validApiRequestReturnsBoundApplication() {
        Req req = signed(API_CLIENT, API_KEY, now(), "nonce-valid-api", "POST", API_PATH, null, API_BODY);
        assertEquals(appId, apiCaller.authenticatedApplication(req.request(), req.body()));
    }

    @Test void tamperedBodyRejected() {
        byte[] signedBody = "{\"eventId\":\"e1\"}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"eventId\":\"e2\"}".getBytes(StandardCharsets.UTF_8);
        Req req = signed(OA_CLIENT, OA_KEY, now(), "nonce-tampered", "POST", OA_PATH, null, signedBody);
        assertRejected(() -> oaCaller.authenticate(req.request(), tamperedBody));
    }

    @Test void unknownClientRejected() {
        Req req = signed("ghost-client", OA_KEY, now(), "nonce-unknown", "POST", OA_PATH, null, OA_BODY);
        assertRejected(() -> oaCaller.authenticate(req.request(), req.body()));
    }

    @Test void crossScopeRejected() {
        Req oaAsApi = signed(OA_CLIENT, OA_KEY, now(), "nonce-cross-1", "POST", API_PATH, null, API_BODY);
        assertRejected(() -> apiCaller.authenticatedApplication(oaAsApi.request(), oaAsApi.body()));
        Req apiAsOa = signed(API_CLIENT, API_KEY, now(), "nonce-cross-2", "POST", OA_PATH, null, OA_BODY);
        assertRejected(() -> oaCaller.authenticate(apiAsOa.request(), apiAsOa.body()));
    }

    @Test void expiredTimestampRejected() {
        Req past = signed(OA_CLIENT, OA_KEY, now() - 400, "nonce-expired", "POST", OA_PATH, null, OA_BODY);
        assertRejected(() -> oaCaller.authenticate(past.request(), past.body()));
    }

    @Test void duplicateNonceRejected() {
        Req req = signed(OA_CLIENT, OA_KEY, now(), "nonce-duplicate", "POST", OA_PATH, null, OA_BODY);
        oaCaller.authenticate(req.request(), req.body());
        assertRejected(() -> oaCaller.authenticate(req.request(), req.body()));
    }

    @Test void concurrentSameNonceOnlyOneAccepted() throws Exception {
        String nonce = "nonce-race";
        Req req = signed(OA_CLIENT, OA_KEY, now(), nonce, "POST", OA_PATH, null, OA_BODY);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger accepted = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            Callable<Void> attempt = () -> {
                ready.countDown();
                start.await();
                try {
                    oaCaller.authenticate(req.request(), req.body());
                    accepted.incrementAndGet();
                } catch (BusinessException e) {
                    assertEquals(INVALID_SERVICE, e.errorCode());
                    rejected.incrementAndGet();
                }
                return null;
            };
            Future<?> f1 = pool.submit(attempt);
            Future<?> f2 = pool.submit(attempt);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            f1.get(20, TimeUnit.SECONDS);
            f2.get(20, TimeUnit.SECONDS);
            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void nonceSurvivesBusinessRollback() {
        String nonce = "nonce-rollback";
        Req req = signed(OA_CLIENT, OA_KEY, now(), nonce, "POST", OA_PATH, null, OA_BODY);
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            oaCaller.authenticate(req.request(), req.body());
            status.setRollbackOnly();
        });
        Integer count = db.queryForObject(
                "SELECT COUNT(*) FROM sys_service_nonce WHERE client_id=? AND nonce=?", Integer.class, OA_CLIENT, nonce);
        assertEquals(1, count);
        assertRejected(() -> oaCaller.authenticate(req.request(), req.body()));
    }

    private static long now() { return System.currentTimeMillis() / 1000; }

    private static void assertRejected(Executable run) {
        BusinessException e = assertThrows(BusinessException.class, run);
        assertEquals(INVALID_SERVICE, e.errorCode());
    }

    private Req signed(String clientId, String keyId, long ts, String nonce, String method,
                       String uri, String query, byte[] body) {
        String rawPathAndQuery = query == null || query.isEmpty() ? uri : uri + "?" + query;
        String message = ServiceRequestSignature.message(clientId, keyId, ts, nonce, method, rawPathAndQuery,
                ServiceRequestSignature.sha256Hex(body));
        return new Req(clientId, keyId, ts, nonce, method, uri, query, body, sign(message));
    }

    private static String sign(String message) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(CLIENT_KEYS.getPrivate());
            signer.update(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    private long insert(String sql, Object... args) {
        KeyHolder holder = new GeneratedKeyHolder();
        db.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            return ps;
        }, holder);
        Number key = holder.getKey();
        if (key == null) throw new IllegalStateException("no generated key");
        return key.longValue();
    }

    private record Req(String clientId, String keyId, long ts, String nonce, String method,
                       String uri, String query, byte[] body, String signature) {
        MockHttpServletRequest request() {
            MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
            if (query != null) r.setQueryString(query);
            r.addHeader(ServiceRequestSignature.CLIENT_ID_HEADER, clientId);
            r.addHeader(ServiceRequestSignature.KEY_ID_HEADER, keyId);
            r.addHeader(ServiceRequestSignature.TIMESTAMP_HEADER, Long.toString(ts));
            r.addHeader(ServiceRequestSignature.NONCE_HEADER, nonce);
            r.addHeader(ServiceRequestSignature.SIGNATURE_HEADER, signature);
            return r;
        }
    }
}
