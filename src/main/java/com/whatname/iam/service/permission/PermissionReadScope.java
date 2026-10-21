package com.whatname.iam.service.permission;

import com.whatname.iam.service.admin.AdminPolicyService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.Set;

/** 在线查询复用统一管理员范围规则。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionReadScope {
    private final AdminPolicyService policy;
    private final PermissionService permissions;

    public PermissionReadScope(AdminPolicyService policy, PermissionService permissions) {
        this.policy = policy;
        this.permissions = permissions;
    }

    public Scope departments(String authorization, String function) {
        long actorId = permissions.currentUser(authorization);
        permissions.requireFunction(actorId, function);
        var scope = policy.managedScope(actorId);
        return new Scope(scope.departments(), scope.global());
    }

    public record Scope(Set<Long> departments, boolean global) {}
}
