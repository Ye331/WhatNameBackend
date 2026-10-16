package com.whatname.iam.service.admin;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.admin.AdminData.Appointment;
import com.whatname.iam.mapper.admin.AdminMapper;
import java.util.List;
import java.util.stream.LongStream;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.whatname.iam.common.api.ErrorCode.*;

/** M5 checks for A/B/C write services. Caller supplies a verified actor and performs M6 function checks. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AdminPolicyService {
    private final AdminStateLoader loader;
    private final AdminRules rules;
    private final AdminPorts.Integrity integrity;
    private final AdminPorts.ManagementRoles managementRoles;
    public AdminPolicyService(AdminMapper mapper, AdminRules rules, AdminPorts.Integrity integrity,
                              AdminPorts.ManagementRoles managementRoles) {
        this.loader = new AdminStateLoader(mapper);
        this.rules = rules;
        this.integrity = integrity;
        this.managementRoles = managementRoles;
    }
    public Appointment requireAdministrator(long actorId) {
        return rules.requireActive(verified(actorId), actorId);
    }
    public void requireLevelOne(long actorId) {
        if (requireAdministrator(actorId).adminLevel() != 1) throw new BusinessException(PERMISSION_DENIED);
    }
    public void requireUserScope(long actorId, long userId) {
        var state = verified(actorId, userId);
        var actor = rules.requireActive(state, actorId);
        var user = state.users().get(userId);
        if (user == null || !rules.contains(state, actor, user.deptId())) throw new BusinessException(RESOURCE_NOT_FOUND);
    }
    public void requireDepartmentScope(long actorId, long departmentId) {
        var state = verified(actorId);
        var actor = rules.requireActive(state, actorId);
        if (!state.departments().containsKey(departmentId) || !rules.contains(state, actor, departmentId)) {
            throw new BusinessException(RESOURCE_NOT_FOUND);
        }
    }
    public void requireDelegableRole(long actorId, long roleId) {
        var state = verified(actorId);
        var role = state.roles().get(roleId);
        rules.checkRole(state, actorId, roleId, role != null && role.roleType() == 2
                && managementRoles.canDelegate(actorId, roleId));
    }
    /** One snapshot and one ordered user-lock acquisition for single-user grants and queries. */
    public AdminState requireGrantContext(long actorId, long userId, Long roleId, boolean granting, boolean lock) {
        var state = loader.load(lock, actorId, userId);
        integrity.verify(state);
        var actor = rules.requireActive(state, actorId);
        var user = state.users().get(userId);
        if (user == null || !rules.contains(state, actor, user.deptId())) throw new BusinessException(RESOURCE_NOT_FOUND);
        if (granting) rules.requireUsableUser(state, userId);
        if (roleId != null) {
            var role = state.roles().get(roleId);
            rules.checkRole(state, actorId, roleId, role != null && role.roleType() == 2
                    && managementRoles.canDelegate(actorId, roleId));
        }
        return state;
    }
    /** One consistent read for scope-filtered batch results; caller owns the read transaction. */
    public AdminState requireBatchReadContext(long actorId, List<Long> userIds) {
        var ids = LongStream.concat(LongStream.of(actorId), userIds.stream().mapToLong(Long::longValue)).distinct().toArray();
        var state = loader.load(false, ids);
        integrity.verify(state);
        rules.requireActive(state, actorId);
        return state;
    }
    /** A management context: all M5 tables, then the COMPLETE sorted actor/target user set.
     * Always locking/current reads, even for A's scoped reads; never a cached RR snapshot. */
    public AdminState requireManagementContext(long actorId, List<Long> targetUserIds) {
        var ids = LongStream.concat(LongStream.of(actorId), targetUserIds.stream().mapToLong(Long::longValue))
                .distinct().toArray();
        var state = loader.load(true, ids);
        integrity.verify(state);
        rules.requireActive(state, actorId);
        return state;
    }
    private AdminState verified(long... userIds) {
        var state = loader.load(true, userIds);
        integrity.verify(state);
        return state;
    }
}
