package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.admin.AdminData.Appointment;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

@Component
public class AdminRules {
    public Set<Long> organizationReview(AdminState before, AdminState after) {
        Set<Long> review = new java.util.TreeSet<>();
        for (var a : before.appointments().values()) {
            if (a.status() != 1) continue;
            var oldUser = before.users().get(a.userId());
            var user = after.users().get(a.userId());
            if (oldUser == null || user == null) fail(AUTH_STATE_UNAVAILABLE);
            boolean invalid =
                    mergedChain(after, a.deptId())
                            || mergedChain(after, user.deptId())
                            || !oldUser.deptId().equals(user.deptId());
            if (!invalid && a.adminLevel() > 1) {
                for (long departmentId : after.departments().keySet()) {
                    if (contains(after, a, departmentId)
                            && (!before.departments().containsKey(departmentId)
                                    || !contains(before, a, departmentId))) {
                        invalid = true;
                        break;
                    }
                }
            }
            if (!invalid && a.adminLevel() > 1)
                for (var member : after.users().values()) {
                    var oldMember = before.users().get(member.id());
                    if (contains(after, a, member.deptId())
                            && (oldMember == null || !contains(before, a, oldMember.deptId()))) {
                        invalid = true;
                        break;
                    }
                }
            if (invalid) review.add(a.userId());
        }
        return review;
    }

    private boolean mergedChain(AdminState state, long departmentId) {
        return departmentChain(state, departmentId, false).stream()
                .anyMatch(id -> state.departments().get(id).mergedIntoDeptId() != null);
    }

    public Appointment requireActive(AdminState state, long actorId) {
        requireUsableUser(state, actorId);
        var actor = state.appointments().get(actorId);
        if (actor == null || actor.status() != 1) fail(PERMISSION_DENIED);
        if (actor.adminLevel() < 1 || actor.delegableRevision() < 1) fail(AUTH_STATE_UNAVAILABLE);
        departmentChain(state, actor.deptId(), true);
        return actor;
    }

    public void requireUsableUser(AdminState state, long userId) {
        var user = state.users().get(userId);
        if (user == null || user.status() != 1) fail(PERMISSION_DENIED);
        departmentChain(state, user.deptId(), true);
    }

    public boolean contains(AdminState state, Appointment actor, long departmentId) {
        var chain = departmentChain(state, departmentId, false);
        departmentChain(state, actor.deptId(), false);
        return actor.adminLevel() == 1 || chain.contains(actor.deptId());
    }

    public void checkAppointment(AdminState state, long actorId, int level, long departmentId) {
        var actor = requireActive(state, actorId);
        if (level < actor.adminLevel()) fail(BUSINESS_RULE_VIOLATION);
        if (!contains(state, actor, departmentId)) fail(OUT_OF_SCOPE);
        departmentChain(state, departmentId, true);
    }

    public void checkRevocation(AdminState state, long actorId, long targetId) {
        var actor = requireActive(state, actorId);
        var target = state.appointments().get(targetId);
        if (target == null) fail(RESOURCE_NOT_FOUND);
        if (actorId == targetId) return;
        if (actor.adminLevel() > target.adminLevel()) fail(PERMISSION_DENIED);
        if (actor.adminLevel() == target.adminLevel() && !actor.deptId().equals(target.deptId()))
            fail(OUT_OF_SCOPE);
        if (!contains(state, actor, target.deptId())) fail(OUT_OF_SCOPE);
    }

    public void checkDelegationTarget(AdminState state, long actorId, long targetId) {
        var actor = requireActive(state, actorId);
        var target = state.appointments().get(targetId);
        if (target == null || target.status() != 1) fail(RESOURCE_NOT_FOUND);
        if (actor.adminLevel() >= target.adminLevel() || !contains(state, actor, target.deptId())) {
            fail(DELEGATION_OUT_OF_SCOPE);
        }
        requireActive(state, targetId);
    }

    /** 普通业务和管理角色共用委派规则；管理员等级不构成角色继承。 */
    public void checkRole(AdminState state, long actorId, long roleId) {
        var actor = requireActive(state, actorId);
        var role = state.roles().get(roleId);
        if (role == null || role.status() != 1 || role.roleType() < 1 || role.roleType() > 2)
            fail(ROLE_NOT_DELEGABLE);
        if (actor.adminLevel() > 1
                && state.delegations().stream()
                        .noneMatch(
                                item -> item.adminUserId() == actorId && item.roleId() == roleId))
            fail(ROLE_NOT_DELEGABLE);
    }

    private Set<Long> departmentChain(
            AdminState state, long departmentId, boolean requireUnfrozen) {
        Set<Long> visited = new HashSet<>();
        long current = departmentId;
        if (current <= 0) fail(AUTH_STATE_UNAVAILABLE);
        while (current != 0) {
            var department = state.departments().get(current);
            if (!visited.add(current) || department == null || department.parentId() < 0)
                fail(AUTH_STATE_UNAVAILABLE);
            if (requireUnfrozen
                    && (department.status() != 1 || department.mergedIntoDeptId() != null))
                fail(PERMISSION_DENIED);
            current = department.parentId();
        }
        return visited;
    }

    private static void fail(ErrorCode code) {
        throw new BusinessException(code);
    }
}
