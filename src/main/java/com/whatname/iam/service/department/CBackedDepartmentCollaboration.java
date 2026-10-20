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

/** C management and organization review adapters. HR authentication/recovery and A's
 * organization signing still require their own production providers. */
public class CBackedDepartmentCollaboration implements DepartmentCollaboration {
    private final AManagementPolicy policy;
    private final JwtTokenService jwt;
    private final AuthService auth;
    private final ManagementDataSecurity data;
    private final com.whatname.iam.service.admin.AdminOrganizationLifecycle lifecycle;
    public CBackedDepartmentCollaboration(AManagementPolicy policy,JwtTokenService jwt,
                                         AuthService auth,ManagementDataSecurity data,
                                         com.whatname.iam.service.admin.AdminOrganizationLifecycle lifecycle) {
        this.policy=policy; this.jwt=jwt; this.auth=auth; this.data=data;
        this.lifecycle=lifecycle;
    }
    public Manager authenticateManager(HttpServletRequest request,String permission) {
        var session=jwt.verifyBearer(request.getHeader("Authorization"));
        return new Manager(Long.toString(session.userId()),session);
    }
    public void prepareManagement(Manager actor) {
        requirePrincipal(actor);
        policy.prepare(actor.session().userId());
        auth.requireCurrentSession(actor.session());
    }
    public void prepareDepartmentManagement(Manager actor,long departmentId) {
        requirePrincipal(actor);
        policy.prepareDepartment(actor.session().userId(),departmentId);
        auth.requireCurrentSession(actor.session());
    }
    private void requirePrincipal(Manager actor) {
        if(actor.session()==null || !actor.actorId().equals(Long.toString(actor.session().userId())))
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
    }
    public Set<Long> scope(Manager actor,String permission,List<Department> organization) {
        // The service already acquired C's current locks and checked the session before
        // any organization read. Do not introduce another lock phase after those reads.
        requirePrincipal(actor);
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
    public void prepareOrganizationChange(TrustedEventContext actor,String eventId) {
        if(lifecycle==null) throw unavailable();
        lifecycle.prepare(actor,eventId);
    }
    public void organizationChanged(Set<Long> departments,List<Map<String,Object>> movedUsers) {
        if(lifecycle==null) throw unavailable();
        lifecycle.organizationChanged(departments,movedUsers);
    }
}
