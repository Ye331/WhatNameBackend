package com.whatname.iam.service.department;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.common.security.ManagementDataSecurity;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.service.admin.AManagementPolicy;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.event.TrustedEventContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;

/** Only management is adapted. HR service authentication and C organization-change policy
 * have no real provider; they deliberately remain unavailable, including recovery. */
public class CBackedDepartmentCollaboration implements DepartmentCollaboration {
    private final AManagementPolicy policy;
    private final JwtTokenService jwt;
    private final AuthService auth;
    private final ManagementDataSecurity data;
    public CBackedDepartmentCollaboration(AManagementPolicy policy,JwtTokenService jwt,
                                         AuthService auth,ManagementDataSecurity data) {
        this.policy=policy; this.jwt=jwt; this.auth=auth; this.data=data;
    }
    public Manager authenticateManager(HttpServletRequest request,String permission) {
        var session=jwt.verifyBearer(request.getHeader("Authorization"));
        return new Manager(Long.toString(session.userId()),session);
    }
    public void prepareManagement(Manager actor) {
        if(actor.session()==null || !actor.actorId().equals(Long.toString(actor.session().userId())))
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        policy.prepare(actor.session().userId());
        auth.requireCurrentSession(actor.session());
    }
    public Set<Long> scope(Manager actor,String permission,List<Department> organization) {
        // Session and current M5 facts are both rechecked in the same transaction.
        prepareManagement(actor);
        var result=policy.scope(actor.session().userId());
        data.requireFunction(actor.session().userId(),permission);
        return result;
    }
    public void verifyOrganization(List<Department> organization) { data.verifyOrganization(organization); }
    public void verifyUsers(List<Map<String,Object>> users) { data.verifyUsers(users); }
    public void signAndAudit(String operation,Object actor,Map<String,Object> evidence) {
        data.signOrganization(operation,actor,evidence);
    }
    private BusinessException unavailable() { return new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
    public TrustedEventContext authenticateHr(HttpServletRequest request,String eventType) { throw unavailable(); }
    public void verifyTrustedEvent(TrustedEventContext context,String eventType) { throw unavailable(); }
    public void authorizeRecovery(TrustedEventContext context,String eventType) { throw unavailable(); }
    public void organizationChanged(Set<Long> departments,List<Map<String,Object>> movedUsers) { throw unavailable(); }
}
