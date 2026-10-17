package com.whatname.iam.service.admin;

import org.junit.jupiter.api.Test;
import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.admin.AdminRulesTest.rejects;
import static org.assertj.core.api.Assertions.*;

class AdminPolicyServiceTest {
    private final AdminTestFixture db = new AdminTestFixture();
    private final AdminPolicyService policy = new AdminPolicyService(db, new AdminRules(), db.integrity, (actor, role) -> false);

    @Test void validAppointmentAndLevelOneAreSeparateFromRoleOwnership() {
        assertThat(policy.requireAdministrator(2).adminLevel()).isEqualTo(2);
        assertThatCode(() -> policy.requireLevelOne(1)).doesNotThrowAnyException();
        rejects(PERMISSION_DENIED, () -> policy.requireLevelOne(2));
        rejects(PERMISSION_DENIED, () -> policy.requireAdministrator(6));
    }
    @Test void everyUserAndDepartmentTargetIsChecked() {
        assertThatCode(() -> policy.requireUserScope(2, 6)).doesNotThrowAnyException();
        assertThatCode(() -> policy.requireDepartmentScope(2, 22)).doesNotThrowAnyException();
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireUserScope(2, 5));
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireUserScope(2, 999));
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireDepartmentScope(2, 30));
        rejects(RESOURCE_NOT_FOUND, () -> policy.requireDepartmentScope(2, 999));
    }
    @Test void currentAppointmentAndDelegationChangesAffectNextCall() {
        assertThatCode(() -> policy.requireDelegableRole(2, 100)).doesNotThrowAnyException();
        db.deleteDelegation(2, 100);
        rejects(ROLE_NOT_DELEGABLE, () -> policy.requireDelegableRole(2, 100));
        db.revoke(2);
        rejects(PERMISSION_DENIED, () -> policy.requireUserScope(2, 6));
    }
}
