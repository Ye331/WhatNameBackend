package com.whatname.iam.service.admin;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.admin.AdminData.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class AdminRulesTest {
    private final AdminRules rules = new AdminRules();
    private final AdminState state = new AdminState(
            Map.of(1L, new Appointment(1L, 1, 10L, 1, 1, null),
                   2L, new Appointment(2L, 2, 20L, 1, 1, 1L),
                   3L, new Appointment(3L, 3, 21L, 1, 1, 2L),
                   4L, new Appointment(4L, 2, 20L, 1, 1, 1L),
                   5L, new Appointment(5L, 2, 30L, 1, 1, 1L)),
            Map.of(10L, new Department(10L, 0L, 1),
                   20L, new Department(20L, 10L, 1),
                   21L, new Department(21L, 20L, 1),
                   30L, new Department(30L, 10L, 1)),
            Map.of(1L, new User(1L, 10L, 1), 2L, new User(2L, 20L, 1),
                   3L, new User(3L, 21L, 1), 4L, new User(4L, 20L, 1),
                   5L, new User(5L, 30L, 1)),
            Map.of(100L, new Role(100L, 1, 1), 200L, new Role(200L, 2, 1),
                   300L, new Role(300L, 3, 1), 400L, new Role(400L, 1, 0)),
            List.of(new Delegation(2L, 100L, 1L), new Delegation(3L, 100L, 2L)));

    @Test void levelOneCoversOtherCompanyButDepartmentAdminDoesNot() {
        assertThat(rules.contains(state, state.appointments().get(1L), 30L)).isTrue();
        assertThat(rules.contains(state, state.appointments().get(2L), 21L)).isTrue();
        assertThat(rules.contains(state, state.appointments().get(2L), 30L)).isFalse();
    }

    @Test void sameLevelAppointmentIsAllowedButPromotionAboveActorIsRejected() {
        assertThatCode(() -> rules.checkAppointment(state, 2L, 2, 21L)).doesNotThrowAnyException();
        rejects(ErrorCode.BUSINESS_RULE_VIOLATION, () -> rules.checkAppointment(state, 2L, 1, 21L));
    }

    @Test void appointmentCannotEscapeManagementRoot() {
        rejects(ErrorCode.OUT_OF_SCOPE, () -> rules.checkAppointment(state, 2L, 3, 30L));
    }

    @Test void selfRevocationAndSameRootPeerRevocationAreAllowed() {
        assertThatCode(() -> rules.checkRevocation(state, 2L, 2L)).doesNotThrowAnyException();
        assertThatCode(() -> rules.checkRevocation(state, 2L, 4L)).doesNotThrowAnyException();
        assertThatCode(() -> rules.checkRevocation(state, 2L, 3L)).doesNotThrowAnyException();
    }

    @Test void peerInAnotherRootCannotBeRevoked() {
        rejects(ErrorCode.OUT_OF_SCOPE, () -> rules.checkRevocation(state, 2L, 5L));
        rejects(ErrorCode.PERMISSION_DENIED, () -> rules.checkRevocation(state, 3L, 2L));
    }

    @Test void delegationRequiresStrictlyLowerLevelEvenForSameRootPeers() {
        assertThatCode(() -> rules.checkDelegationTarget(state, 2L, 3L)).doesNotThrowAnyException();
        rejects(ErrorCode.DELEGATION_OUT_OF_SCOPE, () -> rules.checkDelegationTarget(state, 2L, 4L));
        rejects(ErrorCode.DELEGATION_OUT_OF_SCOPE, () -> rules.checkDelegationTarget(state, 2L, 2L));
    }

    @Test void onlyCurrentDelegableRolesMayBePassedOn() {
        assertThatCode(() -> rules.checkRole(state, 2L, 100L, false)).doesNotThrowAnyException();
        rejects(ErrorCode.ROLE_NOT_DELEGABLE, () -> rules.checkRole(state, 4L, 100L, false));
        assertThatCode(() -> rules.checkRole(state, 1L, 100L, false)).doesNotThrowAnyException();
    }

    @Test void SuperDisabledAndUnconfirmedManagementRolesAreRejected() {
        for (long roleId : List.of(200L, 300L, 400L, 999L)) {
            rejects(ErrorCode.ROLE_NOT_DELEGABLE, () -> rules.checkRole(state, 1L, roleId, false));
        }
        assertThatCode(() -> rules.checkRole(state, 1L, 200L, true)).doesNotThrowAnyException();
        rejects(ErrorCode.ROLE_NOT_DELEGABLE, () -> rules.checkRole(state, 1L, 300L, true));
    }

    @Test void revokedAppointmentCannotBeUsed() {
        var revoked = new AdminState(Map.of(2L, new Appointment(2L, 2, 20L, 0, 1, 1L)),
                state.departments(), state.users(), state.roles(), state.delegations());
        rejects(ErrorCode.PERMISSION_DENIED, () -> rules.requireActive(revoked, 2L));
    }

    @Test void frozenAncestorOrDisabledAccountBlocksManagement() {
        var frozen = new AdminState(state.appointments(),
                Map.of(10L, new Department(10L, 0L, 0), 20L, new Department(20L, 10L, 1)),
                state.users(), state.roles(), state.delegations());
        rejects(ErrorCode.PERMISSION_DENIED, () -> rules.requireActive(frozen, 2L));
        var disabled = new AdminState(state.appointments(), state.departments(),
                Map.of(2L, new User(2L, 20L, 2)), state.roles(), state.delegations());
        rejects(ErrorCode.PERMISSION_DENIED, () -> rules.requireActive(disabled, 2L));
    }

    @Test void missingOrCyclicDepartmentTreeFailsClosed() {
        var broken = new AdminState(state.appointments(),
                Map.of(20L, new Department(20L, 21L, 1), 21L, new Department(21L, 20L, 1)),
                state.users(), state.roles(), state.delegations());
        rejects(ErrorCode.AUTH_STATE_UNAVAILABLE, () -> rules.requireActive(broken, 2L));
        rejects(ErrorCode.AUTH_STATE_UNAVAILABLE, () -> rules.requireActive(
                new AdminState(state.appointments(), Map.of(), state.users(), state.roles(), state.delegations()), 2L));
    }

    static void rejects(ErrorCode code, Runnable work) {
        assertThatThrownBy(work::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.errorCode()).isEqualTo(code));
    }
}
