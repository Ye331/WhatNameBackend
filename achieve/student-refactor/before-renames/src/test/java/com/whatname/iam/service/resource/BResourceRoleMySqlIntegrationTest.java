package com.whatname.iam.service.resource;

import com.whatname.iam.IamApplication;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.dto.resource.ResourceRequests.*;
import com.whatname.iam.dto.role.RoleRequests.*;
import com.whatname.iam.service.role.RoleService;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.dto.auth.AuthRequests;
import com.whatname.iam.service.permission.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import com.whatname.iam.service.permission.DataSignatureService;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;
import static com.whatname.iam.service.permission.SignedDataType.*;
import static org.junit.jupiter.api.Assertions.*;

/** B 的资源和角色流程通过真实 A 登录凭据、C 管理资格和 D 签名校验执行。 */
@SpringBootTest(classes = IamApplication.class, properties = "spring.config.import=")
@Testcontainers
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class BResourceRoleMySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("unified_permission").withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schema.sql");

    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519");
    static KeyPair keys(String algorithm) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("RSA")) generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }

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
        r.add("iam.security.data-signing.public-keys.resource-test", () -> b64(ED.getPublic().getEncoded()));
        r.add("iam.security.data-key", () -> b64(new byte[32]));
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
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path) VALUES(1,0,'ROOT','Root','/1/')");
            db.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized) VALUES(1,'resource-admin','fixture','fixture',?,?,1,3,1,1)", "0".repeat(64), passwords.hash(PASSWORD));
            db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(1,1,1,1)");
            db.update("INSERT INTO sys_application(id,app_code,app_name) VALUES(1,'CONSOLE','IAM')");
            db.update("INSERT INTO sys_role(id,role_code,role_name,role_type) VALUES(1,'RESOURCE_MANAGER','Manager',2)");
            db.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(1,1,1)");
            db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by) VALUES(1,1,'MANUAL','test-bootstrap',1)");
            long menuId = 1;
            for (String code : List.of("IAM_RESOURCES_VIEW", "IAM_RESOURCES_WRITE", "IAM_ROLES_VIEW", "IAM_ROLES_WRITE", "IAM_GRANTS_WRITE", "IAM_GRANTS_VIEW", "IAM_USERS_VIEW")) {
                db.update("INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type) VALUES(?,1,0,?, ?,2)", menuId, code, code);
                db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(1,?)", menuId);
                signatures.sign(RESOURCE_MENU, menuId);
                signatures.sign(MENU_APIS, menuId++);
            }
            signatures.sign(ORGANIZATION, 0);
            for (var type : List.of(DEPARTMENT, USER_IDENTITY, USER_GRANTS, ADMIN, ADMIN_DELEGABLE_ROLES, RESOURCE_APPLICATION, ROLE_MENUS)) {
                signatures.sign(type, 1);
            }
        });
        bearer = login("resource-admin");
    }

    private String login(String username) {
        return "Bearer " + auth.login(new AuthRequests.Login(AuthRequests.LoginType.USERNAME,
                username, PASSWORD), "127.0.0.1", "integration-test").accessToken();
    }

    @Test
    @DisplayName("自定义平台编码仍禁止停用，菜单查询返回本人等级且不要求管理员查看权限")
    void customPlatformAndOwnManagementUseTheSameIdentity() throws Exception {
        var request = new UpdateApplicationRequest();
        request.setStatus(0);
        request.setReason("test protected platform");
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> resources.updateApplication(bearer, 1, request));
        assertEquals(1, db.queryForObject("SELECT status FROM sys_application WHERE id=1", Integer.class));
        http.perform(get("/api/v1/auth/menus").param("appCode", "CONSOLE").header("Authorization", bearer))
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
                .andExpect(jsonPath("$.data.roleIds", org.hamcrest.Matchers.hasItem(Long.toString(role))));
        resources.replaceMenuApis(bearer, menu, apiMapping("v1", List.of(api)));
        roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(menu)));
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized) VALUES(2,'business-user','fixture','fixture',?,?,1,3,1,1)",
                    "1".repeat(64), passwords.hash(PASSWORD));
            signatures.sign(USER_IDENTITY, 2);
            signatures.sign(USER_GRANTS, 2);
        });
        String userBearer = login("business-user");
        assertEquals(false, permissions.check(userBearer, app, "GET", "/records/{id}").get("allowed"));
        var granted = http.perform(post("/api/v1/grants/user-roles")
                .header("Authorization", bearer).contentType("application/json")
                .content(json.writeValueAsString(Map.of("userId", "2", "roleId", Long.toString(role),
                        "sourceType", "MANUAL", "sourceRef", code, "reason", "integration grant", "expectedVersion", "v1"))))
                .andExpect(status().isOk()).andReturn();
        var grant = json.readTree(granted.getResponse().getContentAsString()).path("data");
        assertEquals(true, permissions.check(userBearer, app, "GET", "/records/{id}").get("allowed"));
        assertFalse(((List<?>) permissions.ownMenus(userBearer, code).get("menus")).isEmpty());
        http.perform(post("/api/v1/grants/user-roles/revoke")
                .header("Authorization", bearer).contentType("application/json")
                .content(json.writeValueAsString(Map.of("userId", "2", "roleId", Long.toString(role),
                        "grantSourceId", grant.path("grantSourceId").asText(), "reason", "integration revoke",
                        "expectedVersion", grant.path("permissionRevision").asText()))))
                .andExpect(status().isOk());
        assertEquals(false, permissions.check(userBearer, app, "GET", "/records/{id}").get("allowed"));
        assertTrue(((List<?>) permissions.ownMenus(userBearer, code).get("menus")).isEmpty());
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_admin WHERE user_id=2", Integer.class));
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
            business(ErrorCode.INVALID_CREDENTIALS, () -> auth.login(
                    new AuthRequests.Login(AuthRequests.LoginType.USERNAME, "locked-user", "WrongPassword!"),
                    "127.0.0.1", "integration-test"));
        }
        business(ErrorCode.INVALID_CREDENTIALS, () -> login("locked-user"));
        assertEquals(5, db.queryForObject("SELECT pwd_failed_count FROM sys_user WHERE id=10", Integer.class));
        assertNotNull(db.queryForObject("SELECT pwd_locked_until FROM sys_user WHERE id=10", java.sql.Timestamp.class));
    }

    @Test
    @DisplayName("只有管理角色，没有管理员任命，不能管理资源或角色")
    void managementRoleAloneDoesNotPermitAdministration() {
        createTestUser(11, "role-only-user", null, true);
        bearer = login("role-only-user");
        assertManagementDenied();
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_admin WHERE user_id=11", Integer.class));
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
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=14");
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
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_application WHERE app_code=?", Integer.class, appCode));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_code=?", Integer.class, roleCode));
    }

    /** 每个场景使用独立用户；任命和功能角色分别准备，避免混淆两种资格。 */
    private void createTestUser(long id, String username, Integer adminLevel, boolean managementRole) {
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            db.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized) VALUES(?,?,'fixture','fixture',?,?,1,3,1,1)",
                    id, username, String.format("%064x", id), passwords.hash(PASSWORD));
            if (managementRole) {
                db.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,1,1)", id);
                db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by) VALUES(?,1,'MANUAL','test-bootstrap',1)", id);
            }
            if (adminLevel != null) {
                db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,?,1,1)", id, adminLevel);
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
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_menu WHERE app_id=?", Integer.class, app));
        assertEquals(0, db.queryForObject("SELECT COUNT(*) FROM sys_api WHERE app_id=?", Integer.class, app));
    }

    @Test
    void applicationAndMenuCreationPersistAndRejectDuplicateOrCycle() throws Exception {
        String code = code();
        long app = application(code);
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);

        assertEquals(code, db.queryForObject("SELECT app_code FROM sys_application WHERE id=?", String.class, app));
        assertEquals(2, resources.listMenus(bearer, app).menus().size());
        http.perform(get("/api/v1/applications/" + app + "/menus").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menus[0].menuName").value("Test menu"))
                .andExpect(jsonPath("$.data.menus[0].menuCode").value(code + "DIR"))
                .andExpect(jsonPath("$.data.menus[0].path").value("/test"));
        assertEquals(directory, db.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, item));
        business(ErrorCode.DUPLICATE_RESOURCE, () -> application(code));

        UpdateMenuRequest cycle = new UpdateMenuRequest();
        cycle.setParentId(item);
        cycle.setReason("test cycle");
        business(ErrorCode.MENU_TREE_CYCLE, () -> resources.updateMenu(bearer, directory, cycle));
        assertEquals(0L, db.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, directory));
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
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].apiName").value("Test API"));

        business(ErrorCode.DUPLICATE_RESOURCE,
                () -> api(app, code + "DUP", "GET", "/records/{recordId}"));
        long postApi = api(app, code + "POST", "POST", "/records/{id}");
        assertTrue(postApi > 0);

        business(ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.replaceMenuApis(bearer, directory, apiMapping("v1", List.of(api))));
        business(ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(otherApi))));
        assertEquals("v2", resources.replaceMenuApis(bearer, item, apiMapping("v1", List.of(api, api))).mappingVersion());
        assertEquals(List.of(api), resources.listMenuApis(bearer, item).apiIds());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sys_menu_api WHERE menu_id=?", Integer.class, item));
        business(ErrorCode.VERSION_CONFLICT,
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

        business(ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(directory))));
        assertEquals("v2", roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of(item, item))).permissionRevision());
        assertEquals(List.of(item), roles.listRoleMenus(bearer, role).menuIds());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=?", Integer.class, role));
        business(ErrorCode.VERSION_CONFLICT,
                () -> roles.replaceRoleMenus(bearer, role, roleMapping("v1", List.of())));
        assertEquals(List.of(item), roles.listRoleMenus(bearer, role).menuIds());
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> role(code + "SUPER", 3));
    }

    private static String code() {
        return "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private long application(String code) {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setAppCode(code.toLowerCase());
        request.setAppName("Test application");
        request.setDescription("Isolated B test");
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
        request.setDescription("Isolated B test");
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

    private static void business(ErrorCode expected, org.junit.jupiter.api.function.Executable action) {
        assertEquals(expected, assertThrows(BusinessException.class, action).errorCode());
    }
}
