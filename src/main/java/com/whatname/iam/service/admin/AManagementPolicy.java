package com.whatname.iam.service.admin;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.ManagementDataSecurity;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.mapper.department.OrganizationWriteMapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
    private final OrganizationWriteMapper organization;
    private final ManagementDataSecurity data;
    public AManagementPolicy(AdminPolicyService policy, AdminRules rules,
                             OrganizationWriteMapper organization, ManagementDataSecurity data) {
        this.policy=policy; this.rules=rules; this.organization=organization; this.data=data;
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
        // prepare/prepareDepartment already obtained C's current-write authority before
        // A's first consistent read. This snapshot only projects C's existing scope rules.
        var facts=policy.requireBatchReadContext(actorId,List.of());
        var actor=rules.requireActive(facts,actorId);
        List<Department> departments=organization.organization();
        data.verifyOrganization(departments);
        Map<Long,Department> byId=new HashMap<>();
        departments.forEach(d -> byId.put(d.getId(),d));
        requireUnmergedChain(byId,actor.deptId());
        requireUnmergedChain(byId,facts.users().get(actorId).deptId());
        return facts.departments().keySet().stream().filter(id -> rules.contains(facts,actor,id))
                .collect(Collectors.toUnmodifiableSet());
    }
    /** A owns merged-department lifecycle. C's unchanged policy does not read this field. */
    private void requireUnmergedChain(Map<Long,Department> departments,long id) {
        var visited=new HashSet<Long>();
        while(id!=0) {
            Department department=departments.get(id);
            if(!visited.add(id) || department==null) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            if(department.getMergedIntoDeptId()!=null) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
            id=department.getParentId();
        }
    }
}
