package com.whatname.iam.service.hr;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.M1SecurityGateway;
import com.whatname.iam.common.security.SensitiveDataCodec;
import com.whatname.iam.dto.hr.HrRequests.*;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.department.OrganizationWriteMapper;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.service.auth.NamePinyin;
import com.whatname.iam.service.department.*;
import com.whatname.iam.service.event.*;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Orchestration stays outside a transaction; every apply runs inside the event's transaction.
 * Lock order: event -> C administrators/departments/roles/lists/users/resources -> grant facts.
 * C preparation precedes all organization/person reads. No AuthService HTTP operation
 * (REQUIRES_NEW) is called here. Account SQL joins the existing transaction.
 */
@Service
public class HrService {
    private final ExternalEventService events;
    private final OrganizationWriteMapper organization;
    private final DepartmentService departments;
    private final DepartmentCollaboration trusted;
    private final UserAccountMapper users;
    private final M1SecurityGateway identity;
    private final HrCollaboration grants;
    private final SensitiveDataCodec codec;
    private final PasswordHasher passwords;
    private final Clock clock;

    public HrService(ExternalEventService events,OrganizationWriteMapper organization,DepartmentService departments,
            DepartmentCollaboration trusted,UserAccountMapper users,M1SecurityGateway identity,HrCollaboration grants,
            SensitiveDataCodec codec,PasswordHasher passwords,@Qualifier("hrClock") Clock clock) {
        this.events=events;this.organization=organization;this.departments=departments;this.trusted=trusted;
        this.users=users;this.identity=identity;this.grants=grants;this.codec=codec;this.passwords=passwords;this.clock=clock;
    }

    public Map<String,Object> employee(TrustedEventContext actor,String path,Employee r) {
        source(actor,r.sourceSystem());
        if(!Objects.equals(path,r.sgNo())) fail(ErrorCode.INVALID_ARGUMENT);
        return events.execute(actor,new ExternalEventService.Command(r.eventId(),"HR_EMPLOYEE",r.eventVersion(),
                "USER",r.sgNo(),r.occurredAt(),r),() -> unique(() -> applyEmployee(actor,r)));
    }
    private Map<String,Object> applyEmployee(TrustedEventContext actor,Employee r) {
        List<Department> all=lock(actor,r.eventId(),"HR_EMPLOYEE",r.sgNo());
        Department target=assignable(all,r.deptCode());
        UserAccount u=users.lockByEmployeeNo(r.sgNo());
        String phone=SensitiveDataCodec.normalizePhone(r.phone());
        // Validate the whole name before lifecycle, C grants or profile writes (including updates).
        NamePinyin.variants(r.realName().strip());
        if(u==null) {
            // REHIRE/SUSPEND/RESUME name an existing lifecycle transition, never create a person.
            if(r.employeeAction()!=Action.UPSERT) fail(ErrorCode.RESOURCE_NOT_FOUND);
            u=new UserAccount();u.employeeNo=r.sgNo();u.source=1;u.status=1;u.passwordInitialized=0;
            u.sessionVersion=1L;u.grantRevision=1L;u.pwdFailedCount=0;u.deptId=target.getId();
            String base=HrLoginName.initials(r.realName());u.username=base;
            if(users.lockByUsername(base)!=null) {
                u.username=base+r.sgNo().substring(Math.max(0,r.sgNo().length()-6));
                if(users.lockByUsername(u.username)!=null) fail(ErrorCode.DUPLICATE_RESOURCE);
            }
            // 64 random hex characters, discarded after real scrypt; never a delivered password/history.
            u.passwordHash=passwords.hash(UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-",""));
            profile(u,r,phone); users.insertHr(u);
            com.whatname.iam.common.security.AAccountCreation.inserted(u);
        } else {
            verify(u,r.sgNo(),r.eventVersion());
            if(u.status==3) {
                if(r.employeeAction()!=Action.REHIRE) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
                grants.requireRehireEligible(u.id);
                u.status=1;u.sessionVersion=Math.addExact(u.sessionVersion,1);
            } else {
                if(r.employeeAction()==Action.REHIRE) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
                if(r.employeeAction()==Action.SUSPEND && u.status!=2) {
                    u.status=2;u.sessionVersion=Math.addExact(u.sessionVersion,1);
                } else if(r.employeeAction()==Action.RESUME) u.status=1;
            }
            if(!u.deptId.equals(target.getId())) move(actor,r.eventId(),u,target);
            profile(u,r,phone); update(u);
        }
        finish("HR_EMPLOYEE",actor,r.eventId(),u);
        return Map.of("eventId",r.eventId(),"processingStatus","APPLIED","userId",u.id.toString(),"username",u.username);
    }

    public Map<String,Object> transfer(TrustedEventContext actor,Transfer r) {
        source(actor,r.sourceSystem());
        return events.execute(actor,new ExternalEventService.Command(r.eventId(),"HR_TRANSFER",r.eventVersion(),
                "USER",r.sgNo(),r.occurredAt(),r),() -> {
            List<Department> all=lock(actor,r.eventId(),"HR_TRANSFER",r.sgNo());
            Department from=byCode(all,r.fromDeptCode()); Department target=assignable(all,r.toDeptCode());
            UserAccount u=required(r.sgNo());verify(u,r.sgNo(),r.eventVersion());
            if(!u.deptId.equals(from.getId())) fail(ErrorCode.INVALID_DEPT_MAPPING);
            if(u.status==3 || from.getId().equals(target.getId())) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
            move(actor,r.eventId(),u,target);u.hrVersion=r.eventVersion();update(u);
            finish("HR_TRANSFER",actor,r.eventId(),u);
            return Map.of("eventId",r.eventId(),"processingStatus","APPLIED",
                    "stage","OLD_GRANTS_REVOKED_AND_DEPT_MOVED","awaitingNewRoleGrant",true);
        });
    }

    public Map<String,Object> terminate(TrustedEventContext actor,Termination r) {
        source(actor,r.sourceSystem());
        return events.execute(actor,new ExternalEventService.Command(r.eventId(),"HR_TERMINATION",r.eventVersion(),
                "USER",r.sgNo(),r.effectiveAt(),r),() -> {
            lock(actor,r.eventId(),"HR_TERMINATION",r.sgNo());
            if(r.effectiveAt().toInstant().isAfter(clock.instant())) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
            UserAccount u=required(r.sgNo());verify(u,r.sgNo(),r.eventVersion());
            if(u.status==3) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
            grants.revokeAll(actor,r.eventId(),u.id);
            u.status=3;u.sessionVersion=Math.addExact(u.sessionVersion,1);u.hrVersion=r.eventVersion();update(u);
            finish("HR_TERMINATION",actor,r.eventId(),u);
            return Map.of("eventId",r.eventId(),"processingStatus","APPLIED","accountStatus",3);
        });
    }

    private List<Department> lock(TrustedEventContext actor,String eventId,String type,String employeeNo) {
        if(!TransactionSynchronizationManager.isActualTransactionActive() || !Objects.equals(
                TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),java.sql.Connection.TRANSACTION_SERIALIZABLE))
            throw new IllegalStateException("Serializable event transaction required");
        trusted.verifyTrustedEvent(actor,type);
        grants.prepare(actor,eventId,type,employeeNo);
        List<Department> all=organization.lockOrganization();trusted.verifyOrganization(all);
        // Authentication was checked at ingress; recheck service authority in the write transaction.
        trusted.verifyTrustedEvent(actor,type);return all;
    }
    private Department assignable(List<Department> all,String code) {
        Department d=byCode(all,code);return departments.requireAssignableDepartment(d.getId());
    }
    private Department byCode(List<Department> all,String code) {
        return all.stream().filter(d->d.getDeptCode().equals(code)).findFirst()
                .orElseThrow(()->new BusinessException(ErrorCode.INVALID_DEPT_MAPPING));
    }
    private UserAccount required(String sg) {
        UserAccount u=users.lockByEmployeeNo(sg);if(u==null) fail(ErrorCode.RESOURCE_NOT_FOUND);return u;
    }
    private void verify(UserAccount u,String sg,Long version) {
        if(u.source!=1 || !sg.equals(u.employeeNo)) fail(ErrorCode.INVALID_ARGUMENT);
        identity.verifyIdentity(u);
        if(version==null || version<=u.hrVersion) fail(ErrorCode.INVALID_HR_ORDER);
    }
    private void profile(UserAccount u,Employee r,String phone) {
        u.realNameCiphertext=codec.encrypt(r.realName().strip());u.phone=codec.encrypt(phone);u.phoneHash=codec.phoneHash(phone);
        u.employmentType=r.employmentType()==EmploymentType.FORMAL?1:2;u.hrVersion=r.eventVersion();
    }
    private void move(TrustedEventContext actor,String eventId,UserAccount u,Department target) {
        long old=u.deptId;
        grants.revokePosition(actor,eventId,u.id,old);
        u.deptId=target.getId();
        // C sees the new persisted department while keeping the original source ID explicit.
        update(u);grants.departmentChanged(u.id,old,u.deptId);
        // No sv bump: JWT carries identity/sv, never roles; M6 must read current grants every request.
    }
    private void update(UserAccount u) { if(users.updateHr(u)!=1) fail(ErrorCode.VERSION_CONFLICT); }
    private void finish(String operation,TrustedEventContext actor,String eventId,UserAccount u) {
        // New inserts have not called C; preserve the generated-key entity for empty-grants
        // initialization. Existing accounts reload C's grant_revision before D signs.
        UserAccount current=com.whatname.iam.common.security.AAccountCreation.contains(u)?u:users.lockById(u.id);
        identity.signIdentity(current);
        trusted.signAndAudit(operation,actor,Map.of("eventId",eventId,"userId",current.id.toString(),
                "deptId",current.deptId.toString(),"status",current.status,"hrVersion",current.hrVersion,
                "sessionVersion",current.sessionVersion,"grantRevision",current.grantRevision));
        grants.complete(current.id);
    }
    private void source(TrustedEventContext actor,String body) {
        if(actor==null || !"HR".equals(actor.sourceSystem()) || !"HR".equals(body)) fail(ErrorCode.INVALID_SERVICE);
    }
    private Map<String,Object> unique(Supplier<Map<String,Object>> work) {
        try { return work.get(); } catch(DuplicateKeyException e) { throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE); }
    }
    private static void fail(ErrorCode code) { throw new BusinessException(code); }
}
