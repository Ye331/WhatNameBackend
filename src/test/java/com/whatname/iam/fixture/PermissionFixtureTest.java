package com.whatname.iam.fixture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PermissionFixtureTest {
    @Test
    void standardScenarioContainsConsistentAuthorizationReferencesAndEnabledFacts() {
        PermissionFixture.Scenario fixture = PermissionFixture.standardScenario();
        PermissionFixture.User user =
                fixture.users().stream()
                        .filter(candidate -> candidate.id() == PermissionFixture.USER_ID)
                        .findFirst()
                        .orElseThrow();
        PermissionFixture.Department userDepartment =
                fixture.departments().stream()
                        .filter(candidate -> candidate.id() == user.departmentId())
                        .findFirst()
                        .orElseThrow();
        PermissionFixture.Department parentDepartment =
                fixture.departments().stream()
                        .filter(candidate -> candidate.id() == userDepartment.parentId())
                        .findFirst()
                        .orElseThrow();
        PermissionFixture.GrantSource grantSource = fixture.grantSources().get(0);
        PermissionFixture.Role grantRole =
                fixture.roles().stream()
                        .filter(candidate -> candidate.id() == grantSource.roleId())
                        .findFirst()
                        .orElseThrow();
        PermissionFixture.RoleMenu roleMenu = fixture.roleMenus().get(0);
        PermissionFixture.Menu permissionMenu =
                fixture.menus().stream()
                        .filter(candidate -> candidate.id() == roleMenu.menuId())
                        .findFirst()
                        .orElseThrow();
        PermissionFixture.Menu directoryMenu =
                fixture.menus().stream()
                        .filter(candidate -> candidate.id() == permissionMenu.parentId())
                        .findFirst()
                        .orElseThrow();
        PermissionFixture.MenuApi menuApi = fixture.menuApis().get(0);
        PermissionFixture.Api api =
                fixture.apis().stream()
                        .filter(candidate -> candidate.id() == menuApi.apiId())
                        .findFirst()
                        .orElseThrow();

        assertTrue(fixture.application().enabled());
        assertTrue(fixture.users().stream().allMatch(PermissionFixture.User::enabled));
        assertTrue(fixture.departments().stream().allMatch(PermissionFixture.Department::enabled));
        assertTrue(fixture.roles().stream().allMatch(PermissionFixture.Role::enabled));
        assertTrue(fixture.menus().stream().allMatch(PermissionFixture.Menu::enabled));
        assertTrue(fixture.apis().stream().allMatch(PermissionFixture.Api::enabled));
        assertTrue(fixture.grantSources().stream().allMatch(PermissionFixture.GrantSource::active));

        assertEquals(user.id(), grantSource.userId());
        assertEquals(grantSource.roleId(), grantRole.id());
        assertEquals(grantRole.id(), roleMenu.roleId());
        assertEquals(permissionMenu.id(), roleMenu.menuId());
        assertEquals(2, permissionMenu.type());
        assertEquals(directoryMenu.id(), permissionMenu.parentId());
        assertEquals(1, directoryMenu.type());
        assertEquals(directoryMenu.applicationId(), permissionMenu.applicationId());
        assertEquals(permissionMenu.id(), menuApi.menuId());
        assertEquals(api.id(), menuApi.apiId());
        assertEquals(permissionMenu.applicationId(), api.applicationId());
        assertEquals(fixture.application().id(), api.applicationId());
        assertEquals(user.departmentId(), userDepartment.id());
        assertEquals(userDepartment.parentId(), parentDepartment.id());
        assertEquals(0L, parentDepartment.parentId());
        assertEquals(fixture.application().id(), fixture.serviceIdentity().applicationId());
        assertEquals("API_AUTH", fixture.serviceIdentity().allowedScope());
        assertTrue(fixture.serviceIdentity().enabled());
    }

    @Test
    void standardScenarioCanBeRepeatedWithoutSharingMutableScenarioState() {
        PermissionFixture.Scenario first = PermissionFixture.standardScenario();
        PermissionFixture.Scenario second = PermissionFixture.standardScenario();

        assertEquals(first, second);
        assertNotSame(first, second);
        assertNotSame(first.roles(), second.roles());
    }
}
