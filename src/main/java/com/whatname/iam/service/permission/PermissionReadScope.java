package com.whatname.iam.service.permission;

import com.whatname.iam.service.admin.AdminPolicyService;
import com.whatname.iam.service.admin.AdminRules;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/** Reuses C's current M5 rules. Its missing integrity adapter continues to reject in production. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class PermissionReadScope {
    private final AdminPolicyService policy;
    private final AdminRules rules;
    private final PermissionService permissions;
    public PermissionReadScope(AdminPolicyService policy, AdminRules rules, PermissionService permissions) {
        this.policy = policy; this.rules = rules; this.permissions = permissions;
    }
    public Scope departments(String authorization, String function) {
        long actorId = permissions.currentUser(authorization);
        permissions.requireFunction(actorId, function);
        var state = policy.requireBatchReadContext(actorId, List.of());
        var actor = rules.requireActive(state, actorId);
        return new Scope(state.departments().keySet().stream().filter(id -> rules.contains(state, actor, id))
                .collect(Collectors.toUnmodifiableSet()), actor.adminLevel() == 1);
    }
    public record Scope(Set<Long> departments, boolean global) { }
}
