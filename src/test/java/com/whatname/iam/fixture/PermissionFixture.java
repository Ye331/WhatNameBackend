package com.whatname.iam.fixture;

import java.util.List;

/**
 * Deterministic, in-memory facts for D-module unit tests.
 *
 * <p>These values are not SQL seed data. They deliberately contain no password, private key,
 * database credential, or production identity, so loading the test class cannot affect an
 * application startup.</p>
 */
public final class PermissionFixture {
    public static final long APP_ID = 101L;
    public static final long ROOT_DEPT_ID = 201L;
    public static final long PRODUCT_DEPT_ID = 202L;
    public static final long USER_ID = 301L;
    public static final long ADMIN_USER_ID = 302L;
    public static final long ROLE_ID = 401L;
    public static final long DIRECTORY_MENU_ID = 501L;
    public static final long PERMISSION_MENU_ID = 502L;
    public static final long API_ID = 601L;
    public static final long SERVICE_IDENTITY_ID = 701L;

    private PermissionFixture() {
    }

    public static Scenario standardScenario() {
        Application application = new Application(APP_ID, "inventory", true);
        Department root = new Department(ROOT_DEPT_ID, 0L, "/201/", true);
        Department product = new Department(PRODUCT_DEPT_ID, ROOT_DEPT_ID, "/201/202/", true);
        User user = new User(USER_ID, PRODUCT_DEPT_ID, true, 3L, 5L);
        User admin = new User(ADMIN_USER_ID, ROOT_DEPT_ID, true, 1L, 1L);
        Role role = new Role(ROLE_ID, "INVENTORY_READ", true, 2);
        GrantSource grantSource = new GrantSource(801L, USER_ID, ROLE_ID, "MANUAL", "ticket-1001", true);
        Menu directory = new Menu(DIRECTORY_MENU_ID, APP_ID, 0L, 1, true, 1);
        Menu permissionMenu = new Menu(PERMISSION_MENU_ID, APP_ID, DIRECTORY_MENU_ID, 2, true, 4);
        Api api = new Api(API_ID, APP_ID, "GET", "/inventory/items", true);
        RoleMenu roleMenu = new RoleMenu(ROLE_ID, PERMISSION_MENU_ID);
        MenuApi menuApi = new MenuApi(PERMISSION_MENU_ID, API_ID);
        ServiceIdentity serviceIdentity = new ServiceIdentity(
                SERVICE_IDENTITY_ID, APP_ID, "inventory-backend", "test-key-1", "API_AUTH", true);

        return new Scenario(application, List.of(root, product), List.of(user, admin), role,
                List.of(grantSource), List.of(directory, permissionMenu), List.of(api),
                List.of(roleMenu), List.of(menuApi), serviceIdentity);
    }

    public record Scenario(Application application, List<Department> departments, List<User> users,
                           Role role, List<GrantSource> grantSources, List<Menu> menus,
                           List<Api> apis, List<RoleMenu> roleMenus, List<MenuApi> menuApis,
                           ServiceIdentity serviceIdentity) {
    }

    public record Application(long id, String code, boolean enabled) {
    }

    public record Department(long id, long parentId, String path, boolean enabled) {
    }

    public record User(long id, long departmentId, boolean enabled, long sessionVersion, long grantRevision) {
    }

    public record Role(long id, String code, boolean enabled, int menuRevision) {
    }

    public record GrantSource(long id, long userId, long roleId, String type, String reference, boolean active) {
    }

    public record Menu(long id, long applicationId, long parentId, int type, boolean enabled, int apiRevision) {
    }

    public record Api(long id, long applicationId, String method, String path, boolean enabled) {
    }

    public record RoleMenu(long roleId, long menuId) {
    }

    public record MenuApi(long menuId, long apiId) {
    }

    public record ServiceIdentity(long id, long applicationId, String clientId, String keyId,
                                  String allowedScope, boolean enabled) {
    }
}
