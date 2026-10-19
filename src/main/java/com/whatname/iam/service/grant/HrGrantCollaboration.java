package com.whatname.iam.service.grant;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.grant.GrantData.Source;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.admin.AdminMapper;
import com.whatname.iam.mapper.event.ExternalEventMapper;
import com.whatname.iam.mapper.grant.GrantMapper;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.admin.AdminOrganizationLifecycle;
import com.whatname.iam.service.admin.integration.CChangeEvidence;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.event.TrustedEventContext;
import com.whatname.iam.service.hr.HrCollaboration;
import com.whatname.iam.service.permission.DataSignatureService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import java.util.*;
import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

/** 参与 A 已认证的 HR 事件事务，不伪造人工管理员。
 * A/D 须在 prepare 前完成认证，并在等待业务锁后复核当前服务权限。 */
@Service
@Transactional(propagation=Propagation.MANDATORY)
public class HrGrantCollaboration implements HrCollaboration {
    private final Object key=new Object();
    private final GrantMapper grants;
    private final AdminMapper admins;
    private final UserAccountMapper users;
    private final ExternalEventMapper events;
    private final AdminOrganizationLifecycle lifecycle;
    private final DataSignatureService signatures;
    private final AuditService audit;

    public HrGrantCollaboration(GrantMapper grants,AdminMapper admins,UserAccountMapper users,
            ExternalEventMapper events,AdminOrganizationLifecycle lifecycle,
            DataSignatureService signatures,AuditService audit) {
        this.grants=grants; this.admins=admins; this.users=users; this.events=events;
        this.lifecycle=lifecycle; this.signatures=signatures; this.audit=audit;
    }

    public void prepare(TrustedEventContext actor,String eventId,String eventType,String employeeNo) {
        if(!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        java.sql.Connection.TRANSACTION_SERIALIZABLE)
                || TransactionSynchronizationManager.hasResource(key)) fail(AUTH_STATE_UNAVAILABLE);
        if(actor==null || !"HR".equals(actor.sourceSystem()) || eventId==null || eventId.isBlank()
                || employeeNo==null || employeeNo.isBlank()
                || !Set.of("HR_EMPLOYEE","HR_TRANSFER","HR_TERMINATION").contains(eventType)) fail(INVALID_SERVICE);
        // 绑定 A 已受理且正在处理的事件，防止参与错误业务；此检查不替代 D 服务认证。
        var event=events.lock(actor.sourceSystem(),eventId);
        if(event==null || !"PROCESSING".equals(event.get("status")) || !eventType.equals(event.get("eventType"))
                || !"USER".equals(event.get("targetType")) || !employeeNo.equals(event.get("targetRef")))
            fail(AUTH_STATE_UNAVAILABLE);
        lifecycle.prepareHr(actor,eventId);
        var person=users.lockByEmployeeNo(employeeNo);
        if(person!=null && (person.source!=1 || !employeeNo.equals(person.employeeNo))) fail(INTEGRITY_FAILURE);
        GrantState before=null;
        if(person!=null) {
            before=load(person.id);
            signatures.verify(USER_GRANTS,person.id);
            consistent(before);
        }
        var pending=new Pending(actor,eventId,eventType,employeeNo,person,before);
        TransactionSynchronizationManager.bindResource(key,pending);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void suspend() { TransactionSynchronizationManager.unbindResource(key); }
            public void resume() { TransactionSynchronizationManager.bindResource(key,pending); }
            public void beforeCommit(boolean readOnly) {
                if(!pending.completed || !pending.after.equals(load(pending.after.userId()))
                        || !pending.identity.equals(signatures.snapshot(USER_IDENTITY,pending.after.userId())))
                    fail(AUTH_STATE_UNAVAILABLE);
                signatures.verify(USER_IDENTITY,pending.after.userId());
                signatures.verify(USER_GRANTS,pending.after.userId());
            }
            public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(key); }
        });
    }

    public void revokePosition(TrustedEventContext actor,String eventId,long userId,long oldDepartmentId) {
        var p=required(userId);
        if(!p.actor.equals(actor) || !p.eventId.equals(eventId) || p.cleaned
                || p.person.deptId!=oldDepartmentId || "HR_TERMINATION".equals(p.eventType)) fail(AUTH_STATE_UNAVAILABLE);
        revoke(p,false,oldDepartmentId);
    }

    public void revokeAll(TrustedEventContext actor,String eventId,long userId) {
        var p=required(userId);
        if(!p.actor.equals(actor) || !p.eventId.equals(eventId) || p.cleaned
                || !"HR_TERMINATION".equals(p.eventType)) fail(AUTH_STATE_UNAVAILABLE);
        revoke(p,true,0);
    }

    private void revoke(Pending p,boolean all,long oldDepartmentId) {
        var before=load(p.person.id);
        if(!before.equals(p.before)) fail(AUTH_STATE_UNAVAILABLE);
        var removed=before.sources().stream().filter(s -> s.status()==1 && (all
                || ("DEPT_POSITION".equals(s.sourceType()) && Objects.equals(s.sourceDeptId(),oldDepartmentId)))).toList();
        if(!removed.isEmpty()) {
            if(before.grantRevision()==Long.MAX_VALUE) fail(AUTH_STATE_UNAVAILABLE);
            Set<Long> remaining=new HashSet<>();
            Set<Long> removedIds=new HashSet<>(); removed.forEach(s -> removedIds.add(s.id()));
            for(var s:before.sources()) if(s.status()==1 && !removedIds.contains(s.id())) remaining.add(s.roleId());
            for(var s:removed) expectOne(grants.revokeHrSource(before.userId(),s.roleId(),s.id()));
            for(var r:before.effectiveRoles()) if(!remaining.contains(r.roleId())) expectOne(grants.deleteEffectiveRole(before.userId(),r.roleId()));
            expectOne(grants.advanceRevision(before.userId(),before.grantRevision()));
            var after=load(before.userId()); consistent(after);
            // 身份包含 A 随后更新的部门、状态和会话/HR 版本，由 A 在结束阶段签最终值。
            signatures.sign(USER_GRANTS,after.userId());
            audit.change(all ? "HR_GRANTS_REVOKE_ALL" : "HR_POSITION_REVOKE",null,"USER",after.userId(),p.eventId,Map.of(
                    "schema","C_HR_GRANT_CHANGE_V1","origin",origin(p),
                    "reason",all ? "HR_TERMINATION" : "OLD_DEPARTMENT_POSITION",
                    "oldDepartmentId",all ? p.person.deptId : oldDepartmentId,
                    "revokedSourceIds",removed.stream().map(Source::id).toList(),
                    "before",CChangeEvidence.grants(before),"after",CChangeEvidence.grants(after)));
        }
        p.cleaned=true;
        p.after=load(before.userId());
    }

    public void requireRehireEligible(long userId) {
        var p=required(userId);
        if(p.person.status!=3 || p.rehireChecked || !"HR_EMPLOYEE".equals(p.eventType)) fail(AUTH_STATE_UNAVAILABLE);
        var state=load(userId);
        if(state.sources().stream().anyMatch(s -> s.status()==1) || !state.effectiveRoles().isEmpty()
                || admins.selectAppointments(false).stream().anyMatch(a -> a.userId()==userId && a.status()==1)
                || admins.selectDelegations(false).stream().anyMatch(d -> d.adminUserId()==userId || Objects.equals(d.configuredBy(),userId)))
            fail(BUSINESS_RULE_VIOLATION);
        p.rehireChecked=true;
    }

    public void departmentChanged(long userId,long oldDepartmentId,long newDepartmentId) {
        var p=required(userId);
        if(!p.cleaned || p.moved || p.person.deptId!=oldDepartmentId || oldDepartmentId==newDepartmentId
                || !Objects.equals(users.lockById(userId).deptId,newDepartmentId)) fail(AUTH_STATE_UNAVAILABLE);
        p.moved=true;
    }

    public void complete(long userId) {
        var p=pending();
        var person=users.lockById(userId);
        if(person==null || !p.employeeNo.equals(person.employeeNo) || person.source!=1) fail(AUTH_STATE_UNAVAILABLE);
        if(p.person==null) {
            if(!"HR_EMPLOYEE".equals(p.eventType)) fail(AUTH_STATE_UNAVAILABLE);
            var empty=load(userId);
            if(empty.grantRevision()!=1 || !empty.sources().isEmpty() || !empty.effectiveRoles().isEmpty()) fail(INTEGRITY_FAILURE);
            signatures.sign(USER_GRANTS,userId); // 仅为本次新建身份首次签署空授权集合。
        } else {
            if(p.person.id!=userId || (!p.person.deptId.equals(person.deptId) && !p.moved)
                    || (p.person.status==3 && person.status!=3 && !p.rehireChecked)
                    || ("HR_TRANSFER".equals(p.eventType) && !p.moved)
                    || ("HR_TERMINATION".equals(p.eventType) && (!p.cleaned || person.status!=3))) fail(AUTH_STATE_UNAVAILABLE);
            var expected=p.after==null ? p.before : p.after;
            if(!expected.equals(load(userId))) fail(AUTH_STATE_UNAVAILABLE);
        }
        signatures.verify(USER_IDENTITY,userId);
        signatures.verify(USER_GRANTS,userId);
        lifecycle.hrChanged(userId);
        p.after=load(userId);
        p.identity=signatures.snapshot(USER_IDENTITY,userId);
        p.completed=true;
    }

    private Pending required(long userId) {
        var p=pending();
        if(p.person==null || p.person.id!=userId) fail(AUTH_STATE_UNAVAILABLE);
        return p;
    }
    private Pending pending() {
        var p=(Pending)TransactionSynchronizationManager.getResource(key);
        if(p==null || p.completed) fail(AUTH_STATE_UNAVAILABLE);
        return p;
    }
    private GrantState load(long userId) {
        Long revision=grants.selectRevision(userId,true);
        if(revision==null || revision<1) fail(INTEGRITY_FAILURE);
        return new GrantState(userId,revision,grants.selectSources(userId,true),grants.selectEffectiveRoles(userId,true));
    }
    private void consistent(GrantState state) {
        Set<Long> supported=new HashSet<>(),effective=new HashSet<>();
        Set<Long> roles=new HashSet<>(); admins.selectRoles(false).forEach(r -> roles.add(r.id()));
        for(var s:state.sources()) {
            if(s.userId()!=state.userId() || !roles.contains(s.roleId()) || !Set.of("MANUAL","DEPT_POSITION","OA_APPROVAL").contains(s.sourceType())
                    || s.sourceRef()==null || s.sourceRef().isBlank() || (s.status()!=0 && s.status()!=1)
                    || ("DEPT_POSITION".equals(s.sourceType()) && (s.sourceDeptId()==null || s.sourceDeptId()<=0))) fail(INTEGRITY_FAILURE);
            if(s.status()==1) supported.add(s.roleId());
        }
        for(var r:state.effectiveRoles()) if(r.userId()!=state.userId() || !effective.add(r.roleId())) fail(INTEGRITY_FAILURE);
        if(!supported.equals(effective)) fail(INTEGRITY_FAILURE);
    }
    private static Map<String,Object> origin(Pending p) {
        return Map.of("sourceSystem",p.actor.sourceSystem(),"clientId",p.actor.clientId(),
                "requestId",p.actor.requestId(),"eventId",p.eventId,"eventType",p.eventType);
    }
    private static void expectOne(int n) { if(n!=1) fail(AUTH_STATE_UNAVAILABLE); }
    private static void fail(com.whatname.iam.common.api.ErrorCode code) { throw new BusinessException(code); }
    private static final class Pending {
        final TrustedEventContext actor;
        final String eventId,eventType,employeeNo;
        final UserAccount person;
        final GrantState before;
        boolean cleaned,moved,rehireChecked,completed;
        GrantState after;
        Map<String,Object> identity;
        Pending(TrustedEventContext actor,String eventId,String eventType,String employeeNo,UserAccount person,GrantState before) {
            this.actor=actor; this.eventId=eventId; this.eventType=eventType; this.employeeNo=employeeNo; this.person=person; this.before=before;
        }
    }
}
