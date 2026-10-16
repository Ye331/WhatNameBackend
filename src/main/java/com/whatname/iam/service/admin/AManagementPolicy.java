package com.whatname.iam.service.admin;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A adapter over real C M5. No client scopes, no read snapshot used as write authority. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AManagementPolicy {
    private final AdminPolicyService policy;
    private final AdminRules rules;
    public AManagementPolicy(AdminPolicyService policy, AdminRules rules) {
        this.policy=policy; this.rules=rules;
    }
    public void prepare(long actorId, long... targets) {
        policy.requireManagementContext(actorId, Arrays.stream(targets).boxed().toList());
    }
    public Set<Long> scope(long actorId) {
        var facts=policy.requireManagementContext(actorId, java.util.List.of());
        var actor=rules.requireActive(facts,actorId);
        return facts.departments().keySet().stream().filter(id -> rules.contains(facts,actor,id))
                .collect(Collectors.toUnmodifiableSet());
    }
}
