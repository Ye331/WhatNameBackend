package com.whatname.iam.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PermissionFixtureTest {
    @Test
    void standardScenarioHasAnInternallyConsistentAuthorizationPath() {
        PermissionFixture.Scenario fixture = PermissionFixture.standardScenario();

        assertEquals(PermissionFixture.APP_ID, fixture.serviceIdentity().applicationId());
        assertEquals("API_AUTH", fixture.serviceIdentity().allowedScope());
        assertEquals(2, fixture.departments().size());
        assertEquals(PermissionFixture.ROOT_DEPT_ID, fixture.departments().get(1).parentId());
        assertEquals(PermissionFixture.PRODUCT_DEPT_ID, fixture.users().get(0).departmentId());
        assertEquals(PermissionFixture.ROLE_ID, fixture.grantSources().get(0).roleId());
        assertTrue(fixture.grantSources().get(0).active());
        assertEquals(2, fixture.menus().get(1).type());
        assertEquals(PermissionFixture.ROLE_ID, fixture.roleMenus().get(0).roleId());
        assertEquals(PermissionFixture.PERMISSION_MENU_ID, fixture.menuApis().get(0).menuId());
        assertEquals(PermissionFixture.API_ID, fixture.menuApis().get(0).apiId());
        assertEquals(fixture.application().id(), fixture.apis().get(0).applicationId());
    }
}
