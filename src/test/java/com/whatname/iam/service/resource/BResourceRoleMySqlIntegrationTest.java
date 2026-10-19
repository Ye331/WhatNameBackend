package com.whatname.iam.service.resource;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.resource.ResourceRequests.*;
import com.whatname.iam.dto.role.RoleRequests.*;
import com.whatname.iam.service.role.RoleService;
import com.whatname.iam.support.LocalTestDatabase;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in tests for B's real MySQL mappers and transaction-backed services. */
@EnabledIfEnvironmentVariable(named = "B_DB_TEST", matches = "true")
class BResourceRoleMySqlIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @MapperScan({"com.whatname.iam.mapper.resource", "com.whatname.iam.mapper.role"})
    @Import({ResourceService.class, RoleService.class})
    static class TestApplication { }

    static ConfigurableApplicationContext context;
    static ResourceService resources;
    static RoleService roles;
    static JdbcTemplate db;

    @BeforeAll
    static void start() {
        SpringApplication app = new SpringApplication(TestApplication.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        context = app.run(LocalTestDatabase.arguments("--spring.main.banner-mode=off", "--logging.level.root=WARN"));
        resources = context.getBean(ResourceService.class);
        roles = context.getBean(RoleService.class);
        db = context.getBean(JdbcTemplate.class);
        assertEquals("MySQL", db.execute((org.springframework.jdbc.core.ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName()));
    }

    @AfterAll
    static void close() {
        if (context != null) context.close();
    }

    @Test
    void applicationAndMenuCreationPersistAndRejectDuplicateOrCycle() {
        String code = code();
        long app = application(code);
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);

        assertEquals(code, db.queryForObject("SELECT app_code FROM sys_application WHERE id=?", String.class, app));
        assertEquals(2, resources.listMenus(app).menus().size());
        assertEquals(directory, db.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, item));
        business(ErrorCode.DUPLICATE_RESOURCE, () -> application(code));

        UpdateMenuRequest cycle = new UpdateMenuRequest();
        cycle.setParentId(item);
        cycle.setReason("test cycle");
        business(ErrorCode.MENU_TREE_CYCLE, () -> resources.updateMenu(directory, cycle));
        assertEquals(0L, db.queryForObject("SELECT parent_id FROM sys_menu WHERE id=?", Long.class, directory));
    }

    @Test
    void apiMappingEnforcesCanonicalRouteApplicationAndRevision() {
        String code = code();
        long app = application(code);
        long otherApp = application(code + "X");
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);
        long api = api(app, code + "GET", "GET", "/records/{id}");
        long otherApi = api(otherApp, code + "OTHER", "GET", "/other/{id}");

        business(ErrorCode.DUPLICATE_RESOURCE,
                () -> api(app, code + "DUP", "GET", "/records/{recordId}"));
        long postApi = api(app, code + "POST", "POST", "/records/{id}");
        assertTrue(postApi > 0);

        business(ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.replaceMenuApis(directory, apiMapping("v1", List.of(api))));
        business(ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> resources.replaceMenuApis(item, apiMapping("v1", List.of(otherApi))));
        assertEquals("v2", resources.replaceMenuApis(item, apiMapping("v1", List.of(api, api))).mappingVersion());
        assertEquals(List.of(api), resources.listMenuApis(item).apiIds());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sys_menu_api WHERE menu_id=?", Integer.class, item));
        business(ErrorCode.VERSION_CONFLICT,
                () -> resources.replaceMenuApis(item, apiMapping("v1", List.of(postApi))));
        assertEquals(List.of(api), resources.listMenuApis(item).apiIds());
    }

    @Test
    void roleMenusRejectDirectoryAndStaleRevisionWithoutChangingRows() {
        String code = code();
        long app = application(code);
        long directory = menu(app, 0, code + "DIR", 1);
        long item = menu(app, directory, code + "ITEM", 2);
        long role = role(code + "ROLE", 1);

        business(ErrorCode.BUSINESS_RULE_VIOLATION,
                () -> roles.replaceRoleMenus(role, roleMapping("v1", List.of(directory))));
        assertEquals("v2", roles.replaceRoleMenus(role, roleMapping("v1", List.of(item, item))).permissionRevision());
        assertEquals(List.of(item), roles.listRoleMenus(role).menuIds());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM sys_role_permission WHERE role_id=?", Integer.class, role));
        business(ErrorCode.VERSION_CONFLICT,
                () -> roles.replaceRoleMenus(role, roleMapping("v1", List.of())));
        assertEquals(List.of(item), roles.listRoleMenus(role).menuIds());
        business(ErrorCode.BUSINESS_RULE_VIOLATION, () -> role(code + "SUPER", 3));
    }

    private static String code() {
        return "B" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
    }

    private static long application(String code) {
        CreateApplicationRequest request = new CreateApplicationRequest();
        request.setAppCode(code.toLowerCase());
        request.setAppName("Test application");
        request.setDescription("Isolated B test");
        return resources.createApplication(request).appId();
    }

    private static long menu(long app, long parent, String code, int type) {
        CreateMenuRequest request = new CreateMenuRequest();
        request.setMenuCode(code);
        request.setMenuName("Test menu");
        request.setMenuType(type);
        request.setParentId(parent);
        request.setPath("/test");
        return resources.createMenu(app, request).menuId();
    }

    private static long api(long app, String code, String method, String path) {
        CreateApiRequest request = new CreateApiRequest();
        request.setApiCode(code);
        request.setApiName("Test API");
        request.setHttpMethod(method);
        request.setApiPath(path);
        return resources.createApi(app, request).apiId();
    }

    private static long role(String code, int type) {
        CreateRoleRequest request = new CreateRoleRequest();
        request.setRoleCode(code);
        request.setRoleName("Test role");
        request.setRoleType(type);
        request.setDescription("Isolated B test");
        return roles.createRole(request).roleId();
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
