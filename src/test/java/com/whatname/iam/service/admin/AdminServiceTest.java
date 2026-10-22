package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.admin.AdminRulesTest.rejects;

import static org.assertj.core.api.Assertions.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.admin.AdminDtos.*;
import com.whatname.iam.entity.admin.AdminData.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

class AdminServiceTest {
    private final AdminTestFixture db = new AdminTestFixture();
    private final AdminService service = db.service();

    @Test
    void levelOneCanSelectRolesWithoutAnUpstreamDelegationList() {
        assertThat(service.delegableRoles("1").roleIds()).containsExactly("100", "101", "200");
        assertThat(db.delegations).noneMatch(d -> d.adminUserId() == 1);
    }

    @Test
    void listFiltersBeforePaginationAndKeepsRevokedRecords() {
        db.actorId = 2;
        db.revoke(3);
        var page = service.list(null, 1, 2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.items()).extracting(Summary::userId).containsExactly("2", "3");
        assertThat(page.items().get(1).status()).isZero();
        assertThat(service.list(null, 2, 2).items())
                .extracting(Summary::userId)
                .containsExactly("4");
        rejects(RESOURCE_NOT_FOUND, () -> service.list("30", 1, 20));
    }

    @Test
    void appointPersistsOnlyAppointmentAndRecordsReason() {
        var result = service.appoint(new AppointmentRequest("6", 3, "21", "岗位任命"));
        assertThat(result).isEqualTo(new Summary("6", 3, "21", 1));
        assertThat(db.appointments.get(6L).appointedBy()).isEqualTo(1L);
        assertThat(db.events)
                .singleElement()
                .satisfies(
                        event -> {
                            assertThat(event.reason()).isEqualTo("岗位任命");
                            assertThat(event.changedAdminIds()).containsExactly(6L);
                        });
        assertThat(db.delegations).hasSize(6);
    }

    @Test
    void duplicateAppointmentDoesNotOverwriteExistingScope() {
        rejects(
                DUPLICATE_RESOURCE,
                () -> service.appoint(new AppointmentRequest("2", 3, "21", "重复")));
        assertThat(db.appointments.get(2L).deptId()).isEqualTo(20L);
        assertThat(db.events).isEmpty();
    }

    @Test
    void appointChecksTargetUserAsWellAsManagementRoot() {
        db.actorId = 2;
        rejects(
                RESOURCE_NOT_FOUND,
                () -> service.appoint(new AppointmentRequest("5", 3, "21", "跨部门")));
        rejects(
                RESOURCE_NOT_FOUND,
                () -> service.appoint(new AppointmentRequest("999", 3, "21", "不存在")));
    }

    @Test
    void selfRevocationIsImmediatelyVisibleAndKeepsDelegations() {
        db.actorId = 2;
        assertThat(service.revoke("2", new RevokeRequest("职责结束")).appointmentRevoked()).isTrue();
        assertThat(service.myScope().appointed()).isFalse();
        assertThat(service.myScope().scopeRootIds()).isEmpty();
        rejects(PERMISSION_DENIED, () -> service.list(null, 1, 20));
        assertThat(db.delegations).hasSize(6);
    }

    @Test
    void neverAppointedUserCanInspectOwnEmptyScope() {
        db.actorId = 6;
        assertThat(service.myScope()).isEqualTo(new Scope(false, null, null, List.of(), null));
    }

    @Test
    void readDelegationsAllowsSelfAndSuperiorButNotPeersOrOutsiders() {
        db.actorId = 2;
        assertThat(service.delegableRoles("2").roleIds()).containsExactly("100", "101");
        assertThat(service.delegableRoles("3").roleIds()).containsExactly("100", "101");
        rejects(RESOURCE_NOT_FOUND, () -> service.delegableRoles("5"));
        rejects(RESOURCE_NOT_FOUND, () -> service.delegableRoles("999"));
        db.actorId = 3;
        rejects(RESOURCE_NOT_FOUND, () -> service.delegableRoles("2"));
    }

    @Test
    void shrinkingRecursivelyRemovesOnlyMatchingConfiguredSources() {
        var result =
                service.replaceDelegableRoles(
                        "2", new DelegationRequest(List.of("101"), "v1", "缩减"));
        assertThat(result).isEqualTo(new DelegableRoles("2", List.of("101"), "v2"));
        assertThat(service.delegableRoles("3").roleIds()).containsExactly("101");
        assertThat(service.delegableRoles("4").roleIds()).isEmpty();
        assertThat(service.delegableRoles("5").roleIds()).containsExactly("100");
        assertThat(db.events)
                .singleElement()
                .satisfies(
                        event ->
                                assertThat(event.changedAdminIds())
                                        .containsExactlyInAnyOrder(2L, 3L, 4L));
        assertThat(db.appointments.get(3L).delegableRevision()).isEqualTo(2);
        assertThat(db.appointments.get(4L).delegableRevision()).isEqualTo(2);
        assertThat(db.appointments.get(5L).delegableRevision()).isEqualTo(1);
    }

    @Test
    void staleVersionDoesNotChangeAnyData() {
        var old = List.copyOf(db.delegations);
        rejects(
                VERSION_CONFLICT,
                () ->
                        service.replaceDelegableRoles(
                                "2", new DelegationRequest(List.of(), "v9", "冲突")));
        assertThat(db.delegations).isEqualTo(old);
        assertThat(db.events).isEmpty();
    }

    @Test
    void reappointmentClearsOldDelegationsAndAlsoShrinksDescendants() {
        db.revoke(2);
        service.appoint(new AppointmentRequest("2", 2, "20", "重新任命"));
        assertThat(service.delegableRoles("2").roleIds()).isEmpty();
        assertThat(service.delegableRoles("3").roleIds()).containsExactly("101");
        assertThat(service.delegableRoles("4").roleIds()).isEmpty();
        assertThat(db.appointments.get(2L).delegableRevision()).isEqualTo(2);
    }

    @Test
    void replacingWithEmptySetIsSupported() {
        assertThat(
                        service.replaceDelegableRoles(
                                        "2", new DelegationRequest(List.of(), "v1", "清空"))
                                .roleIds())
                .isEmpty();
        assertThat(db.appointments.get(2L).delegableRevision()).isEqualTo(2);
    }

    @Test
    void duplicateRolesAndMalformedIdsAndVersionsAreRejected() {
        rejects(
                INVALID_ARGUMENT,
                () ->
                        service.replaceDelegableRoles(
                                "2", new DelegationRequest(List.of("100", "100"), "v1", "重复")));
        rejects(
                INVALID_ARGUMENT,
                () ->
                        service.replaceDelegableRoles(
                                "2", new DelegationRequest(List.of(), "v2147483648", "溢出")));
        rejects(INVALID_ARGUMENT, () -> service.delegableRoles("9223372036854775808"));
        rejects(
                INVALID_ARGUMENT,
                () -> service.appoint(new AppointmentRequest("6", 65536, "21", "越界")));
        rejects(INVALID_ARGUMENT, () -> service.revoke("2", new RevokeRequest("  ")));
        rejects(INVALID_ARGUMENT, () -> service.list(null, 0, 20));
        rejects(INVALID_ARGUMENT, () -> service.list(null, 1, 501));
    }

    @Test
    void functionPermissionAndIntegrityFailuresRejectBeforeMutation() {
        db.functions =
                (actor, permission) -> {
                    throw new BusinessException(PERMISSION_DENIED);
                };
        rejects(PERMISSION_DENIED, () -> service.revoke("2", new RevokeRequest("不允许")));
        db.functions = (actor, permission) -> {};
        db.integrity =
                new AdminIntegrity(null, null) {
                    public void verify(AdminState state) {
                        throw new BusinessException(INTEGRITY_FAILURE);
                    }

                    public void sign(AdminState state, Set<Long> changed) {
                        throw new AssertionError("must not write");
                    }
                };
        rejects(INTEGRITY_FAILURE, () -> service.revoke("2", new RevokeRequest("签名异常")));
        assertThat(db.appointments.get(2L).status()).isEqualTo(1);
        assertThat(db.events).isEmpty();
    }

    @Test
    void superRolesNeverEnterAList() {
        for (String role : List.of("300")) {
            rejects(
                    ROLE_NOT_DELEGABLE,
                    () ->
                            service.replaceDelegableRoles(
                                    "2", new DelegationRequest(List.of(role), "v1", "禁止")));
        }
        assertThat(db.appointments.get(2L).delegableRevision()).isEqualTo(1);
    }

    @Test
    void managementRolesFollowExplicitDelegationWithoutCreatingAppointments() {
        service.replaceDelegableRoles("2", new DelegationRequest(List.of("200"), "v1", "委派管理角色"));
        db.actorId = 2;
        service.replaceDelegableRoles(
                "3",
                new DelegationRequest(
                        List.of("200"), "v" + db.appointments.get(3L).delegableRevision(), "向下委派"));
        assertThat(db.delegations).anyMatch(d -> d.adminUserId() == 3 && d.roleId() == 200);
        assertThat(db.appointments).doesNotContainKey(6L);
        db.actorId = 5;
        rejects(
                ROLE_NOT_DELEGABLE,
                () ->
                        new AdminRules()
                                .checkRole(
                                        new AdminState(
                                                db.appointments,
                                                db.departments,
                                                db.users,
                                                db.roles,
                                                db.delegations),
                                        5,
                                        200));
    }
}
