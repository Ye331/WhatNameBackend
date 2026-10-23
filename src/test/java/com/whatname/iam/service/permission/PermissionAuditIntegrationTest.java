package com.whatname.iam.service.permission;

import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import com.whatname.iam.common.exception.*;
import com.whatname.iam.common.security.*;
import com.whatname.iam.controller.audit.AuditController;
import com.whatname.iam.controller.auth.PermissionController;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.audit.*;
import com.whatname.iam.support.TestDatabase;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;
import java.net.http.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** 真实 MySQL/HTTP；只有服务认证和管理员验签是测试替身。 */
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PermissionAuditIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan("com.whatname.iam.entity")
    @EnableJpaRepositories({
        "com.whatname.iam.repository.permission",
        "com.whatname.iam.repository.audit",
        "com.whatname.iam.repository.admin"
    })
    @Import({
        DataSigningKeys.class,
        DataSignatureService.class,
        PermissionService.class,
        PermissionReadScope.class,
        UserPermissionService.class,
        AuditService.class,
        AuditQueryService.class,
        PermissionController.class,
        AuditController.class,
        GlobalExceptionHandler.class,
        JwtTokenService.class,
        AdminPolicyService.class,
        AdminRules.class
    })
    static class TestApplication {
        @Bean
        AtomicLong testApplicationId() {
            return new AtomicLong();
        }

        @Bean
        AuditFailure auditFailure() {
            return new AuditFailure();
        }

        @Bean
        ServiceApiCaller fixtureCaller(AtomicLong appId) {
            return new ServiceApiCaller(null) {
                @Override
                public long authenticatedApplication(
                        jakarta.servlet.http.HttpServletRequest request, byte[] body) {
                    if (!"fixture-only".equals(request.getHeader("X-Test-Service")))
                        throw new BusinessException(
                                com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE);
                    return appId.get();
                }
            };
        }

        @Bean
        AdminIntegrity testCReadAdapter(DataSignatureService signatures) {
            return new AdminIntegrity(null, null) {
                public void verify(AdminState state) {
                    signatures.verify(ORGANIZATION, 0);
                    state.users().keySet().forEach(id -> signatures.verify(USER_IDENTITY, id));
                    state.appointments().keySet().forEach(id -> signatures.verify(ADMIN, id));
                }

                public void sign(AdminState state, Set<Long> ids) {
                    throw new UnsupportedOperationException();
                }
            };
        }
    }

    @org.aspectj.lang.annotation.Aspect
    static class AuditFailure extends com.whatname.iam.support.RepositoryProbe {
        volatile boolean fail;

        public Object intercept(Invocation call) throws Throwable {
            if (fail
                    && call.id()
                            .equals("com.whatname.iam.repository.audit.AuditRepository.insert"))
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        "test-only audit outage");
            return call.proceed();
        }
    }

    ServletWebServerApplicationContext context;
    JdbcTemplate db;
    DataSignatureService signatures;
    PermissionService permissions;
    AuditService audit;
    DataSigningKeys signingKeys;
    JwtTokenService jwt;
    TransactionTemplate tx;
    ObjectMapper json;
    String base, prefix, token, adminToken, limitedToken;
    long app,
            root,
            dept,
            outside,
            user,
            admin,
            limited,
            outsideUser,
            role,
            adminRole,
            directory,
            menu,
            secondMenu,
            api;

    @BeforeAll
    void start() throws Exception {
        var rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        var jwtKeys = rsa.generateKeyPair();
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var settings =
                new ArrayList<String>(
                        List.of(
                                "--server.port=0",
                                "--server.address=127.0.0.1",
                                "--spring.main.banner-mode=off",
                                "--logging.level.root=WARN"));
        settings.add(
                "--iam.security.jwt-private-key=" + encoded(jwtKeys.getPrivate().getEncoded()));
        settings.add("--iam.security.jwt-public-key=" + encoded(jwtKeys.getPublic().getEncoded()));
        settings.add("--iam.security.jwt-key-id=test-jwt");
        settings.add("--iam.security.jwt-issuer=d-fixture");
        settings.add("--iam.security.jwt-audience=d-api");
        settings.add("--iam.security.data-signing.key-id=test-sign");
        settings.add(
                "--iam.security.data-signing.private-key="
                        + encoded(signing.getPrivate().getEncoded()));
        settings.add(
                "--iam.security.data-signing.public-keys.test-sign="
                        + encoded(signing.getPublic().getEncoded()));
        context =
                (ServletWebServerApplicationContext)
                        new SpringApplication(TestApplication.class)
                                .run(TestDatabase.arguments(settings.toArray(String[]::new)));
        db = context.getBean(JdbcTemplate.class);
        signatures = context.getBean(DataSignatureService.class);
        permissions = context.getBean(PermissionService.class);
        audit = context.getBean(AuditService.class);
        signingKeys = context.getBean(DataSigningKeys.class);
        jwt = context.getBean(JwtTokenService.class);
        json = context.getBean(ObjectMapper.class);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        base = "http://127.0.0.1:" + context.getWebServer().getPort() + "/api/v1";
        assertEquals(
                "MySQL",
                db.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>)
                                c -> c.getMetaData().getDatabaseProductName()));
    }

    @AfterAll
    void close() {
        if (context != null) context.close();
    }

    @BeforeEach
    void fixture() {
        prefix = "D" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        root =
                insert(
                        "INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path)"
                                + " VALUES(0,?,?, '/')",
                        prefix + "r",
                        "Root");
        db.update("UPDATE sys_dept SET dept_path=? WHERE id=?", "/" + root + "/", root);
        dept = department(root, "d");
        outside = department(root, "o");
        user = account(dept, "u");
        admin = account(root, "a");
        limited = account(dept, "l");
        outsideUser = account(outside, "x");
        db.update(
                "INSERT INTO sys_admin(user_id,admin_level,dept_id,status)"
                        + " VALUES(?,1,?,1),(?,2,?,1)",
                admin,
                root,
                limited,
                dept);
        app =
                insert(
                        "INSERT INTO sys_application(app_code,app_name) VALUES(?,?)",
                        prefix,
                        "Fixture");
        context.getBean(AtomicLong.class).set(app);
        directory = menu(0, "DIR", 1);
        menu = menu(directory, "VIEW", 2);
        secondMenu = menu(directory, "EDIT", 2);
        long usersMenu = menu(0, "IAM_USERS_VIEW", 2), auditMenu = menu(0, "IAM_AUDIT_VIEW", 2);
        role =
                insert(
                        "INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",
                        prefix + "role",
                        "Role");
        adminRole =
                insert(
                        "INSERT INTO sys_role(role_code,role_name,role_type) VALUES(?,?,2)",
                        prefix + "admin",
                        "Admin role");
        db.update(
                "INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?),(?,?),(?,?),(?,?)",
                role,
                menu,
                role,
                secondMenu,
                adminRole,
                usersMenu,
                adminRole,
                auditMenu);
        grant(user, role, "MANUAL", prefix + "s1");
        grant(admin, adminRole, "MANUAL", prefix + "s2");
        grant(limited, adminRole, "MANUAL", prefix + "s3");
        api =
                insert(
                        "INSERT INTO sys_api(app_id,api_code,api_name,api_path,http_method)"
                                + " VALUES(?,?,'Fixture','/records/{id}','GET')",
                        app,
                        prefix + "api");
        db.update(
                "INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?),(?,?)",
                menu,
                api,
                secondMenu,
                api);
        tx.executeWithoutResult(
                status -> {
                    for (long id : List.of(user, admin, limited, outsideUser)) {
                        signatures.sign(USER_IDENTITY, id);
                        signatures.sign(USER_GRANTS, id);
                    }
                    signatures.sign(ORGANIZATION, 0);
                    signatures.sign(ADMIN, admin);
                    signatures.sign(ADMIN, limited);
                    signatures.sign(ROLE_MENUS, role);
                    signatures.sign(ROLE_MENUS, adminRole);
                    signatures.sign(RESOURCE_APPLICATION, app);
                    signatures.sign(RESOURCE_API, api);
                    for (long id : List.of(directory, menu, secondMenu, usersMenu, auditMenu)) {
                        signatures.sign(RESOURCE_MENU, id);
                        signatures.sign(MENU_APIS, id);
                    }
                });
        token = "Bearer " + jwt.issue(user, 1);
        adminToken = "Bearer " + jwt.issue(admin, 1);
        limitedToken = "Bearer " + jwt.issue(limited, 1);
        // Use this fixture's application as the management application, without changing production
        // defaults.
        org.springframework.test.util.ReflectionTestUtils.setField(
                (Object)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                permissions),
                "managementApplication",
                prefix);
    }

    @Test
    void realHttpMenusIncludeAncestorsOnceButDirectoryIsNotAnAuthorization() throws Exception {
        var result = http("GET", "/auth/menus?appCode=" + prefix, null, token, false, 200);
        assertEquals(
                Set.of(Long.toString(directory), Long.toString(menu), Long.toString(secondMenu)),
                ids(result.at("/data/menus")));
        assertFalse(ids(result.at("/data/menus")).contains("0"));
        for (var item : result.at("/data/menus")) {
            assertTrue(item.path("menuType").isInt(), "menuType must be a numeric enum, not an ID string");
            assertTrue(Set.of(1, 2).contains(item.path("menuType").asInt()));
            assertTrue(item.path("menuId").isTextual(), "BIGINT IDs must remain strings");
        }
        assertTrue(check().at("/data/allowed").asBoolean());
        var first = check().path("data");
        var second = check().path("data");
        assertEquals(first.path("permissionVersion"), second.path("permissionVersion"));
        assertNotEquals(first.path("decisionId"), second.path("decisionId"));
    }

    @Test
    void canonicalTemplatesUnknownRoutesAndMethodsGiveExplicitDecisions() throws Exception {
        assertTrue(
                http(
                                "POST",
                                "/auth/check",
                                Map.of("httpMethod", "GET", "routeTemplate", "/records/{recordId}"),
                                token,
                                true,
                                200)
                        .at("/data/allowed")
                        .asBoolean());
        for (var request :
                List.of(
                        Map.of("httpMethod", "POST", "routeTemplate", "/records/{id}"),
                        Map.of("httpMethod", "GET", "routeTemplate", "/unknown")))
            assertFalse(
                    http("POST", "/auth/check", request, token, true, 200)
                            .at("/data/allowed")
                            .asBoolean());
        http(
                "POST",
                "/auth/check",
                Map.of("httpMethod", "GET", "routeTemplate", "//bad"),
                token,
                true,
                400);
        http(
                "POST",
                "/auth/check",
                Map.of("httpMethod", "get", "routeTemplate", "/records/{id}"),
                token,
                true,
                400);
    }

    @Test
    void displayedPermissionMenuAncestorNeverGrantsItsOwnApi() throws Exception {
        db.update("UPDATE sys_menu SET menu_type=2 WHERE id=?", directory);
        long parentApi =
                insert(
                        "INSERT INTO"
                            + " sys_api(app_id,api_code,api_name,api_path,canonical_path,http_method)"
                            + " VALUES(?,?,'Parent','/parent','/parent','GET')",
                        app,
                        prefix + "parent");
        db.update("INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?)", directory, parentApi);
        sign(RESOURCE_MENU, directory);
        sign(RESOURCE_API, parentApi);
        sign(RESOURCE_APPLICATION, app);
        sign(MENU_APIS, directory);
        var menus =
                http("GET", "/auth/menus?appCode=" + prefix, null, token, false, 200)
                        .at("/data/menus");
        for (var item : menus)
            if (item.path("menuId").asText().equals(Long.toString(directory)))
                assertTrue(item.path("navigationOnly").asBoolean());
        assertTrue(check().at("/data/allowed").asBoolean());
        assertFalse(
                http(
                                "POST",
                                "/auth/check",
                                Map.of("httpMethod", "GET", "routeTemplate", "/parent"),
                                token,
                                true,
                                200)
                        .at("/data/allowed")
                        .asBoolean());
    }

    @Test
    void noServiceCredentialOrJwtCanNeverBeReplacedByAnApplicationHeader() throws Exception {
        http("POST", "/auth/check", body(), token, false, 401);
        http("POST", "/auth/check", body(), null, true, 401);
        http("GET", "/auth/menus?appCode=" + prefix, null, "Bearer invalid", false, 401);
    }

    @Test
    void independentGrantSourcesSurviveUntilTheLastSourceIsRevoked() throws Exception {
        grant(user, role, "OA_APPROVAL", prefix + "oa");
        sign(USER_GRANTS, user);
        revokeSources("MANUAL");
        sign(USER_GRANTS, user);
        assertTrue(check().at("/data/allowed").asBoolean());
        revokeSources("OA_APPROVAL");
        db.update("DELETE FROM sys_user_role WHERE user_id=?", user);
        sign(USER_GRANTS, user);
        assertFalse(check().at("/data/allowed").asBoolean());
    }

    @Test
    void eachNewDecisionSeesCommittedRevocationEvenInsideAnOlderCallerTransaction() {
        tx.executeWithoutResult(
                status -> {
                    db.queryForObject(
                            "SELECT grant_revision FROM sys_user WHERE id=?", Long.class, user);
                    var separate =
                            new TransactionTemplate(
                                    context.getBean(PlatformTransactionManager.class));
                    separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    separate.executeWithoutResult(
                            other -> {
                                revokeSources("MANUAL");
                                db.update("DELETE FROM sys_user_role WHERE user_id=?", user);
                                signatures.sign(USER_GRANTS, user);
                            });
                    assertEquals(
                            false,
                            permissions.check(token, app, "GET", "/records/{id}").get("allowed"));
                });
    }

    @Test
    void freezingAncestorsEmptiesMenusAndDeniesApiWithoutChangingAccounts() throws Exception {
        db.update("UPDATE sys_dept SET status=0 WHERE id=?", root);
        sign(ORGANIZATION, 0);
        assertEquals(
                0,
                http("GET", "/auth/menus?appCode=" + prefix, null, token, false, 200)
                        .at("/data/menus")
                        .size());
        assertFalse(check().at("/data/allowed").asBoolean());
        assertEquals(
                1,
                db.queryForObject("SELECT status FROM sys_user WHERE id=?", Integer.class, user));
        db.update("UPDATE sys_dept SET status=1 WHERE id=?", root);
        sign(ORGANIZATION, 0);
        assertTrue(check().at("/data/allowed").asBoolean());
    }

    @Test
    void menuOrMappingLossCanUseAnotherMenuButDisabledDirectoryCannot() throws Exception {
        db.update("UPDATE sys_menu SET status=0 WHERE id=?", menu);
        sign(RESOURCE_MENU, menu);
        assertTrue(check().at("/data/allowed").asBoolean());
        db.update("UPDATE sys_menu SET status=0 WHERE id=?", directory);
        sign(RESOURCE_MENU, directory);
        assertFalse(check().at("/data/allowed").asBoolean());
        db.update("UPDATE sys_menu SET status=1 WHERE id=?", directory);
        sign(RESOURCE_MENU, directory);
        db.update("DELETE FROM sys_menu_api WHERE menu_id=?", secondMenu);
        sign(MENU_APIS, secondMenu);
        assertFalse(check().at("/data/allowed").asBoolean());
    }

    @Test
    void signedResourceAndAccountStatesCannotGrantDisabledAccess() throws Exception {
        db.update("UPDATE sys_api SET status=0 WHERE id=?", api);
        sign(RESOURCE_API, api);
        assertFalse(check().at("/data/allowed").asBoolean());
        db.update("UPDATE sys_api SET status=1 WHERE id=?", api);
        sign(RESOURCE_API, api);
        db.update("UPDATE sys_role SET status=0 WHERE id=?", role);
        sign(ROLE_MENUS, role);
        assertFalse(check().at("/data/allowed").asBoolean());
        db.update("UPDATE sys_role SET status=1 WHERE id=?", role);
        sign(ROLE_MENUS, role);
        db.update("UPDATE sys_application SET status=0 WHERE id=?", app);
        sign(RESOURCE_APPLICATION, app);
        assertFalse(check().at("/data/allowed").asBoolean());
        db.update("UPDATE sys_user SET status=2 WHERE id=?", user);
        sign(USER_IDENTITY, user);
        http("POST", "/auth/check", body(), token, true, 401);
    }

    @Test
    void unsignedIdentityOrMembershipEditsAndUnknownSigningKeysFailClosed() throws Exception {
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?", outside, user);
        assertEquals(
                "INTEGRITY_FAILURE",
                http("POST", "/auth/check", body(), token, true, 503).path("code").asText());
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?", dept, user);
        db.update("DELETE FROM sys_user_role WHERE user_id=?", user);
        http("POST", "/auth/check", body(), token, true, 503);
        sign(
                USER_GRANTS,
                user); // Even a signed relationship without a matching source is inconsistent.
        http("POST", "/auth/check", body(), token, true, 503);
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", user, role);
        sign(USER_GRANTS, user);
        String trusted = signingKeys.getPublicKeys().remove("test-sign");
        try {
            http("POST", "/auth/check", body(), token, true, 503);
        } finally {
            signingKeys.getPublicKeys().put("test-sign", trusted);
        }
    }

    @Test
    void deletedCollectionMembersAreDetectedInsteadOfSilentlyReducingTheEvidence()
            throws Exception {
        db.update("DELETE FROM sys_role_permission WHERE role_id=? AND menu_id=?", role, menu);
        http("POST", "/auth/check", body(), token, true, 503);
        sign(ROLE_MENUS, role);
        assertTrue(check().at("/data/allowed").asBoolean());
        db.update("DELETE FROM sys_menu_api WHERE menu_id=?", secondMenu);
        http("POST", "/auth/check", body(), token, true, 503);
    }

    @Test
    void revokedJtiAndSessionVersionRejectOldJwt() throws Exception {
        var principal = jwt.verifyBearer(token);
        db.update(
                "INSERT INTO sys_session_invalidation(user_id,jti,reason,expires_at)"
                        + " VALUES(?,?,'LOGOUT',DATE_ADD(NOW(),INTERVAL 1 HOUR))",
                user,
                principal.jti());
        http("POST", "/auth/check", body(), token, true, 401);
        token = "Bearer " + jwt.issue(user, 1);
        assertTrue(check().at("/data/allowed").asBoolean());
        db.update("UPDATE sys_user SET session_version=2 WHERE id=?", user);
        sign(USER_IDENTITY, user);
        http("POST", "/auth/check", body(), token, true, 401);
    }

    @Test
    void auditFailureCannotReturnAnAllowDecision() throws Exception {
        long before = db.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Long.class);
        context.getBean(AuditFailure.class).fail = true;
        try {
            assertEquals(
                    "AUDIT_UNAVAILABLE",
                    http("POST", "/auth/check", body(), token, true, 503).path("code").asText());
        } finally {
            context.getBean(AuditFailure.class).fail = false;
        }
        assertEquals(
                before, db.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Long.class));
        assertTrue(check().at("/data/allowed").asBoolean());
    }

    @Test
    void businessSignatureAndAuditRollbackTogetherAndRequireACallerTransaction() {
        String before =
                db.queryForObject(
                        "SELECT signature FROM sys_data_signature WHERE data_type='USER_IDENTITY'"
                                + " AND data_id=?",
                        String.class,
                        user);
        long logs = db.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Long.class);
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.executeWithoutResult(
                                status -> {
                                    db.update(
                                            "UPDATE sys_user SET session_version=9 WHERE id=?",
                                            user);
                                    signatures.sign(USER_IDENTITY, user);
                                    audit.change(
                                            "TEST_CHANGE",
                                            admin,
                                            "USER",
                                            user,
                                            null,
                                            Map.of("after", Map.of("sessionVersion", 9)));
                                    throw new IllegalStateException("force rollback");
                                }));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT session_version FROM sys_user WHERE id=?", Integer.class, user));
        assertEquals(
                before,
                db.queryForObject(
                        "SELECT signature FROM sys_data_signature WHERE data_type='USER_IDENTITY'"
                                + " AND data_id=?",
                        String.class,
                        user));
        assertEquals(logs, db.queryForObject("SELECT COUNT(*) FROM sys_operation_log", Long.class));
        tx.executeWithoutResult(status -> assertNotNull(signatures.verify(USER_IDENTITY, user)));
        assertThrows(
                org.springframework.transaction.IllegalTransactionStateException.class,
                () -> signatures.sign(USER_IDENTITY, user));
    }

    @Test
    void auditQueriesRetainHistoricalEvidenceAndHonorRealCDepartmentScope() throws Exception {
        var decision = check().path("data");
        long event =
                db.queryForObject(
                        "SELECT MAX(id) FROM sys_operation_log WHERE user_id=?", Long.class, user);
        revokeSources("MANUAL");
        db.update("DELETE FROM sys_user_role WHERE user_id=?", user);
        sign(USER_GRANTS, user);
        var historical =
                http("GET", "/audit/evidence/" + event, null, adminToken, false, 200).path("data");
        assertTrue(historical.path("integrityVerified").asBoolean());
        assertEquals(decision.path("decisionId"), historical.at("/evidence/decisionId"));
        assertFalse(historical.path("evidence").has("user_role"));
        assertFalse(historical.path("evidence").has("signatures"));
        var list =
                http("GET", "/audit/authorizations?userId=" + user, null, limitedToken, false, 200)
                        .at("/data/items");
        assertEquals(1, list.size());
        assertTrue(list.get(0).path("allowed").asBoolean());
        tx.executeWithoutResult(
                status -> {
                    audit.change(
                            "TEST_CHANGE",
                            admin,
                            "USER",
                            outsideUser,
                            "fixture-event",
                            Map.of("after", Map.of("status", 1)));
                    audit.login(user, 1, true, null);
                    audit.login(outsideUser, 2, false, "INVALID_CREDENTIALS");
                });
        long outsideEvent = db.queryForObject("SELECT MAX(id) FROM sys_operation_log", Long.class);
        http("GET", "/audit/evidence/" + outsideEvent, null, limitedToken, false, 404);
        assertEquals(
                0,
                http("GET", "/audit/operations", null, limitedToken, false, 200)
                        .at("/data/items")
                        .size());
        assertTrue(
                http("GET", "/audit/operations", null, adminToken, false, 200)
                                .at("/data/total")
                                .asLong()
                        > 0);
        assertEquals(
                1,
                http("GET", "/audit/logins", null, limitedToken, false, 200)
                        .at("/data/total")
                        .asLong());
        assertEquals(
                user + "",
                http(
                                "GET",
                                "/users/" + user + "/permissions?appCode=" + prefix,
                                null,
                                limitedToken,
                                false,
                                200)
                        .at("/data/userId")
                        .asText());
        http(
                "GET",
                "/users/" + outsideUser + "/permissions?appCode=" + prefix,
                null,
                limitedToken,
                false,
                404);
        http("GET", "/audit/logins?pageSize=501", null, adminToken, false, 400);
        http("GET", "/audit/evidence/0", null, adminToken, false, 400);
        String stored =
                db.queryForObject(
                        "SELECT evidence FROM sys_operation_log WHERE id=?", String.class, event);
        assertFalse(stored.contains("password"));
        assertFalse(stored.contains("ciphertext"));
        assertFalse(stored.contains(token));
    }

    @Test
    void historicalSealRejectsChangedEvidenceAndMissingFunctionCannotReadAudit() throws Exception {
        check();
        long event = db.queryForObject("SELECT MAX(id) FROM sys_operation_log", Long.class);
        db.update(
                "INSERT INTO"
                    + " sys_operation_log(user_id,request_id,operation_type,target_type,target_id,evidence,status)"
                    + " SELECT"
                    + " user_id,request_id,'TEST_CHANGE',target_type,target_id,JSON_SET(evidence,'$.decisionId','tampered'),1"
                    + " FROM sys_operation_log WHERE id=?",
                event);
        long altered = db.queryForObject("SELECT MAX(id) FROM sys_operation_log", Long.class);
        http("GET", "/audit/evidence/" + altered, null, adminToken, false, 503);
        http("GET", "/audit/operations", null, token, false, 403);
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE sys_operation_log SET operation_type='X' WHERE id=?",
                                event));
        assertThrows(
                org.springframework.dao.DataAccessException.class,
                () -> db.update("DELETE FROM sys_operation_log WHERE id=?", event));
    }

    private long department(long parent, String code) {
        long id =
                insert(
                        "INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path)"
                                + " VALUES(?,?,?,?)",
                        parent,
                        prefix + code,
                        code,
                        "/" + parent + "/");
        db.update("UPDATE sys_dept SET dept_path=? WHERE id=?", "/" + parent + "/" + id + "/", id);
        return id;
    }

    private long account(long department, String code) {
        return insert(
                "INSERT INTO"
                    + " sys_user(username,real_name,phone,password_hash,dept_id,source,password_initialized)"
                    + " VALUES(?,'fixture-cipher',?,'fixture-hash',?,3,1)",
                prefix + code,
                String.format("138%08d", Math.floorMod(UUID.randomUUID().getMostSignificantBits(), 100_000_000L)),
                department);
    }

    private long menu(long parent, String code, int type) {
        return insert(
                "INSERT INTO sys_menu(app_id,parent_id,menu_code,menu_name,menu_type,path)"
                        + " VALUES(?,?,?,?,?,?)",
                app,
                parent,
                code,
                code,
                type,
                "/" + code);
    }

    private void grant(long account, long roleId, String type, String reference) {
        db.update("INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(?,?)", account, roleId);
        db.update(
                "INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref)"
                        + " VALUES(?,?,?,?)",
                account,
                roleId,
                type,
                reference);
    }

    private void revokeSources(String type) {
        db.update(
                "UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE user_id=? AND"
                        + " source_type=? AND status=1",
                user,
                type);
    }

    private void sign(SignedDataType type, long id) {
        tx.executeWithoutResult(status -> signatures.sign(type, id));
    }

    private long insert(String sql, Object... args) {
        var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
        db.update(
                connection -> {
                    var statement = connection.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                    return statement;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    private Map<String, String> body() {
        return Map.of("httpMethod", "GET", "routeTemplate", "/records/{id}");
    }

    private JsonNode check() throws Exception {
        return http("POST", "/auth/check", body(), token, true, 200);
    }

    private Set<String> ids(JsonNode menus) {
        var values = new HashSet<String>();
        menus.forEach(menu -> values.add(menu.path("menuId").asText()));
        return values;
    }

    private String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    private JsonNode http(
            String method,
            String path,
            Object body,
            String authorization,
            boolean service,
            int status)
            throws Exception {
        var request =
                HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json");
        if (authorization != null) request.header("Authorization", authorization);
        if (service) request.header("X-Test-Service", "fixture-only");
        request.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response =
                HttpClient.newHttpClient()
                        .send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), method + " " + path + " " + response.body());
        return json.readTree(response.body());
    }
}
