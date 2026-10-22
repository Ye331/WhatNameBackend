package com.whatname.iam.common.security;

import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.service.admin.AccountManagementPolicy;

import org.springframework.stereotype.Service;

import java.util.Set;

/** 账号模块的安全入口：先按统一锁序准备管理范围，再验签、检查功能权限、签名和审计。 全部加入调用方事务；审计只记录操作和原因，不记录密码、验证码或完整 JWT。 */
@Service
public class AccountSecurity {
    private final AccountManagementPolicy policy;
    private final ManagementDataSecurity data;

    public AccountSecurity(AccountManagementPolicy policy, ManagementDataSecurity data) {
        this.policy = policy;
        this.data = data;
    }

    public void prepareManagement(long actorId, long... targets) {
        policy.prepare(actorId, targets);
    }

    public void prepareDepartmentManagement(long actorId, long departmentId) {
        policy.prepareDepartment(actorId, departmentId);
    }

    public void verifyIdentity(UserAccount user) {
        data.verifyIdentity(user);
    }

    public Set<Long> authorizeManagement(UserAccount actor, String permission) {
        var scope = policy.scope(actor.id);
        data.requireFunction(actor.id, permission);
        return scope;
    }

    public void signIdentity(UserAccount user) {
        data.signIdentity(user);
    }

    public void audit(String operation, Long actor, Long target, String reason) {
        data.audit(operation, actor, target, reason);
    }
}
