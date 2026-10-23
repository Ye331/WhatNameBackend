package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.repository.admin.AdminRepository;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.event.TrustedEventContext;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

import java.util.*;

/** 参与组织变更事务，复核受影响的管理范围；服务身份认证须在调用前完成。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AdminOrganizationLifecycle {
    private final Object key = new Object();
    private final AdminRepository repository;
    private final AdminStateLoader loader;
    private final AdminRules rules;
    private final AdminIntegrity integrity;
    private final ObjectProvider<AuditService> audit;

    public AdminOrganizationLifecycle(
            AdminRepository repository,
            AdminRules rules,
            AdminIntegrity integrity,
            ObjectProvider<AuditService> audit) {
        this.repository = repository;
        this.loader = new AdminStateLoader(repository);
        this.rules = rules;
        this.integrity = integrity;
        this.audit = audit;
    }

    /** 验证服务身份并锁定事件行后、读取组织或用户前调用；构造上下文不能代替认证。 */
    public void prepare(TrustedEventContext actor, String eventId) {
        prepare(actor, eventId, false);
    }

    /** 复用全局锁序，单独约束 HR 单人变更，避免混入普通组织变更。 */
    public void prepareHr(TrustedEventContext actor, String eventId) {
        prepare(actor, eventId, true);
    }

    private void prepare(TrustedEventContext actor, String eventId, boolean hr) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        java.sql.Connection.TRANSACTION_SERIALIZABLE)
                || TransactionSynchronizationManager.hasResource(key)) fail(AUTH_STATE_UNAVAILABLE);
        if (actor == null
                || !"HR".equals(actor.sourceSystem())
                || actor.clientId() == null
                || actor.clientId().isBlank()
                || actor.requestId() == null
                || actor.requestId().isBlank()
                || eventId == null
                || eventId.isBlank()
                || eventId.length() > 128) fail(INVALID_ARGUMENT);
        var before = loader.loadOrganization();
        integrity.verify(before);
        var pending = new Pending(before, actor, eventId, hr);
        TransactionSynchronizationManager.bindResource(key, pending);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    public void suspend() {
                        TransactionSynchronizationManager.unbindResource(key);
                    }

                    public void resume() {
                        TransactionSynchronizationManager.bindResource(key, pending);
                    }

                    public void beforeCommit(boolean readOnly) {
                        if (!pending.applied) fail(AUTH_STATE_UNAVAILABLE);
                        var finalState = loader.loadOrganization();
                        if (!finalState.equals(pending.after)) fail(AUTH_STATE_UNAVAILABLE);
                        // 组织和人员的最终签名完成后，业务结果才能一起提交。
                        integrity.verify(finalState);
                    }

                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(key);
                    }
                });
    }

    public void organizationChanged(Set<Long> departments, List<Map<String, Object>> movedUsers) {
        var pending = (Pending) TransactionSynchronizationManager.getResource(key);
        if (pending == null || pending.applied || pending.hr) fail(AUTH_STATE_UNAVAILABLE);
        var before = pending.before;
        var current = loader.loadOrganization();
        validateDelta(before, current, departments, movedUsers);
        review(pending, current, departments, Set.of());
    }

    /** HR 仅允许变更一名人员的部门、状态，或创建该人员。 */
    public void hrChanged(long userId) {
        var pending = (Pending) TransactionSynchronizationManager.getResource(key);
        if (pending == null || pending.applied || !pending.hr) fail(AUTH_STATE_UNAVAILABLE);
        var before = pending.before;
        var current = loader.loadOrganization();
        if (!before.departments().equals(current.departments())
                || !before.roles().equals(current.roles())
                || !before.appointments().equals(current.appointments())
                || !before.delegations().equals(current.delegations())
                || !current.users().containsKey(userId)) fail(AUTH_STATE_UNAVAILABLE);
        var othersBefore = new HashMap<>(before.users());
        othersBefore.remove(userId);
        var othersAfter = new HashMap<>(current.users());
        othersAfter.remove(userId);
        if (!othersBefore.equals(othersAfter)) fail(AUTH_STATE_UNAVAILABLE);
        var user = current.users().get(userId);
        var old = before.users().get(userId);
        Set<Long> departments = new TreeSet<>();
        departments.add(user.deptId());
        if (old != null) departments.add(old.deptId());
        review(pending, current, departments, user.status() == 3 ? Set.of(userId) : Set.of());
    }

    private void review(
            Pending pending, AdminState current, Set<Long> departments, Set<Long> forced) {
        var before = pending.before;
        var revoked = rules.organizationReview(before, current);
        revoked.addAll(forced);
        Set<Long> changed = new TreeSet<>();
        for (long id : revoked) {
            var appointment = before.appointments().get(id);
            if (appointment != null && appointment.status() == 1) {
                expectOne(repository.revoke(id));
                changed.add(id);
            }
        }

        // Lists are configured directly by level-one administrators; there is no delegation chain.
        for (long id : revoked) {
            if (before.appointments().containsKey(id) && repository.deleteDelegations(id) > 0)
                changed.add(id);
        }
        for (long id : changed) {
            int revision = before.appointments().get(id).delegableRevision();
            if (revision < 1 || revision == Integer.MAX_VALUE) fail(AUTH_STATE_UNAVAILABLE);
            expectOne(repository.advanceRevision(id, revision));
        }
        var after = loader.loadOrganization();
        integrity.sign(after, changed);
        var writer = audit.getIfAvailable();
        if (writer == null) fail(AUDIT_UNAVAILABLE);
        for (long id : changed)
            writer.change(
                    pending.hr ? "ADMIN_HR_REVIEW" : "ADMIN_ORG_REVIEW",
                    null,
                    "USER",
                    id,
                    pending.eventId,
                    Map.of(
                            "schema",
                            pending.hr ? "C_HR_ADMIN_REVIEW_V1" : "C_ORGANIZATION_REVIEW_V1",
                            "origin",
                            Map.of(
                                    "sourceSystem",
                                    pending.actor.sourceSystem(),
                                    "clientId",
                                    pending.actor.clientId(),
                                    "requestId",
                                    pending.actor.requestId(),
                                    "eventId",
                                    pending.eventId),
                            "requiresReappointment",
                            revoked.contains(id),
                            "affectedDepartmentIds",
                            departments.stream().sorted().toList(),
                            "before",
                            evidence(before, id),
                            "after",
                            evidence(after, id)));
        pending.after = after;
        pending.applied = true;
    }

    private void validateDelta(
            AdminState before,
            AdminState after,
            Set<Long> departments,
            List<Map<String, Object>> movedUsers) {
        if (departments == null
                || departments.isEmpty()
                || movedUsers == null
                || !after.departments().keySet().containsAll(before.departments().keySet())
                || !after.departments().keySet().containsAll(departments)
                || !before.appointments().equals(after.appointments())
                || !before.roles().equals(after.roles())
                || !before.delegations().equals(after.delegations())
                || !before.users().keySet().equals(after.users().keySet()))
            fail(AUTH_STATE_UNAVAILABLE);
        for (var d : after.departments().values())
            if (!d.equals(before.departments().get(d.id())) && !departments.contains(d.id()))
                fail(AUTH_STATE_UNAVAILABLE);
        Set<Long> declared = new HashSet<>();
        for (var row : movedUsers) {
            long id = number(row.get("id"));
            var old = before.users().get(id);
            if (old == null || !declared.add(id) || number(row.get("deptId")) != old.deptId())
                fail(AUTH_STATE_UNAVAILABLE);
        }
        Set<Long> actual = new HashSet<>();
        for (var u : after.users().values()) {
            var old = before.users().get(u.id());
            if (u.status() != old.status()) fail(AUTH_STATE_UNAVAILABLE);
            if (!u.deptId().equals(old.deptId())) actual.add(u.id());
        }
        if (!actual.equals(declared)) fail(AUTH_STATE_UNAVAILABLE);
    }

    private Map<String, Object> evidence(AdminState state, long id) {
        return ChangeEvidence.authority(
                new AdminState(
                        Map.of(id, state.appointments().get(id)),
                        state.departments(),
                        Map.of(id, state.users().get(id)),
                        Map.of(),
                        state.delegations().stream().filter(d -> d.adminUserId() == id).toList()));
    }

    private static long number(Object value) {
        try {
            return Long.parseLong(Objects.toString(value));
        } catch (NumberFormatException error) {
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        }
    }

    private static void expectOne(int count) {
        if (count != 1) fail(AUTH_STATE_UNAVAILABLE);
    }

    private static void fail(com.whatname.iam.common.api.ErrorCode code) {
        throw new BusinessException(code);
    }

    private static final class Pending {
        final AdminState before;
        final TrustedEventContext actor;
        final String eventId;
        final boolean hr;
        boolean applied;
        AdminState after;

        Pending(AdminState before, TrustedEventContext actor, String eventId, boolean hr) {
            this.before = before;
            this.actor = actor;
            this.eventId = eventId;
            this.hr = hr;
        }
    }
}
