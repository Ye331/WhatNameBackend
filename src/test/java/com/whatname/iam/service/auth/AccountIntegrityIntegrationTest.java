package com.whatname.iam.service.auth;

import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.auth.AuthRequests.*;
import com.whatname.iam.dto.user.UserRequests;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.department.OrganizationWriteMapper;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.department.*;
import com.whatname.iam.service.hr.HrCollaboration;
import com.whatname.iam.service.permission.*;
import com.whatname.iam.service.user.UserService;
import com.whatname.iam.support.TestDatabase;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Instant;
import java.util.*;

/**
 * Real production A beans + D Ed25519/permissions/audit + MySQL. C integrity remains a fixture. SMS
 * uses memory delivery except the explicit local-file/HTTP scenarios below; production profile
 * selection is covered independently by LocalDemoSmsSenderTest.
 */
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountIntegrityIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(
            basePackages = "com.whatname.iam",
            excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = {".*Test.*", "com.whatname.iam.IamApplication"}),
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = TestConfiguration.class)
            })
    static class Bootstrap {}

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Primary
        AdminPorts.Integrity fixtureCIntegrity(DataSignatureService signatures) {
            return new AdminPorts.Integrity() {
                public void verify(AdminState state) {
                    signatures.verify(ORGANIZATION, 0);
                    state.users().keySet().forEach(id -> signatures.verify(USER_IDENTITY, id));
                    state.users().keySet().stream()
                            .filter(state.appointments()::containsKey)
                            .forEach(id -> signatures.verify(ADMIN, id));
                }

                public void sign(AdminState state, Set<Long> ids) {
                    throw new UnsupportedOperationException("C writes not adapted");
                }
            };
        }

        @Bean
        @Primary
        Delivery fixtureSms() {
            return new Delivery();
        }

        @Bean
        AuditFailure auditFailure() {
            return new AuditFailure();
        }
    }

    static class Delivery implements SmsSender {
        final Map<String, String> codes = new HashMap<>();
        volatile SmsSender delegate;

        public void requireAvailable() {
            var selected = delegate;
            if (selected != null) selected.requireAvailable();
        }

        public void send(String phone, String purpose, String code) {
            var selected = delegate;
            if (selected != null) selected.send(phone, purpose, code);
            else codes.put(phone + purpose, code);
        }
    }

    @Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class AuditFailure implements Interceptor {
        boolean fail;

        public Object intercept(Invocation call) throws Throwable {
            if (fail
                    && ((MappedStatement) call.getArgs()[0])
                            .getId()
                            .equals("com.whatname.iam.mapper.audit.AuditMapper.insert"))
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        "fixture audit outage");
            return call.proceed();
        }
    }

    final String prefix = "AS2" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    final String password = "Cobalt!Ridge847", nextPassword = "Violet!Trail956";
    final List<Long> accounts = new ArrayList<>(), menus = new ArrayList<>();
    ConfigurableApplicationContext app;
    JdbcTemplate db;
    UserAccountMapper mapper;
    OrganizationWriteMapper organization;
    DataSignatureService signatures;
    ManagementDataSecurity data;
    JwtTokenService jwt;
    SensitiveDataCodec codec;
    AuthService auth;
    UserService users;
    DepartmentService departments;
    TransactionTemplate tx;
    ObjectMapper json;
    Delivery sms;
    AuditFailure failure;
    List<Map<String, Object>> originalOrgSignatures, originalDepartments;
    long dept, leaf, application, role;
    int seq;
    UserAccount actor, target;
    String bearer;

    @BeforeAll
    void start() throws Exception {
        var rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        var jwtKeys = rsa.generateKeyPair();
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var settings = new HashMap<String, Object>();
        settings.put("server.port", 0);
        settings.put("logging.level.root", "WARN");
        settings.put("iam.security.jwt-private-key", encode(jwtKeys.getPrivate().getEncoded()));
        settings.put("iam.security.jwt-public-key", encode(jwtKeys.getPublic().getEncoded()));
        settings.put("iam.security.jwt-issuer", prefix);
        settings.put("iam.security.jwt-audience", prefix);
        settings.put("iam.security.jwt-key-id", "fixture-jwt");
        settings.put("iam.security.data-signing.key-id", "fixture-as2");
        settings.put(
                "iam.security.data-signing.private-key", encode(signing.getPrivate().getEncoded()));
        settings.put(
                "iam.security.data-signing.public-keys.fixture-as2",
                encode(signing.getPublic().getEncoded()));
        for (String key : List.of("data-key", "phone-hmac-key", "sms-hmac-key")) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            settings.put("iam.security." + key, encode(bytes));
        }
        settings.put("iam.permission.management-app-code", prefix);
        app =
                new SpringApplicationBuilder(Bootstrap.class, Fixtures.class)
                        .properties(settings)
                        .run(
                                TestDatabase.arguments(
                                        "--server.port=0", "--server.address=127.0.0.1"));
        db = app.getBean(JdbcTemplate.class);
        mapper = app.getBean(UserAccountMapper.class);
        organization = app.getBean(OrganizationWriteMapper.class);
        signatures = app.getBean(DataSignatureService.class);
        data = app.getBean(ManagementDataSecurity.class);
        jwt = app.getBean(JwtTokenService.class);
        codec = app.getBean(SensitiveDataCodec.class);
        auth = app.getBean(AuthService.class);
        users = app.getBean(UserService.class);
        departments = app.getBean(DepartmentService.class);
        json = app.getBean(ObjectMapper.class);
        sms = app.getBean(Delivery.class);
        failure = app.getBean(AuditFailure.class);
        tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertEquals(
                "MySQL",
                db.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>)
                                c -> c.getMetaData().getDatabaseProductName()));
        originalDepartments = db.queryForList("SELECT * FROM sys_dept ORDER BY id");
        originalOrgSignatures =
                db.queryForList(
                        "SELECT * FROM sys_data_signature WHERE data_type IN"
                            + " ('ORGANIZATION','DEPARTMENT') ORDER BY data_type,data_id");
        dept =
                db.queryForObject(
                        "SELECT id FROM sys_dept WHERE dept_code='TEST-SEED-ROOT'", Long.class);
        leaf =
                db.queryForObject(
                        "SELECT id FROM sys_dept WHERE status=1 AND merged_into_dept_id IS NULL AND"
                            + " dept_path LIKE ? AND id NOT IN (SELECT parent_id FROM sys_dept)"
                            + " ORDER BY id LIMIT 1",
                        Long.class,
                        "/" + dept + "/%");
        tx.executeWithoutResult(
                status -> {
                    organization
                            .organization()
                            .forEach(d -> signatures.sign(DEPARTMENT, d.getId()));
                    signatures.sign(ORGANIZATION, 0);
                });
        application =
                insert(
                        "INSERT INTO sys_application(app_code,app_name) VALUES(?,?)",
                        prefix,
                        "A stage2 fixture");
        role =
                insert(
                        "INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",
                        prefix,
                        "A stage2 fixture");
        for (String permission :
                List.of(
                        "IAM_USERS_VIEW",
                        "IAM_USERS_WRITE",
                        "IAM_DEPTS_VIEW",
                        "IAM_DEPTS_FREEZE")) {
            long id =
                    insert(
                            "INSERT INTO sys_menu(app_id,parent_id,menu_code,menu_name,menu_type)"
                                + " VALUES(?,0,?,?,2)",
                            application,
                            permission,
                            permission);
            menus.add(id);
            db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)", role, id);
        }
        tx.executeWithoutResult(
                status -> {
                    signatures.sign(RESOURCE_APPLICATION, application);
                    signatures.sign(ROLE_MENUS, role);
                    for (long id : menus) {
                        signatures.sign(RESOURCE_MENU, id);
                        signatures.sign(MENU_APIS, id);
                    }
                });
    }

    @BeforeEach
    void fixtures() {
        failure.fail = false;
        actor = account(true);
        target = account(false);
        bearer = "Bearer " + jwt.issue(actor.id, 1);
    }

    @AfterEach
    void resetFailure() {
        failure.fail = false;
        RequestContextHolder.resetRequestAttributes();
    }

    @AfterAll
    void retire() {
        if (db != null && originalDepartments != null && originalOrgSignatures != null) {
            failure.fail = false;
            tx.executeWithoutResult(
                    status -> {
                        for (long id : accounts) {
                            db.update(
                                    "UPDATE sys_user_role_source SET status=0,revoked_at=NOW()"
                                        + " WHERE user_id=? AND status=1",
                                    id);
                            db.update("DELETE FROM sys_user_role WHERE user_id=?", id);
                            db.update(
                                    "UPDATE sys_user SET"
                                        + " status=2,session_version=session_version+1,grant_revision=grant_revision+1"
                                        + " WHERE id=?",
                                    id);
                            db.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id);
                            signatures.sign(USER_IDENTITY, id);
                            signatures.sign(USER_GRANTS, id);
                            if (count("SELECT COUNT(*) FROM sys_admin WHERE user_id=?", id) > 0) {
                                signatures.sign(ADMIN, id);
                                signatures.sign(ADMIN_DELEGABLE_ROLES, id);
                            }
                        }
                        db.update(
                                "UPDATE sys_sms_code SET consumed_at=COALESCE(consumed_at,NOW())"
                                    + " WHERE phone_hash IN (SELECT phone_hash FROM sys_user WHERE"
                                    + " username LIKE ? OR temp_reason LIKE ?)",
                                prefix + "%",
                                prefix + "%");
                        db.update("UPDATE sys_application SET status=0 WHERE id=?", application);
                        db.update("UPDATE sys_role SET status=0 WHERE id=?", role);
                        db.update("UPDATE sys_menu SET status=0 WHERE app_id=?", application);
                        if (application > 0) signatures.sign(RESOURCE_APPLICATION, application);
                        if (role > 0) signatures.sign(ROLE_MENUS, role);
                        for (long id : menus) {
                            signatures.sign(RESOURCE_MENU, id);
                            signatures.sign(MENU_APIS, id);
                        }
                        for (var row : originalDepartments)
                            db.update(
                                    "UPDATE sys_dept SET status=?,updated_at=? WHERE id=?",
                                    row.get("status"),
                                    row.get("updated_at"),
                                    row.get("id"));
                        db.update(
                                "DELETE FROM sys_data_signature WHERE data_type IN"
                                    + " ('ORGANIZATION','DEPARTMENT')");
                        for (var row : originalOrgSignatures)
                            db.update(
                                    "INSERT INTO"
                                        + " sys_data_signature(id,data_type,data_id,signature,sign_version,key_id,created_at,updated_at)"
                                        + " VALUES(?,?,?,?,?,?,?,?)",
                                    row.get("id"),
                                    row.get("data_type"),
                                    row.get("data_id"),
                                    row.get("signature"),
                                    row.get("sign_version"),
                                    row.get("key_id"),
                                    row.get("created_at"),
                                    row.get("updated_at"));
                    });
            assertEquals(
                    originalDepartments, db.queryForList("SELECT * FROM sys_dept ORDER BY id"));
            assertEquals(
                    originalOrgSignatures,
                    db.queryForList(
                            "SELECT * FROM sys_data_signature WHERE data_type IN"
                                + " ('ORGANIZATION','DEPARTMENT') ORDER BY data_type,data_id"));
            assertEquals(
                    accounts.size(),
                    db.queryForObject(
                            "SELECT COUNT(*) FROM sys_user WHERE status=2 AND (username LIKE ? OR"
                                + " temp_reason LIKE ?)",
                            Integer.class,
                            prefix + "%",
                            prefix + "%"));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM sys_user_role_source s JOIN sys_user u ON"
                                + " u.id=s.user_id WHERE s.status=1 AND (u.username LIKE ? OR"
                                + " u.temp_reason LIKE ?)",
                            prefix + "%",
                            prefix + "%"));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM sys_user_role r JOIN sys_user u ON u.id=r.user_id"
                                + " WHERE u.username LIKE ? OR u.temp_reason LIKE ?",
                            prefix + "%",
                            prefix + "%"));
            System.out.println(
                    "A_STAGE2_CLEANUP prefix="
                            + prefix
                            + " retiredUsers="
                            + accounts.size()
                            + " originalOrganizationAndSignaturesRestored=true");
        }
        if (app != null) app.close();
    }

    @Test
    void productionBeansAreSelectedAndUnprovenHrCallsRefuse() {
        assertInstanceOf(
                ManagementDataSecurityService.class,
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(data));
        assertInstanceOf(AccountSecurityService.class, app.getBean(AccountSecurity.class));
        expect(
                ErrorCode.INVALID_TOKEN,
                () -> app.getBean(AdminPorts.Identity.class).currentUserId());
        expect(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                () -> app.getBean(AdminPorts.Functions.class).require(actor.id, "IAM_USERS_VIEW"));
        expect(
                ErrorCode.INVALID_SERVICE,
                () ->
                        app.getBean(DepartmentCollaboration.class)
                                .authenticateHr(new MockHttpServletRequest(), "HR_EMPLOYEE"));
        var hr = app.getBean(HrCollaboration.class);
        assertInstanceOf(
                com.whatname.iam.service.grant.HrGrantCollaboration.class,
                org.springframework.test.util.AopTestUtils.getUltimateTargetObject(hr));
        assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> hr.requireRehireEligible(target.id));
        expect(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                () -> tx.executeWithoutResult(status -> hr.requireRehireEligible(target.id)));
    }

    @Test
    void loginPersistsExactlyOneLoginAndSealedSanitizedOperation() throws Exception {
        assertNotNull(login(target));
        assertEquals(1, count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=?", target.id));
        var retained = json.readValue(evidence("AU01_LOGIN", target.id), Map.class);
        @SuppressWarnings("unchecked")
        var seal = (Map<String, Object>) retained.remove("seal");
        assertEquals(Boolean.TRUE, tx.execute(status -> signatures.verifySeal(retained, seal)));
        String text = retained.toString();
        assertFalse(text.contains(target.phone));
        assertFalse(text.contains(target.passwordHash));
    }

    @Test
    void realHttpLoginReturnsTokenUsingProductionAAndDBeans() throws Exception {
        int port =
                ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                                app)
                        .getWebServer()
                        .getPort();
        var request =
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(
                                        "http://127.0.0.1:" + port + "/api/v1/auth/login"))
                        .header("Content-Type", "application/json")
                        .POST(
                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "loginType",
                                                        "USERNAME",
                                                        "identifier",
                                                        target.username,
                                                        "password",
                                                        password))))
                        .build();
        var response =
                java.net.http.HttpClient.newHttpClient()
                        .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        var result = json.readTree(response.body());
        assertEquals(
                target.id.longValue(),
                jwt.verify(result.at("/data/accessToken").asText()).userId());
        assertEquals(1, count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=?", target.id));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='AU01_LOGIN'"
                            + " AND target_id=?",
                        target.id));
    }

    @Test
    void localDemoFileCodeInitializesPasswordAndLogsInOverHttp(@TempDir Path inbox)
            throws Exception {
        var result = create();
        long id = Long.parseLong(result.userId());
        accounts.add(id);
        UserAccount created = mapper.findById(id);
        String grants = signature(USER_GRANTS, id), phone = phone(created);
        sms.delegate = new LocalDemoSmsSender(inbox.toString());
        try {
            var accepted =
                    authHttp("/sms/send", Map.of("phone", phone, "purpose", "SET_PASSWORD"), 200);
            assertOpaqueSmsResponse(accepted, phone);
            String code = localProof(inbox, phone, "SET_PASSWORD");
            assertTrue(
                    authHttp(
                                    "/password/initialize",
                                    Map.of(
                                            "phone",
                                            phone,
                                            "code",
                                            code,
                                            "newPassword",
                                            nextPassword),
                                    200)
                            .at("/data/initialized")
                            .asBoolean());
            var loggedIn =
                    authHttp(
                            "/login",
                            Map.of(
                                    "loginType",
                                    "USERNAME",
                                    "identifier",
                                    created.username,
                                    "password",
                                    nextPassword),
                            200);
            assertEquals(id, jwt.verify(loggedIn.at("/data/accessToken").asText()).userId());
            assertEquals(1, mapper.findById(id).passwordInitialized);
            assertEquals(
                    1,
                    count(
                            "SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=? AND"
                                + " purpose='SET_PASSWORD' AND consumed_at IS NOT NULL",
                            created.phoneHash));
            assertEquals(1, count("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?", id));
            assertEquals(grants, signature(USER_GRANTS, id));
            signVerify(USER_IDENTITY, id);
            signVerify(USER_GRANTS, id);
        } finally {
            sms.delegate = null;
        }
    }

    @Test
    void localDemoResetOverHttpInvalidatesOldPasswordAndSession(@TempDir Path inbox)
            throws Exception {
        var before =
                authHttp(
                        "/login",
                        Map.of(
                                "loginType",
                                "USERNAME",
                                "identifier",
                                target.username,
                                "password",
                                password),
                        200);
        String old = "Bearer " + before.at("/data/accessToken").asText(),
                grants = signature(USER_GRANTS, target.id),
                phone = phone(target);
        sms.delegate = new LocalDemoSmsSender(inbox.toString());
        try {
            assertOpaqueSmsResponse(
                    authHttp("/sms/send", Map.of("phone", phone, "purpose", "RESET_PASSWORD"), 200),
                    phone);
            String code = localProof(inbox, phone, "RESET_PASSWORD");
            var reset =
                    authHttp(
                            "/password/reset",
                            Map.of("phone", phone, "code", code, "newPassword", nextPassword),
                            200);
            assertTrue(reset.at("/data/passwordReset").asBoolean());
            assertTrue(reset.at("/data/sessionsInvalidated").asBoolean());
            expect(
                    ErrorCode.INVALID_TOKEN,
                    () ->
                            tx.executeWithoutResult(
                                    status -> auth.requireCurrentSession(jwt.verifyBearer(old))));
            assertEquals(
                    "INVALID_CREDENTIALS",
                    authHttp(
                                    "/login",
                                    Map.of(
                                            "loginType",
                                            "USERNAME",
                                            "identifier",
                                            target.username,
                                            "password",
                                            password),
                                    401)
                            .path("code")
                            .asText());
            var after =
                    authHttp(
                            "/login",
                            Map.of(
                                    "loginType",
                                    "USERNAME",
                                    "identifier",
                                    target.username,
                                    "password",
                                    nextPassword),
                            200);
            assertEquals(2, jwt.verify(after.at("/data/accessToken").asText()).sessionVersion());
            assertEquals(2, mapper.findById(target.id).sessionVersion);
            assertEquals(
                    2,
                    count("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?", target.id));
            assertEquals(grants, signature(USER_GRANTS, target.id));
            signVerify(USER_IDENTITY, target.id);
        } finally {
            sms.delegate = null;
        }
    }

    @Test
    void localFileDeliveryAfterAuditRollbackIsNotAUsablePasswordProof(@TempDir Path inbox)
            throws Exception {
        String phone = phone(target), before = signature(USER_IDENTITY, target.id);
        sms.delegate = new LocalDemoSmsSender(inbox.toString());
        failure.fail = true;
        try {
            assertEquals(
                    "AUDIT_UNAVAILABLE",
                    authHttp("/sms/send", Map.of("phone", phone, "purpose", "RESET_PASSWORD"), 503)
                            .path("code")
                            .asText());
            String rolledBackCode = localProof(inbox, phone, "RESET_PASSWORD");
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=?",
                            target.phoneHash));
            assertEquals(
                    0,
                    count(
                            "SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='AU05_SMS'"
                                + " AND target_id=?",
                            target.id));
            failure.fail = false;
            assertEquals(
                    "INVALID_CREDENTIALS",
                    authHttp(
                                    "/password/reset",
                                    Map.of(
                                            "phone",
                                            phone,
                                            "code",
                                            rolledBackCode,
                                            "newPassword",
                                            nextPassword),
                                    401)
                            .path("code")
                            .asText());
            assertEquals(target.passwordHash, mapper.findById(target.id).passwordHash);
            assertEquals(
                    1,
                    count("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?", target.id));
            assertEquals(before, signature(USER_IDENTITY, target.id));
        } finally {
            sms.delegate = null;
            failure.fail = false;
        }
    }

    @Test
    void tamperedIdentityRefusesWithoutRepair() {
        String before = signature(USER_IDENTITY, target.id);
        db.update("UPDATE sys_user SET temp_reason='tampered' WHERE id=?", target.id);
        expect(ErrorCode.INTEGRITY_FAILURE, () -> login(target));
        assertEquals(before, signature(USER_IDENTITY, target.id));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=?", target.id));
    }

    @Test
    void missingOldIdentityCannotBeInitializedEvenWithNullTimestamp() {
        db.update(
                "DELETE FROM sys_data_signature WHERE data_type='USER_IDENTITY' AND data_id=?",
                target.id);
        expect(ErrorCode.INTEGRITY_FAILURE, () -> login(target));
        UserAccount old = mapper.findById(target.id);
        old.createdAt = null;
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () -> tx.executeWithoutResult(status -> data.signIdentity(old)));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM sys_data_signature WHERE data_type='USER_IDENTITY'"
                            + " AND data_id=?",
                        target.id));
    }

    @Test
    void loginAuditFailureRollsBackResultLogAndLoginState() {
        failure.fail = true;
        expect(ErrorCode.AUDIT_UNAVAILABLE, () -> login(target));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_login_log WHERE user_id=?", target.id));
        assertNull(mapper.findById(target.id).lastLoginAt);
    }

    @Test
    void newTemporaryAccountHasBothValidSignaturesAndEmptyGrants() throws Exception {
        var result = create();
        long id = Long.parseLong(result.userId());
        accounts.add(id);
        tx.executeWithoutResult(
                status -> {
                    signatures.verify(USER_IDENTITY, id);
                    signatures.verify(USER_GRANTS, id);
                    var grants = (Map<?, ?>) signatures.snapshot(USER_GRANTS, id).get("facts");
                    assertEquals(List.of(), grants.get("roles"));
                    assertEquals(List.of(), grants.get("sources"));
                });
        assertEquals(
                prefix + " reason",
                json.readTree(evidence("US03_CREATE", id)).path("reason").asText());
        UserAccount created = mapper.findById(id);
        String grants = signature(USER_GRANTS, id);
        auth.sendSms(new SendSms(phone(created), Purpose.SET_PASSWORD));
        String proof = sms.codes.get(phone(created) + Purpose.SET_PASSWORD.name());
        auth.changePassword(new Password(phone(created), proof, nextPassword), true);
        signVerify(USER_IDENTITY, id);
        signVerify(USER_GRANTS, id);
        assertEquals(1, mapper.findById(id).passwordInitialized);
        assertEquals(grants, signature(USER_GRANTS, id));
        assertNotNull(
                auth.login(
                        new Login(LoginType.USERNAME, created.username, nextPassword),
                        "127.0.0.1",
                        "fixture"));
    }

    @Test
    void createAuditFailureLeavesNoAccountOrSignature() {
        int before = count("SELECT COUNT(*) FROM sys_user WHERE created_by=?", actor.id);
        failure.fail = true;
        expect(ErrorCode.AUDIT_UNAVAILABLE, this::create);
        assertEquals(before, count("SELECT COUNT(*) FROM sys_user WHERE created_by=?", actor.id));
    }

    @Test
    void realFunctionDenialPreventsManagementCreation() {
        int before = count("SELECT COUNT(*) FROM sys_user WHERE created_by=?", actor.id);
        db.update("UPDATE sys_role SET status=0 WHERE id=?", role);
        sign(ROLE_MENUS, role);
        try {
            expect(ErrorCode.PERMISSION_DENIED, this::create);
            assertEquals(
                    before, count("SELECT COUNT(*) FROM sys_user WHERE created_by=?", actor.id));
        } finally {
            db.update("UPDATE sys_role SET status=1 WHERE id=?", role);
            sign(ROLE_MENUS, role);
        }
    }

    @Test
    void missingActorGrantSignatureRefusesWithoutSupplement() {
        db.update(
                "DELETE FROM sys_data_signature WHERE data_type='USER_GRANTS' AND data_id=?",
                actor.id);
        expect(ErrorCode.INTEGRITY_FAILURE, this::create);
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM sys_data_signature WHERE data_type='USER_GRANTS' AND"
                            + " data_id=?",
                        actor.id));
    }

    @Test
    void statusWriteSignsCurrentIdentityAndInvalidatesOldSession() {
        String old = "Bearer " + jwt.issue(target.id, 1),
                grants = signature(USER_GRANTS, target.id);
        users.changeStatus(
                bearer, target.id.toString(), new UserRequests.Status(2, prefix + " disable"));
        assertEquals(2, mapper.findById(target.id).sessionVersion);
        signVerify(USER_IDENTITY, target.id);
        assertEquals(grants, signature(USER_GRANTS, target.id));
        expect(
                ErrorCode.INVALID_TOKEN,
                () ->
                        tx.executeWithoutResult(
                                status -> app.getBean(PermissionService.class).currentUser(old)));
    }

    @Test
    void statusAuditFailureRollsBackIdentitySignatureAndEvidence() {
        String before = signature(USER_IDENTITY, target.id);
        failure.fail = true;
        expect(
                ErrorCode.AUDIT_UNAVAILABLE,
                () ->
                        users.changeStatus(
                                bearer,
                                target.id.toString(),
                                new UserRequests.Status(2, "rollback")));
        assertEquals(1, mapper.findById(target.id).status);
        assertEquals(before, signature(USER_IDENTITY, target.id));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM sys_operation_log WHERE operation_type='US04_STATUS'"
                            + " AND target_id=?",
                        target.id));
    }

    @Test
    void resetPasswordSignsNewHashAndSessionAndPreservesEmptyGrants() {
        String old = "Bearer " + jwt.issue(target.id, 1),
                grants = signature(USER_GRANTS, target.id);
        String code = resetCode();
        auth.changePassword(new Password(phone(target), code, nextPassword), false);
        signVerify(USER_IDENTITY, target.id);
        assertEquals(grants, signature(USER_GRANTS, target.id));
        assertEquals(2, mapper.findById(target.id).sessionVersion);
        expect(
                ErrorCode.INVALID_TOKEN,
                () ->
                        tx.executeWithoutResult(
                                status -> app.getBean(PermissionService.class).currentUser(old)));
        assertNotNull(
                auth.login(
                        new Login(LoginType.USERNAME, target.username, nextPassword),
                        "127.0.0.1",
                        "fixture"));
    }

    @Test
    void passwordAuditFailureRollsBackHashHistoryCodeAndSignature() {
        String code = resetCode(), before = signature(USER_IDENTITY, target.id);
        failure.fail = true;
        expect(
                ErrorCode.AUDIT_UNAVAILABLE,
                () -> auth.changePassword(new Password(phone(target), code, nextPassword), false));
        assertEquals(target.passwordHash, mapper.findById(target.id).passwordHash);
        assertEquals(before, signature(USER_IDENTITY, target.id));
        assertEquals(
                1, count("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?", target.id));
        assertEquals(
                1,
                count(
                        "SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=? AND consumed_at IS"
                            + " NULL",
                        target.phoneHash));
    }

    @Test
    void freezeAndUnfreezeSignWrittenDepartmentAndWholeOrganization() {
        var manager =
                new DepartmentCollaboration.Manager(actor.id.toString(), jwt.verifyBearer(bearer));
        try {
            departments.freeze(manager, leaf, true, prefix + " freeze");
            signVerify(DEPARTMENT, leaf);
            signVerify(ORGANIZATION, 0);
            departments.freeze(manager, leaf, false, prefix + " unfreeze");
            signVerify(DEPARTMENT, leaf);
            signVerify(ORGANIZATION, 0);
        } finally {
            restoreDepartment();
        }
    }

    @Test
    void freezeAuditFailureRollsBackDepartmentAndBothSignatures() {
        String before = signature(DEPARTMENT, leaf), whole = signature(ORGANIZATION, 0);
        failure.fail = true;
        var manager =
                new DepartmentCollaboration.Manager(actor.id.toString(), jwt.verifyBearer(bearer));
        expect(
                ErrorCode.AUDIT_UNAVAILABLE,
                () -> departments.freeze(manager, leaf, true, "rollback"));
        assertEquals(1, count("SELECT status FROM sys_dept WHERE id=?", leaf));
        assertEquals(before, signature(DEPARTMENT, leaf));
        assertEquals(whole, signature(ORGANIZATION, 0));
    }

    @Test
    void projectionMismatchCannotVerifyAnotherCurrentUser() {
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        tx.executeWithoutResult(
                                status ->
                                        data.verifyUsers(
                                                List.of(
                                                        Map.of(
                                                                "userId",
                                                                target.id.toString(),
                                                                "deptId",
                                                                Long.toString(leaf))))));
    }

    @Test
    void departmentUserProjectionVerifiesFullIdentityWithStringIds() {
        tx.executeWithoutResult(
                status ->
                        data.verifyUsers(
                                List.of(
                                        Map.of(
                                                "userId",
                                                target.id.toString(),
                                                "deptId",
                                                Long.toString(dept),
                                                "status",
                                                1))));
    }

    @Test
    void currentLockedUserCannotBeVerifiedAgainstOldRepeatableReadSnapshot() {
        var rr = new TransactionTemplate(tx.getTransactionManager());
        rr.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        var fresh = new TransactionTemplate(tx.getTransactionManager());
        fresh.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        rr.executeWithoutResult(
                                status -> {
                                    signatures.snapshot(
                                            USER_IDENTITY,
                                            target.id); // establish the old consistent snapshot
                                    fresh.executeWithoutResult(
                                            inner -> {
                                                db.update(
                                                        "UPDATE sys_user SET session_version=2"
                                                            + " WHERE id=?",
                                                        target.id);
                                                signatures.sign(USER_IDENTITY, target.id);
                                            });
                                    data.verifyIdentity(
                                            mapper.lockById(
                                                    target.id)); // current locking read is version
                                    // 2
                                }));
    }

    @Test
    void aSessionCapabilityRechecksLogoutInCallerTransaction() {
        assertEquals(actor.id.longValue(), jwt.verifyBearer(bearer).userId());
        tx.executeWithoutResult(
                status ->
                        assertEquals(
                                actor.id, auth.requireCurrentSession(jwt.verifyBearer(bearer)).id));
        auth.logout(bearer);
        expect(
                ErrorCode.INVALID_TOKEN,
                () ->
                        tx.executeWithoutResult(
                                status -> auth.requireCurrentSession(jwt.verifyBearer(bearer))));
    }

    @Test
    void logoutAuditFailureRollsBackRevocation() {
        String token = "Bearer " + jwt.issue(target.id, 1), jti = jwt.verifyBearer(token).jti();
        failure.fail = true;
        expect(ErrorCode.AUDIT_UNAVAILABLE, () -> auth.logout(token));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_session_invalidation WHERE jti=?", jti));
    }

    @Test
    void verificationProvenanceDoesNotLeakIntoNextTransaction() {
        tx.executeWithoutResult(status -> data.verifyIdentity(mapper.lockById(target.id)));
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        tx.executeWithoutResult(
                                status -> data.signIdentity(mapper.lockById(target.id))));
    }

    UserAccount account(boolean admin) {
        String suffix = Integer.toString(++seq),
                phone =
                        "139"
                                + String.format(
                                        "%08d", Math.abs((prefix + suffix).hashCode()) % 100000000);
        long id =
                insert(
                        "INSERT INTO"
                            + " sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,created_by,temp_reason,password_initialized)"
                            + " VALUES(?,?,?,?,?,?,?,?,?,1)",
                        prefix + suffix,
                        codec.encrypt("张三"),
                        codec.encrypt(phone),
                        codec.phoneHash(phone),
                        app.getBean(PasswordHasher.class).hash(password),
                        dept,
                        admin ? 3 : 2,
                        admin ? null : actor.id,
                        admin ? null : prefix + " fixture");
        accounts.add(id);
        db.update(
                "INSERT INTO sys_password_history(user_id,password_hash) SELECT id,password_hash"
                    + " FROM sys_user WHERE id=?",
                id);
        if (admin) {
            db.update(
                    "INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,1,?,1)",
                    id,
                    dept);
            db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", id, role);
            db.update(
                    "INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref)"
                        + " VALUES(?,?,'MANUAL',?)",
                    id,
                    role,
                    prefix + suffix);
        }
        tx.executeWithoutResult(
                status -> {
                    signatures.sign(USER_IDENTITY, id);
                    signatures.sign(USER_GRANTS, id);
                    if (admin) {
                        signatures.sign(ADMIN, id);
                        signatures.sign(ADMIN_DELEGABLE_ROLES, id);
                    }
                });
        return mapper.findById(id);
    }

    UserService.Created create() {
        return users.createTemporary(
                bearer,
                new UserRequests.Temporary(
                        "张三",
                        "138"
                                + String.format(
                                        "%08d", Math.abs((prefix + ++seq).hashCode()) % 100000000),
                        Long.toString(dept),
                        prefix + " reason"));
    }

    AuthService.LoginResult login(UserAccount account) {
        return auth.login(
                new Login(LoginType.USERNAME, account.username, password), "127.0.0.1", "fixture");
    }

    String phone(UserAccount account) {
        return codec.decrypt(account.phone);
    }

    String resetCode() {
        auth.sendSms(new SendSms(phone(target), Purpose.RESET_PASSWORD));
        return sms.codes.get(phone(target) + Purpose.RESET_PASSWORD.name());
    }

    String evidence(String operation, long id) {
        return db.queryForObject(
                "SELECT evidence FROM sys_operation_log WHERE operation_type=? AND target_id=?"
                    + " ORDER BY id DESC LIMIT 1",
                String.class,
                operation,
                id);
    }

    void restoreDepartment() {
        var row =
                originalDepartments.stream()
                        .filter(d -> ((Number) d.get("id")).longValue() == leaf)
                        .findFirst()
                        .orElseThrow();
        db.update(
                "UPDATE sys_dept SET status=?,updated_at=? WHERE id=?",
                row.get("status"),
                row.get("updated_at"),
                leaf);
        sign(DEPARTMENT, leaf);
        sign(ORGANIZATION, 0);
    }

    void sign(SignedDataType type, long id) {
        tx.executeWithoutResult(status -> signatures.sign(type, id));
    }

    void signVerify(SignedDataType type, long id) {
        tx.executeWithoutResult(status -> signatures.verify(type, id));
    }

    String signature(SignedDataType type, long id) {
        return db.queryForObject(
                "SELECT signature FROM sys_data_signature WHERE data_type=? AND data_id=?",
                String.class,
                type.name(),
                id);
    }

    int count(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args);
    }

    long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(
                c -> {
                    var s = c.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
                    return s;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    static String encode(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    com.fasterxml.jackson.databind.JsonNode authHttp(
            String path, Map<String, Object> body, int expected) throws Exception {
        int port =
                ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext)
                                app)
                        .getWebServer()
                        .getPort();
        var request =
                java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(
                                        "http://127.0.0.1:" + port + "/api/v1/auth" + path))
                        .header("Content-Type", "application/json")
                        .POST(
                                java.net.http.HttpRequest.BodyPublishers.ofString(
                                        json.writeValueAsString(body)))
                        .build();
        var response =
                java.net.http.HttpClient.newHttpClient()
                        .send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        assertEquals(expected, response.statusCode());
        return json.readTree(response.body());
    }

    void assertOpaqueSmsResponse(com.fasterxml.jackson.databind.JsonNode response, String phone) {
        assertEquals("OK", response.path("code").asText());
        assertTrue(response.at("/data/accepted").asBoolean());
        assertEquals(60, response.at("/data/retryAfterSeconds").asInt());
        assertEquals(2, response.path("data").size());
        assertTrue(response.at("/data/code").isMissingNode());
        assertFalse(response.toString().contains(phone));
    }

    String localProof(Path inbox, String phone, String purpose) throws Exception {
        String digest =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-256")
                                        .digest(phone.getBytes(StandardCharsets.UTF_8)));
        var delivered = new Properties();
        try (var reader =
                Files.newBufferedReader(
                        inbox.resolve(digest + "-" + purpose + ".sms"), StandardCharsets.UTF_8)) {
            delivered.load(reader);
        }
        assertEquals(2, delivered.size());
        String code = delivered.getProperty("code");
        assertNotNull(code);
        assertTrue(code.matches("[0-9]{6}"));
        assertTrue(Instant.parse(delivered.getProperty("expiresAt")).isAfter(Instant.now()));
        return code;
    }

    static void expect(ErrorCode code, org.junit.jupiter.api.function.Executable work) {
        assertEquals(code, assertThrows(BusinessException.class, work).errorCode());
    }
}
