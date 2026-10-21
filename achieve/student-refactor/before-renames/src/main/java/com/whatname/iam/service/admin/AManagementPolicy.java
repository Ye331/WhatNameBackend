package com.whatname.iam.service.admin;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A adapter over real C M5. No client scopes, no read snapshot used as write authority. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AManagementPolicy {
    private final AdminPolicyService policy;
    public AManagementPolicy(AdminPolicyService policy) {
        this.policy = policy;
    }
    /** First call in a fresh A transaction. C takes its own ordered M5/user locks.
     * A operations have zero or one existing target; never acquire actor first for a target write. */
    public void prepare(long actorId, long... targets) {
        if(targets.length==0) policy.requireAdministrator(actorId);
        else if(targets.length==1) policy.requireUserScope(actorId,targets[0]);
        else throw new IllegalArgumentException("A operation must have at most one existing target");
    }
    public void prepareDepartment(long actorId,long departmentId) {
        try { policy.requireDepartmentScope(actorId,departmentId); }
        catch(BusinessException failure) {
            // A's existing department-target HTTP contract uses OUT_OF_SCOPE (403).
            if(failure.errorCode()==ErrorCode.RESOURCE_NOT_FOUND)
                throw new BusinessException(ErrorCode.OUT_OF_SCOPE);
            throw failure;
        }
    }
    public Set<Long> scope(long actorId) {
        return policy.managedScope(actorId).departments();
    }
}
