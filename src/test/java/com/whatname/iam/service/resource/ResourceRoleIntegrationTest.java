package com.whatname.iam.service.resource;

import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.IamApplication;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.dto.auth.AuthRequests;
import com.whatname.iam.dto.resource.ResourceRequests.*;
import com.whatname.iam.dto.role.RoleRequests.*;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.PermissionService;
import com.whatname.iam.service.role.RoleService;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import java.util.*;

/** 资源和角色维护走真实的登录凭据、一级管理员资格和签名校验。 */
@SpringBootTest(classes = IamApplication.class, properties = "spring.config.import=")
@Testcontainers
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class ResourceRoleIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(
                            DockerImageName.parse(
                                            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("unified_permission")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw"))
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
        r.add("iam.security.jwt-issuer", () -> "resource-test");
        r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "resource-test");
        r.add("iam.security.data-signing.key-id", () -> "resource-test");
        r.add("iam.security.data-signing.private-key", () -> b64(ED.getPrivate().getEncoded()));
        r.add(
                "iam.security.data-signing.public-keys.resource-test",
                () -> b64(ED.getPublic().getEncoded()));
        r.add("iam.permission.management-app-code", () -> "CONSOLE");
    }

    @Autowired ResourceService resources;
    @Autowired RoleService roles;
    @Autowired JdbcTemplate db;
    @Autowired JwtTokenService jwt;
    @Autowired DataSignatureService signatures;
    @Autowired PlatformTransactionManager transactions;
    @Autowired AuthService auth;
    @Autowired PasswordHasher passwords;
    @Autowired PermissionService permissions;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    String bearer;
    static final String PASSWORD = "Integration123!";

    @BeforeEach
    void seedAdministrator() {
        if (db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE id=1", Integer.class) != 0) {
            bearer = login("resource-admin");
            return;
        }
        // 隔离的测试数据库，仅为测试建立初始管理员与 IAM 功能权限。
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.update(
                                    "INSERT INTO"
                                        + " sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                                        + " VALUES(1,0,'ROOT','Root','/1/')");
                            db.update(
                                    "INSERT INTO"
                                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                                        + " VALUES(1,'resource-admin','fixture',?,?,1,3,1,1)",
                                    "13800000000",
                                    passwords.hash(PASSWORD));
                            db.update(
                                    "INSERT INTO sys_admin(user_id,admin_level,dept_id,status)"
                                            + " VALUES(1,1,1,1)");
                            db.update(
                                    "INSERT INTO sys_application(id,app_code,app_name)"
                                            + " VALUES(1,'CONSOLE','IAM')");
                            db.update(
                                    "INSERT INTO sys_role(id,role_code,role_name,role_type)"
                                            + " VALUES(1,'RESOURCE_MANAGER','Manager',2)");
                            db.update(
                                    "INSERT INTO sys_user_role(user_id,role_id,granted_by)"
                                            + " VALUES(1,1,1)");
                            db.update(
                                    "INSERT INTO"
                                        + " sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by)"
                                        + " VALUES(1,1,'MANUAL','test-bootstrap',1)");
                            long menuId = 1;
                            for (String code :
                                    List.of(
                                            "IAM_RESOURCES_VIEW",
                                            "IAM_RESOURCES_WRITE",
                                            "IAM_ROLES_VIEW",
                                            "IAM_ROLES_WRITE",
                                            "IAM_GRANTS_WRITE",
                                            "IAM_GRANTS_VIEW",
                                            "IAM_USERS_VIEW")) {
                                db.update(
                                        "INSERT INTO"
                                            + " sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type)"
                                            + " VALUES(?,1,0,?, ?,2)",
                                        menuId,
                                        code,
                                        code);
                                db.update(
                                        "INSERT INTO sys_role_permission(role_id,menu_id)"
                                                + " VALUES(1,?)",
                                        menuId);
                                signatures.sign(RESOURCE_MENU, menuId);
                                signatures.sign(MENU_APIS, menuId++);
                            }
                            signatures.sign(ORGANIZATION, 0);
                            for (var type :
                                    List.of(
                                            DEPARTMENT,
                                            USER_IDENTITY,
                                            USER_GRANTS,
                                            ADMIN,
                                            ADMIN_DELEGABLE_ROLES,
                                            RESOURCE_APPLICATION,
                                            ROLE_MENUS)) {
                                signatures.sign(type, 1);
                            }
                        });
        bearer = login("resource-admin");
    }

    private String login(String username) {
        return "Bearer "
                + auth.login(
                                new AuthRequests.Login(
                                        AuthRequests.LoginType.USERNAME, username, PASSWORD),
                                "127.0.0.1",
                                "integration-test")
                        .accessToken();
    }

    @Test
    @DisplayName("自定义平台编码仍禁止停用，菜单查询返回本人等级且不要求管理员查看权限")
    void customPlatformAndOwnManagementUseTheSameIdentity() throws Exception {
        var request = new UpdateApplicationRequest();
        request.setStatus(0);
        request.setReason("test protected platform");
        business(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.updateApplication(bearer, 1, request));
        assertEquals(
                1,
                db.queryForObject("SELECT status FROM sys_application WHERE id=1", Integer.class));
        http.perform(
                        get("/api/v1/auth/menus")
                                .param("appCode", "CONSOLE")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.management.appointed").value(true))
                .andExpect(jsonPath("$.data.management.adminLevel").value(1));
    }

    @Test
    void loginGrantMenuApiAndRevokeUseTheSameProductionRules() throws Exception {
        String code = code();
        long app = application(code);
        long menu = menu(app, 0, code + "MENU", 2);
        long api = api(app, code + "READ", "GET", "/records/{id}");
        long role = role(code + "ROLE", 1);
        http.perform(get("/api/v1/admins/1/delegable-roles").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.data.roleIds",
                                org.hamcrest.Matchers.hasItem(Long.toString(role))));
        resources.replaceMenuApis(bearer, menu, apiMapping("v1", List.of(api)));
        roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(menu)));
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.update(
                                    "INSERT INTO"
                                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                                        + " VALUES(2,'business-user','fixture',?,?,1,3,1,1)",
                                    "13800000001",
                                    passwords.hash(PASSWORD));
                            signatures.sign(USER_IDENTITY, 2);
                            signatures.sign(USER_GRANTS, 2);
                        });
        String userBearer = login("business-user");
        assertEquals(
                false, permissions.check(userBearer, app, "GET", "/records/{id}").get("allowed"));
        var granted =
                http.perform(
                                post("/api/v1/grants/user-roles")
                                        .header("Authorization", bearer)
                                        .contentType("application/json")
                                        .content(
                                                json.writeValueAsString(
                                                        Map.of(
                                                                "userId",
                                                                "2",
                                                                "roleId",
                                                                Long.toString(role),
                                                                "sourceType",
                                                                "MANUAL",
                                                                "sourceRef",
                                                                code,
                                                                "reason",
                                                                "integration grant",
                                                                "expectedVersion",
                                                                "v1"))))
                        .andExpect(status().isOk())
                        .andReturn();
        var grant = json.readTree(granted.getResponse().getContentAsString()).path("data");
        assertEquals(
                true, permissions.check(userBearer, app, "GET", "/records/{id}").get("allowed"));
        assertFalse(((List<?>) permissions.ownMenus(userBearer, code).get("menus")).isEmpty());
        http.perform(
                        post("/api/v1/grants/user-roles/revoke")
                                .header("Authorization", bearer)
                                .contentType("application/json")
                                .content(
                                        json.writeValueAsString(
                                                Map.of(
                                                        "userId",
                                                        "2",
                                                        "roleId",
                                                        Long.toString(role),
                                                        "grantSourceId",
                                                        grant.path("grantSourceId").asText(),
                                                        "reason",
                                                        "integration revoke",
                                                        "expectedVersion",
                                                        grant.path("permissionRevision")
                                                                .asText()))))
                .andExpect(status().isOk());
        assertEquals(
                false, permissions.check(userBearer, app, "GET", "/records/{id}").get("allowed"));
        assertTrue(((List<?>) permissions.ownMenus(userBearer, code).get("menus")).isEmpty());
        assertEquals(
                0,
                db.queryForObject("SELECT COUNT(*) FROM sys_admin WHERE user_id=2", Integer.class));
    }

    @Test
    void loggedOutTokenCannotReadOrWriteResourcesAndRoles() {
        auth.logout(bearer);
        var page = new com.whatname.iam.dto.common.PageRequest();
        business(ErrorCode.INVALID_TOKEN, () -> resources.listApplications(bearer, page));
        business(ErrorCode.INVALID_TOKEN, () -> roles.listRoles(bearer, page));
        business(ErrorCode.INVALID_TOKEN, () -> application(code()));
        business(ErrorCode.INVALID_TOKEN, () -> role(code(), 1));
    }

    @Test
    @DisplayName("连续五次输错密码后，即使密码正确也不能立即登录")
    void fiveWrongPasswordsLockLogin() {
        createTestUser(10, "locked-user", null, false);
        for (int attempt = 0; attempt < 5; attempt++) {
            business(
                    ErrorCode.INVALID_CREDENTIALS,
                    () ->
                            auth.login(
                                    new AuthRequests.Login(
                                            AuthRequests.LoginType.USERNAME,
                                            "locked-user",
                                            "WrongPassword!"),
                                    "127.0.0.1",
                                    "integration-test"));
        }
        business(ErrorCode.INVALID_CREDENTIALS, () -> login("locked-user"));
        assertEquals(
                5,
                db.queryForObject(
                        "SELECT pwd_failed_count FROM sys_user WHERE id=10", Integer.class));
        assertNotNull(
                db.queryForObject(
                        "SELECT pwd_locked_until FROM sys_user WHERE id=10",
                        java.sql.Timestamp.class));
    }

    @Test
    @DisplayName("只有管理角色，没有管理员任命，不能管理资源或角色")
    void managementRoleAloneDoesNotPermitAdministration() {
        createTestUser(11, "role-only-user", null, true);
        bearer = login("role-only-user");
        assertManagementDenied();
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_admin WHERE user_id=11", Integer.class));
    }

    @Test
    @DisplayName("只有一级管理员任命，没有功能权限，仍不能管理资源或角色")
    void appointmentAloneDoesNotSupplyFunctionPermissions() {
        createTestUser(12, "appointment-only-user", 1, false);
        bearer = login("appointment-only-user");
        assertManagementDenied();
    }

    @Test
    @DisplayName("二级管理员有查询权限时可查询，但不能维护一级管理资源")
    void levelTwoCanReadButCannotWriteResourcesOrRoles() {
        createTestUser(13, "level-two-user", 2, true);
        bearer = login("level-two-user");
        var page = new com.whatname.iam.dto.common.PageRequest();
        assertNotNull(resources.listApplications(bearer, page));
        assertNotNull(roles.listRoles(bearer, page));
        assertWritesDenied();
    }

    @Test
    @DisplayName("会话版本更新后旧令牌失效，重新登录的令牌可正常访问")
    void sessionVersionChangeRejectsOldTokenButAllowsNewLogin() {
        createTestUser(14, "session-user", 1, true);
        bearer = login("session-user");
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.update(
                                    "UPDATE sys_user SET session_version=session_version+1 WHERE"
                                            + " id=14");
                            signatures.sign(USER_IDENTITY, 14);
                        });
        var page = new com.whatname.iam.dto.common.PageRequest();
        business(ErrorCode.INVALID_TOKEN, () -> resources.listApplications(bearer, page));
        business(ErrorCode.INVALID_TOKEN, () -> roles.listRoles(bearer, page));
        business(ErrorCode.INVALID_TOKEN, () -> application(code()));
        business(ErrorCode.INVALID_TOKEN, () -> role(code(), 1));
        bearer = login("session-user");
        assertNotNull(resources.listApplications(bearer, page));
        assertNotNull(roles.listRoles(bearer, page));
    }

    private void assertManagementDenied() {
        var page = new com.whatname.iam.dto.common.PageRequest();
        business(ErrorCode.PERMISSION_DENIED, () -> resources.listApplications(bearer, page));
        business(ErrorCode.PERMISSION_DENIED, () -> roles.listRoles(bearer, page));
        assertWritesDenied();
    }

    private void assertWritesDenied() {
        String appCode = code();
        String roleCode = code();
        business(ErrorCode.PERMISSION_DENIED, () -> application(appCode));
        business(ErrorCode.PERMISSION_DENIED, () -> role(roleCode, 1));
        // 拒绝请求之后，还要确认没有偷偷写入数据。
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_application WHERE app_code=?",
                        Integer.class,
                        appCode));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_role WHERE role_code=?",
                        Integer.class,
                        roleCode));
    }

    /** 每个场景使用独立用户；任命和功能角色分别准备，避免混淆两种资格。 */
    private void createTestUser(
            long id, String username, Integer adminLevel, boolean managementRole) {
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.update(
                                    "INSERT INTO"
                                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                                        + " VALUES(?,?,'fixture',?,?,1,3,1,1)",
                                    id,
                                    username,
                                    String.format("138%08d", id),
                                    passwords.hash(PASSWORD));
                            if (managementRole) {
                                db.update(
                                        "INSERT INTO sys_user_role(user_id,role_id,granted_by)"
                                                + " VALUES(?,1,1)",
                                        id);
                                db.update(
                                        "INSERT INTO"
                                            + " sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by)"
                                            + " VALUES(?,1,'MANUAL','test-bootstrap',1)",
                                        id);
                            }
                            if (adminLevel != null) {
                                db.update(
                                        "INSERT INTO sys_admin(user_id,admin_level,dept_id,status)"
                                                + " VALUES(?,?,1,1)",
                                        id,
                                        adminLevel);
                                signatures.sign(ADMIN, id);
                                signatures.sign(ADMIN_DELEGABLE_ROLES, id);
                            }
                            signatures.sign(USER_IDENTITY, id);
                            signatures.sign(USER_GRANTS, id);
                        });
    }

    @Test
    void addingResourcesCannotRepairATamperedApplicationSignature() {
        String code = code();
        long app = application(code);
        db.update("UPDATE sys_application SET status=0 WHERE id=?", app);
        business(ErrorCode.INTEGRITY_FAILURE, () -> menu(app, 0, code + "MENU", 2));
        business(ErrorCode.INTEGRITY_FAILURE, () -> api(app, code + "READ", "GET", "/test"));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_menu WHERE app_id=?", Integer.class, app));
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_api WHERE app_id=?", Integer.class, app));
    }

    @Test
    void applicationAndMenuCreationPersistAndRejectDuplicateOrCycle() throws Exception {
        String code = code();
        long app = application(code);
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);

        assertEquals(
                code,
                db.queryForObject(
                        "SELECT app_code FROM sys_application WHERE id=?", String.class, app));
        assertEquals(2, resources.listMenus(bearer, app).menus().size());
        http.perform(get("/api/v1/applications/" + app + "/menus").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menus[0].menuName").value("Test menu"))
                .andExpect(jsonPath("$.data.menus[0].menuCode").value(code + "DIR"))
                .andExpect(jsonPath("$.data.menus[0].path").value("/test"));
        assertEquals(
                directory,
                db.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, item));
        business(ErrorCode.DUPLICATE_RESOURCE, () -> application(code));

        UpdateMenuRequest cycle = new UpdateMenuRequest();
        cycle.setParentId(item);
        cycle.setReason("test cycle");
        business(ErrorCode.MENU_TREE_CYCLE, () -> resources.updateMenu(bearer, directory, cycle));
        assertEquals(
                0L,
                db.queryForObject(
                        "SELECT parent_id FROM sys_menu WHERE id=?", Long.class, directory));
    }

    @Test
    void apiMappingEnforcesCanonicalRouteApplicationAndRevision() throws Exception {
        String code = code();
        long app = application(code);
        long otherApp = application(code + "X");
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);
        long api = api(app, code + "GET", "GET", "/records/{id}");
        long otherApi = api(otherApp, code + "OTHER", "GET", "/other/{id}");
        http.perform(get("/api/v1/applications/" + app + "/apis").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].apiName").value("Test API"));

        business(
                ErrorCode.DUPLICATE_RESOURCE,
                () -> api(app, code + "DUP", "GET", "/records/{recordId}"));
        long postApi = api(app, code + "POST", "POST", "/records/{id}");
        assertTrue(postApi > 0);

        business(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.replaceMenuApis(bearer, directory, apiMapping("v1", List.of(api))));
        business(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(otherApi))));
        assertEquals(
                "v2",
                resources
                        .replaceMenuApis(bearer, item, apiMapping("v1", List.of(api, api)))
                        .mappingVersion());
        assertEquals(List.of(api), resources.listMenuApis(bearer, item).apiIds());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_menu_api WHERE menu_id=?", Integer.class, item));
        business(
                ErrorCode.VERSION_CONFLICT,
                () -> resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(postApi))));
        assertEquals(List.of(api), resources.listMenuApis(bearer, item).apiIds());
    }

    @Test
    void roleMenusRejectDirectoryAndStaleRevisionWithoutChangingRows() {
        String code = code();
        long app = application(code);
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);
        long role = role(code + "ROLE", 1);

        business(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(directory))));
        assertEquals(
                "v2",
                roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(item, item)))
                        .permissionRevision());
        assertEquals(List.of(item), roles.listRoleMenus(bearer, role).menuIds());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_role_permission WHERE role_id=?",
                        Integer.class,
                        role));
        business(
                ErrorCode.VERSION_CONFLICT,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of())));
        assertEquals(List.of(item), roles.listRoleMenus(bearer, role).menuIds());
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> role(code + "SUPER", 3));
    }

    @Test
    @DisplayName("应用改名和停用分别生效，不能停用管理平台自身")
    void applicationUpdatesPreserveUnchangedFieldsAndProtectConsole() {
        long app = application(code());
        var rename = new UpdateApplicationRequest();
        rename.setAppName("  Renamed application  ");
        rename.setReason("rename");
        assertEquals(1, resources.updateApplication(bearer, app, rename).status());
        assertEquals(
                "Renamed application",
                db.queryForObject(
                        "SELECT app_name FROM sys_application WHERE id=?", String.class, app));
        var disable = new UpdateApplicationRequest();
        disable.setStatus(0);
        disable.setReason("disable");
        assertEquals(0, resources.updateApplication(bearer, app, disable).status());
        business(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.updateApplication(bearer, 1, disable));
        assertEquals(
                1,
                db.queryForObject("SELECT status FROM sys_application WHERE id=1", Integer.class));
        var page =
                resources.listApplications(bearer, new com.whatname.iam.dto.common.PageRequest());
        assertTrue(page.total() >= 2);
    }

    @Test
    @DisplayName("菜单可以改名、停用和移到根目录，移动后父子关系正确")
    void menuUpdatesAndReparentingPersist() {
        String code = code();
        long app = application(code);
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);
        var rename = new UpdateMenuRequest();
        rename.setMenuName("  Renamed menu  ");
        rename.setReason("rename");
        assertEquals(directory, resources.updateMenu(bearer, item, rename).parentId());
        var move = new UpdateMenuRequest();
        move.setParentId(0L);
        move.setStatus(0);
        move.setReason("move");
        var result = resources.updateMenu(bearer, item, move);
        assertEquals(0L, result.parentId());
        assertEquals(0, result.status());
        move.setParentId(directory);
        move.setStatus(1);
        assertEquals(directory, resources.updateMenu(bearer, item, move).parentId());
        assertEquals(
                "Renamed menu",
                db.queryForObject("SELECT menu_name FROM sys_menu WHERE id=?", String.class, item));
    }

    @Test
    @DisplayName("API 改名与停用保持路由不变，方法筛选支持规范化")
    void apiUpdatesAndMethodFilteringPersist() {
        long app = application(code());
        long api = api(app, code(), " get ", "/records");
        api(app, code(), "POST", "/records");
        var rename = new UpdateApiRequest();
        rename.setApiName("  Renamed API  ");
        rename.setReason("rename");
        assertEquals(1, resources.updateApi(bearer, api, rename).status());
        var disable = new UpdateApiRequest();
        disable.setStatus(0);
        disable.setReason("disable");
        assertEquals(0, resources.updateApi(bearer, api, disable).status());
        var page = new com.whatname.iam.dto.common.PageRequest();
        var filtered = resources.listApis(bearer, app, " get ", page);
        assertEquals(1, filtered.total());
        assertEquals("Renamed API", filtered.items().getFirst().apiName());
        assertEquals("/records", filtered.items().getFirst().apiPath());
        assertEquals(2, resources.listApis(bearer, app, null, page).total());
        business(ErrorCode.INVALID_ARGUMENT, () -> resources.listApis(bearer, app, "TRACE", page));
        business(ErrorCode.INVALID_ARGUMENT, () -> api(app, code(), null, "/missing-method"));
        business(ErrorCode.INVALID_ARGUMENT, () -> api(app, code(), "TRACE", "/invalid-method"));
    }

    @Test
    @DisplayName("角色改名和停用保留其他字段，重复编码不能创建")
    void roleUpdatesAndDuplicateCodes() {
        String code = code();
        long role = role(code, 2);
        var rename = new UpdateRoleRequest();
        rename.setRoleName("  Renamed role  ");
        rename.setReason("rename");
        assertEquals(1, roles.updateRole(bearer, role, rename).status());
        var disable = new UpdateRoleRequest();
        disable.setStatus(0);
        disable.setReason("disable");
        assertEquals(0, roles.updateRole(bearer, role, disable).status());
        var result = roles.getRole(bearer, role);
        assertEquals("Renamed role", result.roleName());
        assertEquals(2, result.roleType());
        business(ErrorCode.DUPLICATE_RESOURCE, () -> role(code.toLowerCase(), 1));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {-1, 2})
    @DisplayName("非法状态不能修改应用、菜单、API 或角色")
    void invalidStatusesLeaveAllEntitiesUnchanged(int invalid) {
        long app = application(code());
        long item = menu(app, 0, code(), 2);
        long api = api(app, code(), "GET", "/status");
        long role = role(code(), 1);
        var a = new UpdateApplicationRequest();
        a.setStatus(invalid);
        var m = new UpdateMenuRequest();
        m.setStatus(invalid);
        var i = new UpdateApiRequest();
        i.setStatus(invalid);
        var r = new UpdateRoleRequest();
        r.setStatus(invalid);
        business(ErrorCode.INVALID_ARGUMENT, () -> resources.updateApplication(bearer, app, a));
        business(ErrorCode.INVALID_ARGUMENT, () -> resources.updateMenu(bearer, item, m));
        business(ErrorCode.INVALID_ARGUMENT, () -> resources.updateApi(bearer, api, i));
        business(ErrorCode.INVALID_ARGUMENT, () -> roles.updateRole(bearer, role, r));
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT status FROM sys_application WHERE id=?", Integer.class, app));
        assertEquals(
                1,
                db.queryForObject("SELECT status FROM sys_menu WHERE id=?", Integer.class, item));
        assertEquals(
                1, db.queryForObject("SELECT status FROM sys_api WHERE id=?", Integer.class, api));
        assertEquals(1, roles.getRole(bearer, role).status());
    }

    @Test
    @DisplayName("不存在的资源返回明确错误，不创建映射")
    void missingResourcesAreRejected() {
        long missing = Long.MAX_VALUE;
        business(ErrorCode.RESOURCE_NOT_FOUND, () -> resources.listMenus(bearer, missing));
        business(ErrorCode.RESOURCE_NOT_FOUND, () -> resources.listMenuApis(bearer, missing));
        business(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> resources.updateApi(bearer, missing, new UpdateApiRequest()));
        business(ErrorCode.RESOURCE_NOT_FOUND, () -> roles.getRole(bearer, missing));
        long app = application(code());
        long item = menu(app, 0, code(), 2);
        long role = role(code(), 1);
        business(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(missing))));
        business(
                ErrorCode.RESOURCE_NOT_FOUND,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(missing))));
        assertEquals("v1", resources.listMenuApis(bearer, item).mappingVersion());
        assertEquals("v1", roles.listRoleMenus(bearer, role).permissionRevision());
        assertTrue(resources.listMenuApis(bearer, item).apiIds().isEmpty());
        assertTrue(roles.listRoleMenus(bearer, role).menuIds().isEmpty());
    }

    @Test
    @DisplayName("清空映射删除关联并更新版本，旧版本仍不能覆盖")
    void clearingMappingsDeletesRowsAndAdvancesVersion() {
        long app = application(code());
        long item = menu(app, 0, code(), 2);
        long api = api(app, code(), "GET", "/clear");
        long role = role(code(), 1);
        resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(api)));
        roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(item)));
        assertEquals(
                "v3",
                resources
                        .replaceMenuApis(bearer, item, apiMapping("v2", List.of()))
                        .mappingVersion());
        assertEquals(
                "v3",
                roles.replaceRoleMenus(bearer, role, roleMapping("v2", List.of()))
                        .permissionRevision());
        assertTrue(resources.listMenuApis(bearer, item).apiIds().isEmpty());
        assertTrue(roles.listRoleMenus(bearer, role).menuIds().isEmpty());
        business(
                ErrorCode.VERSION_CONFLICT,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v2", List.of(item))));
        business(
                ErrorCode.VERSION_CONFLICT,
                () -> resources.replaceMenuApis(bearer, item, apiMapping("v2", List.of(api))));
    }

    @Test
    @DisplayName("菜单拒绝跨应用父节点、自引用、负数父节点和重复编码")
    void invalidMenuParentsAndDuplicateCodesLeaveTreeUnchanged() {
        long app = application(code());
        long otherApp = application(code());
        String code = code();
        long item = menu(app, 0, code, 2);
        long other = menu(otherApp, 0, code(), 1);
        business(ErrorCode.DUPLICATE_RESOURCE, () -> menu(app, 0, code, 2));
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> menu(app, other, code(), 2));
        business(ErrorCode.INVALID_ARGUMENT, () -> menu(app, -1, code(), 2));
        business(ErrorCode.INVALID_ARGUMENT, () -> menu(app, 0, code(), 3));
        var move = new UpdateMenuRequest();
        move.setParentId(other);
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> resources.updateMenu(bearer, item, move));
        move.setParentId(item);
        business(ErrorCode.MENU_TREE_CYCLE, () -> resources.updateMenu(bearer, item, move));
        move.setParentId(-1L);
        business(ErrorCode.INVALID_ARGUMENT, () -> resources.updateMenu(bearer, item, move));
        assertEquals(
                0L,
                db.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, item));
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> resources.listMenuApis(bearer, other));
    }

    @Test
    @DisplayName("系统角色不能修改或配置菜单，非法角色类型不能创建")
    void protectedRoleCannotBeChanged() {
        long role = role(code(), 1);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx -> {
                            db.update("UPDATE sys_role SET role_type=3 WHERE id=?", role);
                            signatures.sign(ROLE_MENUS, role);
                        });
        var update = new UpdateRoleRequest();
        update.setStatus(0);
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> roles.updateRole(bearer, role, update));
        business(
                ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of())));
        business(ErrorCode.INVALID_ARGUMENT, () -> role(code(), 0));
        business(ErrorCode.INVALID_ARGUMENT, () -> role(code(), 4));
        assertEquals(1, roles.getRole(bearer, role).status());
        assertEquals("v1", roles.listRoleMenus(bearer, role).permissionRevision());
    }

    @Test
    @DisplayName("审计写入失败时，资源和角色映射、版本、签名一起回滚")
    void auditFailureRollsBackMappingsVersionsAndSignatures() {
        long app = application(code());
        long item = menu(app, 0, code(), 2);
        long api = api(app, code(), "GET", "/rollback");
        long role = role(code(), 1);
        // 仅拒绝本场景的两种审计记录；不影响已有数据，finally 中移除约束。
        db.execute(
                "ALTER TABLE sys_operation_log ADD CONSTRAINT reject_test_audit CHECK ("
                        + "NOT (operation_type='MENU_APIS_REPLACE' AND target_id="
                        + item
                        + ") AND "
                        + "NOT (operation_type='ROLE_MENUS_REPLACE' AND target_id="
                        + role
                        + "))");
        try {
            business(
                    ErrorCode.AUDIT_UNAVAILABLE,
                    () -> resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(api))));
            business(
                    ErrorCode.AUDIT_UNAVAILABLE,
                    () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(item))));
            assertTrue(resources.listMenuApis(bearer, item).apiIds().isEmpty());
            assertTrue(roles.listRoleMenus(bearer, role).menuIds().isEmpty());
            assertEquals("v1", resources.listMenuApis(bearer, item).mappingVersion());
            assertEquals("v1", roles.listRoleMenus(bearer, role).permissionRevision());
            new TransactionTemplate(transactions)
                    .executeWithoutResult(
                            tx -> {
                                signatures.verify(MENU_APIS, item);
                                signatures.verify(RESOURCE_MENU, item);
                                signatures.verify(RESOURCE_APPLICATION, app);
                                signatures.verify(ROLE_MENUS, role);
                            });
        } finally {
            db.execute("ALTER TABLE sys_operation_log DROP CHECK reject_test_audit");
        }
        // 故障解除后，原版本仍可正常重试。
        assertEquals(
                "v2",
                resources
                        .replaceMenuApis(bearer, item, apiMapping("v1", List.of(api)))
                        .mappingVersion());
        assertEquals(
                "v2",
                roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(item)))
                        .permissionRevision());
    }

    @Test
    @DisplayName("HTTP 修改请求只有原因、没有实际变更时返回输入错误")
    void emptyHttpUpdatesAreRejectedWithoutChangingData() throws Exception {
        long app = application(code());
        long item = menu(app, 0, code(), 2);
        long api = api(app, code(), "GET", "/empty-patch");
        long role = role(code(), 1);
        for (String path :
                List.of("applications/" + app, "menus/" + item, "apis/" + api, "roles/" + role)) {
            http.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .patch("/api/v1/" + path)
                                    .header("Authorization", bearer)
                                    .contentType("application/json")
                                    .content(
                                            json.writeValueAsString(
                                                    Map.of("reason", "no changes"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        assertEquals(
                "Test application",
                db.queryForObject(
                        "SELECT app_name FROM sys_application WHERE id=?", String.class, app));
        assertEquals(
                "Test menu",
                db.queryForObject("SELECT menu_name FROM sys_menu WHERE id=?", String.class, item));
        assertEquals(
                "Test API",
                db.queryForObject("SELECT api_name FROM sys_api WHERE id=?", String.class, api));
        assertEquals("Test role", roles.getRole(bearer, role).roleName());
    }

    private static String code() {
        return "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private long application(String code) {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setAppCode(code.toLowerCase());
        request.setAppName("Test application");
        request.setDescription("Isolated resource test");
        return resources.createApplication(bearer, request).appId();
    }

    private long menu(long app, long parent, String code, int type) {
        CreateMenuRequest request = new CreateMenuRequest();
        request.setMenuCode(code);
        request.setMenuName("Test menu");
        request.setMenuType(type);
        request.setParentId(parent);
        request.setPath("/test");
        return resources.createMenu(bearer, app, request).menuId();
    }

    private long api(long app, String code, String method, String path) {
        CreateApiRequest request = new CreateApiRequest();
        request.setApiCode(code);
        request.setApiName("Test API");
        request.setHttpMethod(method);
        request.setApiPath(path);
        return resources.createApi(bearer, app, request).apiId();
    }

    private long role(String code, int type) {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setRoleCode(code);
        request.setRoleName("Test role");
        request.setRoleType(type);
        request.setDescription("Isolated resource test");
        return roles.createRole(bearer, request).roleId();
    }

    private static ReplaceMenuApisRequest apiMapping(String version, List<Long> ids) {
        ReplaceMenuApisRequest request = new ReplaceMenuApisRequest();
        request.setExpectedVersion(version);
        request.setApiIds(ids);
        return request;
    }

    private static ReplaceRoleMenusRequest roleMapping(String version, List<Long> ids) {
        ReplaceRoleMenusRequest request = new ReplaceRoleMenusRequest();
        request.setExpectedVersion(version);
        request.setMenuIds(ids);
        return request;
    }

    private static void business(
            ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).errorCode());
    }
}
