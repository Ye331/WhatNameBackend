package com.whatname.iam.service.admin;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 账号模块使用的管理范围检查。不接受客户端提交的范围，也不用只读快照作为写入依据。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AccountManagementPolicy {
    private final AdminPolicyService policy;

    public AccountManagementPolicy(AdminPolicyService policy) {
        this.policy = policy;
    }

    /**
     * First call in a fresh account transaction. Takes the ordered admin/user locks. Account
     * operations have zero or one existing target; never acquire actor first for a target write.
     */
    public void prepare(long actorId, long... targets) {
        if (targets.length == 0) policy.requireAdministrator(actorId);
        else if (targets.length == 1) policy.requireUserScope(actorId, targets[0]);
        else
            throw new IllegalArgumentException("A operation must have at most one existing target");
    }

    public void prepareDepartment(long actorId, long departmentId) {
        try {
            policy.requireDepartmentScope(actorId, departmentId);
        } catch (BusinessException failure) {
            // 账号接口对部门目标越权按既有契约返回 OUT_OF_SCOPE (403)。
            if (failure.errorCode() == ErrorCode.RESOURCE_NOT_FOUND)
                throw new BusinessException(ErrorCode.OUT_OF_SCOPE);
            throw failure;
        }
    }

    public Set<Long> scope(long actorId) {
        return policy.managedScope(actorId).departments();
    }
}
