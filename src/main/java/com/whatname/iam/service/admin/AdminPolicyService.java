package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.admin.AdminData.Appointment;
import com.whatname.iam.mapper.admin.AdminMapper;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

/** 供 A/B/C 写入服务复用的 M5 校验；调用方须提供已验证的操作者，并执行 M6 功能权限检查。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AdminPolicyService {
    private final AdminStateLoader loader;
    private final AdminRules rules;
    private final AdminPorts.Integrity integrity;

    public AdminPolicyService(
            AdminMapper mapper, AdminRules rules, AdminPorts.Integrity integrity) {
        this.loader = new AdminStateLoader(mapper);
        this.rules = rules;
        this.integrity = integrity;
    }

    public Appointment requireAdministrator(long actorId) {
        return rules.requireActive(verified(actorId), actorId);
    }

    public Appointment requireAdministratorReadOnly(long actorId) {
        return rules.requireActive(verifiedReadOnly(actorId), actorId);
    }

    public void requireLevelOne(long actorId) {
        if (requireAdministrator(actorId).adminLevel() != 1)
            throw new BusinessException(PERMISSION_DENIED);
    }

    public void requireLevelOneReadOnly(long actorId) {
        if (requireAdministratorReadOnly(actorId).adminLevel() != 1)
            throw new BusinessException(PERMISSION_DENIED);
    }

    public void requireUserScope(long actorId, long userId) {
        var state = verified(actorId, userId);
        var actor = rules.requireActive(state, actorId);
        var user = state.users().get(userId);
        if (user == null || !rules.contains(state, actor, user.deptId()))
            throw new BusinessException(RESOURCE_NOT_FOUND);
    }

    public void requireUserScopeReadOnly(long actorId, long userId) {
        var state = verifiedReadOnly(actorId, userId);
        var actor = rules.requireActive(state, actorId);
        var user = state.users().get(userId);
        if (user == null || !rules.contains(state, actor, user.deptId()))
            throw new BusinessException(RESOURCE_NOT_FOUND);
    }

    public void requireDepartmentScope(long actorId, long departmentId) {
        var state = verified(actorId);
        var actor = rules.requireActive(state, actorId);
        if (!state.departments().containsKey(departmentId)
                || !rules.contains(state, actor, departmentId)) {
            throw new BusinessException(RESOURCE_NOT_FOUND);
        }
    }

    public void requireDepartmentScopeReadOnly(long actorId, long departmentId) {
        var state = verifiedReadOnly(actorId);
        var actor = rules.requireActive(state, actorId);
        if (!state.departments().containsKey(departmentId)
                || !rules.contains(state, actor, departmentId)) {
            throw new BusinessException(RESOURCE_NOT_FOUND);
        }
    }

    public void requireDelegableRole(long actorId, long roleId) {
        var state = verified(actorId);
        rules.checkRole(state, actorId, roleId);
    }

    public void requireDelegableRoleReadOnly(long actorId, long roleId) {
        var state = verifiedReadOnly(actorId);
        rules.checkRole(state, actorId, roleId);
    }

    /** 单人授权和查询共用一次状态读取；需要加锁时，按用户 ID 顺序一次取得相关用户锁。 */
    public AdminState requireGrantContext(
            long actorId, long userId, Long roleId, boolean granting, boolean lock) {
        var state = loader.load(lock, actorId, userId);
        integrity.verify(state);
        var actor = rules.requireActive(state, actorId);
        var user = state.users().get(userId);
        if (user == null || !rules.contains(state, actor, user.deptId()))
            throw new BusinessException(RESOURCE_NOT_FOUND);
        if (granting) rules.requireUsableUser(state, userId);
        if (roleId != null) {
            rules.checkRole(state, actorId, roleId);
        }
        return state;
    }

    /** 在调用方的读事务中一致读取批量查询所需的管理范围。 */
    public AdminState requireBatchReadContext(long actorId, List<Long> userIds) {
        var ids =
                LongStream.concat(
                                LongStream.of(actorId), userIds.stream().mapToLong(Long::longValue))
                        .distinct()
                        .toArray();
        var state = loader.load(false, ids);
        integrity.verify(state);
        rules.requireActive(state, actorId);
        return state;
    }

    /** 管理查询统一使用已验签的组织事实和同一套范围规则。 */
    public ManagedScope managedScope(long actorId) {
        var state = requireBatchReadContext(actorId, List.of());
        var actor = rules.requireActive(state, actorId);
        var departments =
                state.departments().keySet().stream()
                        .filter(id -> rules.contains(state, actor, id))
                        .collect(Collectors.toUnmodifiableSet());
        return new ManagedScope(departments, actor.adminLevel() == 1);
    }

    public record ManagedScope(Set<Long> departments, boolean global) {}

    private AdminState verified(long... userIds) {
        var state = loader.load(true, userIds);
        integrity.verify(state);
        return state;
    }

    private AdminState verifiedReadOnly(long... userIds) {
        var state = loader.load(false, userIds);
        integrity.verify(state);
        return state;
    }
}
