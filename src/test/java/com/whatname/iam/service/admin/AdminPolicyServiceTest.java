package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.admin.AdminRulesTest.rejects;

import static org.assertj.core.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AdminPolicyServiceTest {
    private final AdminTestFixture db = new AdminTestFixture();
    private final AdminPolicyService policy =
            new AdminPolicyService(db, new AdminRules(), db.integrity);

    @Test
    void validAppointmentAndLevelOneAreSeparateFromRoleOwnership() {
        assertThat(policy.requireAdministrator(2).adminLevel()).isEqualTo(2);
        assertThatCode(() -> policy.requireLevelOne(1)).doesNotThrowAnyException();
        rejects(PERMISSION_DENIED, () -> policy.requireLevelOne(2));
        rejects(PERMISSION_DENIED, () -> policy.requireAdministrator(6));
    }

    @Test
    void everyUserAndDepartmentTargetIsChecked() {
        assertThatCode(() -> policy.requireUserScope(2, 6)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireDepartmentScope(2, 22)).doesNotThrowAnyException();
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireUserScope(2, 5));
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireUserScope(2, 999));
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireDepartmentScope(2, 30));
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireDepartmentScope(2, 999));
    }

    @Test
    void currentAppointmentAndDelegationChangesAffectNextCall() {
        assertThatCode(() -> policy.requireDelegableRole(2, 100)).doesNotThrowAnyException();
        db.deleteDelegation(2, 100);
        rejects(ROLE_NOT_DELEGABLE, () -> policy.requireDelegableRole(2, 100));
        db.revoke(2);
        rejects(PERMISSION_DENIED, () -> policy.requireUserScope(2, 6));
    }

    @Test
    void mergedRootAndAncestorBlockManagementAndNewGrants() {
        db.departments.put(
                20L, new com.whatname.iam.entity.admin.AdminData.Department(20L, 10L, 1, 30L));
        rejects(PERMISSION_DENIED, () -> policy.requireAdministrator(2));
        rejects(PERMISSION_DENIED, () -> policy.requireAdministrator(3));
        rejects(PERMISSION_DENIED, () -> policy.requireGrantContext(1, 6, 100L, true, true));
        rejects(
                PERMISSION_DENIED,
                () ->
                        db.service()
                                .appoint(
                                        new com.whatname.iam.dto.admin.AdminDtos.AppointmentRequest(
                                                "6", 3, "20", "merged")));
        // Historical records remain visible to an eligible superior for review/revocation.
        assertThatCode(() -> policy.requireDepartmentScope(1, 20)).doesNotThrowAnyException();
    }

    @Test
    void frozenTargetIsLocatableButCannotReceiveNewGrant() {
        db.departments.put(
                20L, new com.whatname.iam.entity.admin.AdminData.Department(20L, 10L, 0));
        assertThatCode(() -> policy.requireDepartmentScope(1, 20)).doesNotThrowAnyException();
        rejects(PERMISSION_DENIED, () -> policy.requireGrantContext(1, 6, 100L, true, true));
    }

    @Test
    void manualRevokeStillRequiresCurrentRoleQualification() {
        db.roles.put(100L, new com.whatname.iam.entity.admin.AdminData.Role(100L, 1, 0));
        rejects(ROLE_NOT_DELEGABLE, () -> policy.requireGrantContext(1, 6, 100L, false, true));
        assertThatCode(() -> policy.requireDelegableRole(1, 200)).doesNotThrowAnyException();
        rejects(ROLE_NOT_DELEGABLE, () -> policy.requireDelegableRole(2, 200));
        rejects(ROLE_NOT_DELEGABLE, () -> policy.requireDelegableRole(1, 300));
    }
}
