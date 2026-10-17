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

/** 供 A/B/C 写入服务复用的 M5 校验；调用方须提供已验证的操作者，并执行 M6 功能权限检查。 */
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
    /** 单人授权和查询共用一次状态读取；需要加锁时，按用户 ID 顺序一次取得相关用户锁。 */
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
    /** 在调用方的读事务中一致读取批量查询所需的管理范围。 */
    public AdminState requireBatchReadContext(long actorId, List<Long> userIds) {
        var ids = LongStream.concat(LongStream.of(actorId), userIds.stream().mapToLong(Long::longValue)).distinct().toArray();
        var state = loader.load(false, ids);
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
