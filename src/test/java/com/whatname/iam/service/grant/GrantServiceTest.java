package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;

import static org.assertj.core.api.Assertions.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.entity.admin.AdminData.*;
import com.whatname.iam.entity.grant.GrantData.*;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

class GrantServiceTest {
    private final GrantTestFixture db = new GrantTestFixture();
    private final GrantService service = db.service();

    static GrantRequest grant(String ref, String version) {
        return new GrantRequest("6", "100", "MANUAL", ref, null, "test", version);
    }

    static RevokeRequest revoke(String source, String version) {
        return new RevokeRequest("6", "100", source, "test", version);
    }

    static void rejects(ErrorCode code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(
                        BusinessException.class, e -> assertThat(e.errorCode()).isEqualTo(code));
    }

    @Test
    void grantCreatesSourceEffectiveRelationRevisionAndEvidence() {
        var result = service.grant(grant("WORK-1", "v1"));
        assertThat(result.grantSourceId()).isEqualTo("901");
        assertThat(result.permissionRevision()).isEqualTo("v2");
        assertThat(result.roleEffective()).isTrue();
        assertThat(service.roles("6").roles()).containsExactly(new RoleView("100", true, 1));
        assertThat(db.events).hasSize(1);
        assertThat(db.events.getFirst().before().sources()).isEmpty();
        assertThat(db.events.getFirst().after().sources()).hasSize(1);
        assertThat(db.signed.getFirst().grantRevision()).isEqualTo(2);
    }

    @Test
    void partialRevokePreservesIndependentSourceAndLastRevokeRetainsHistory() {
        var first = service.grant(grant("WORK-1", "v1"));
        var second =
                service.grant(
                        new GrantRequest(
                                "6", "100", "DEPT_POSITION", "POST-1", "21", "test", "v2"));
        var partial = service.revoke(revoke(first.grantSourceId(), "v3"));
        assertThat(partial.remainingActiveSources()).isEqualTo(1);
        assertThat(partial.roleEffective()).isTrue();
        var last = service.revoke(revoke(second.grantSourceId(), "v4"));
        assertThat(last.remainingActiveSources()).isZero();
        assertThat(last.roleEffective()).isFalse();
        assertThat(service.roles("6").roles()).isEmpty();
        assertThat(service.sources("6", "100").sources()).hasSize(2).allMatch(s -> !s.active());
        assertThat(service.sources("6", "100").grantRevision()).isEqualTo("v5");
        assertThat(db.signed.getLast().effectiveRoles()).isEmpty();
    }

    @Test
    void currentVersionDuplicateIsNoOpButOldVersionAlwaysConflicts() {
        var first = service.grant(grant("WORK-1", "v1"));
        rejects(VERSION_CONFLICT, () -> service.grant(grant("WORK-1", "v1")));
        assertThat(service.grant(grant("WORK-1", "v2")).grantSourceId())
                .isEqualTo(first.grantSourceId());
        assertThat(db.events).hasSize(1);
        service.revoke(revoke(first.grantSourceId(), "v2"));
        service.revoke(revoke(first.grantSourceId(), "v3"));
        assertThat(db.events).hasSize(2);
        assertThat(db.revisions.get(6L)).isEqualTo(3);
        rejects(DUPLICATE_RESOURCE, () -> service.grant(grant("WORK-1", "v3")));
    }

    @Test
    void mismatchedSourceDoesNotTouchAnotherUserOrRole() {
        var source = service.grant(grant("WORK-1", "v1")).grantSourceId();
        rejects(
                GRANT_SOURCE_NOT_FOUND,
                () -> service.revoke(new RevokeRequest("5", "100", source, "test", "v1")));
        rejects(
                GRANT_SOURCE_NOT_FOUND,
                () -> service.revoke(new RevokeRequest("6", "101", source, "test", "v2")));
        assertThat(db.sources.getFirst().status()).isEqualTo(1);
    }

    @Test
    void positionsRequireCurrentDepartmentAndManualCannotClaimOne() {
        for (String dept : new String[] {null, "20"}) {
            rejects(
                    INVALID_ARGUMENT,
                    () ->
                            service.grant(
                                    new GrantRequest(
                                            "6",
                                            "100",
                                            "DEPT_POSITION",
                                            "POST",
                                            dept,
                                            "test",
                                            "v1")));
        }
        rejects(
                INVALID_ARGUMENT,
                () ->
                        service.grant(
                                new GrantRequest(
                                        "6", "100", "MANUAL", "WORK", "21", "test", "v1")));
        rejects(
                INVALID_ARGUMENT,
                () ->
                        service.grant(
                                new GrantRequest(
                                        "6", "100", "OA_APPROVAL", "OA", null, "test", "v1")));
        assertThat(db.sources).isEmpty();
    }

    @Test
    void sameStableKeyWithDifferentPositionDepartmentConflicts() {
        service.grant(new GrantRequest("6", "100", "DEPT_POSITION", "POST", "21", "test", "v1"));
        db.admins.users.put(6L, new User(6L, 22L, 1));
        rejects(
                IDEMPOTENCY_CONFLICT,
                () ->
                        service.grant(
                                new GrantRequest(
                                        "6", "100", "DEPT_POSITION", "POST", "22", "test", "v2")));
    }

    @Test
    void hiddenAndMissingTargetsCannotBeReadOrWritten() {
        db.admins.actorId = 2;
        for (String user : new String[] {"5", "999"}) {
            rejects(RESOURCE_NOT_FOUND, () -> service.roles(user));
            rejects(RESOURCE_NOT_FOUND, () -> service.sources(user, "100"));
            rejects(
                    RESOURCE_NOT_FOUND,
                    () ->
                            service.grant(
                                    new GrantRequest(
                                            user, "100", "MANUAL", "WORK", null, "test", "v1")));
        }
        assertThat(db.sources).isEmpty();
    }

    @Test
    void selfGrantAndRevokeStillRequireAppointmentAndDelegableList() {
        db.admins.actorId = 2;
        var result =
                service.grant(new GrantRequest("2", "100", "MANUAL", "SELF", null, "test", "v1"));
        db.admins.deleteDelegation(2, 100);
        rejects(
                ROLE_NOT_DELEGABLE,
                () ->
                        service.revoke(
                                new RevokeRequest(
                                        "2", "100", result.grantSourceId(), "test", "v2")));
        db.admins.insertDelegation(2, 100, 1);
        assertThat(
                        service.revoke(
                                        new RevokeRequest(
                                                "2", "100", result.grantSourceId(), "test", "v2"))
                                .roleEffective())
                .isFalse();
        db.admins.revoke(2);
        rejects(PERMISSION_DENIED, () -> service.roles("2"));
    }

    @Test
    void suspendedOrFrozenTargetCannotReceiveButCanLoseGrants() {
        var source = service.grant(grant("WORK", "v1")).grantSourceId();
        db.admins.users.put(6L, new User(6L, 21L, 0));
        rejects(PERMISSION_DENIED, () -> service.grant(grant("OTHER", "v2")));
        db.admins.users.put(6L, new User(6L, 21L, 1));
        db.admins.departments.put(21L, new Department(21L, 20L, 0));
        rejects(PERMISSION_DENIED, () -> service.grant(grant("OTHER", "v2")));
        assertThat(service.revoke(revoke(source, "v2")).roleEffective()).isFalse();
    }

    @Test
    void superUnknownAndDisabledRolesAreNotGrantable() {
        for (String role : new String[] {"300", "999"}) {
            rejects(
                    ROLE_NOT_DELEGABLE,
                    () ->
                            service.grant(
                                    new GrantRequest(
                                            "6", role, "MANUAL", "WORK", null, "test", "v1")));
        }
        db.admins.roles.put(100L, new Role(100L, 1, 0));
        rejects(ROLE_NOT_DELEGABLE, () -> service.grant(grant("WORK", "v1")));
    }

    @Test
    void grantingManagementRoleDoesNotAppointAnAdministrator() {
        assertThat(
                        service.grant(
                                        new GrantRequest(
                                                "6", "200", "MANUAL", "WORK", null, "管理功能授权", "v1"))
                                .roleEffective())
                .isTrue();
        assertThat(db.admins.appointments).doesNotContainKey(6L);
    }

    @Test
    void orphanRelationAndOrphanActiveSourceAreNeverSilentlyRepaired() {
        db.effective.add(new EffectiveRole(800L, 6L, 100L, 1L, LocalDateTime.now()));
        rejects(INTEGRITY_FAILURE, () -> service.grant(grant("WORK", "v1")));
        db.effective.clear();
        db.insertSource(6, 100, "MANUAL", "WORK", null, 1);
        rejects(INTEGRITY_FAILURE, () -> service.grant(grant("OTHER", "v1")));
        assertThat(db.revisions.get(6L)).isEqualTo(1);
    }

    @Test
    void integrityAndFunctionFailuresStopBeforeMutation() {
        db.admins.functions =
                (actor, permission) -> {
                    throw new BusinessException(PERMISSION_DENIED);
                };
        rejects(PERMISSION_DENIED, () -> service.grant(grant("WORK", "v1")));
        db.admins.functions = (actor, permission) -> {};
        db.integrity =
                new GrantIntegrity(null) {
                    public void verify(GrantState state) {
                        throw new BusinessException(INTEGRITY_FAILURE);
                    }

                    public void sign(GrantState state) {
                        throw new AssertionError("must not sign");
                    }
                };
        rejects(INTEGRITY_FAILURE, () -> service.grant(grant("WORK", "v1")));
        assertThat(db.sources).isEmpty();
    }

    @Test
    void revisionsUseBigintAndOverflowIsRejectedBeforeAnyWrite() {
        db.revisions.put(6L, 2147483648L);
        assertThat(service.grant(grant("WORK", "v2147483648")).permissionRevision())
                .isEqualTo("v2147483649");
        db.revisions.put(6L, Long.MAX_VALUE);
        rejects(
                AUTH_STATE_UNAVAILABLE,
                () -> service.grant(grant("OTHER", "v9223372036854775807")));
        assertThat(db.sources).hasSize(1);
        rejects(INVALID_ARGUMENT, () -> service.grant(grant("OTHER", "v9223372036854775808")));
    }

    @Test
    void readPermissionsAreIndependentAndEmptyCollectionsKeepRevision() {
        db.admins.functions =
                (actor, permission) -> {
                    if (!permission.equals("IAM_USERS_VIEW"))
                        throw new BusinessException(PERMISSION_DENIED);
                };
        assertThat(service.roles("6").grantRevision()).isEqualTo("v1");
        assertThat(service.roles("6").roles()).isEmpty();
        rejects(PERMISSION_DENIED, () -> service.sources("6", "100"));
        rejects(PERMISSION_DENIED, () -> service.grant(grant("WORK", "v1")));
    }
}
