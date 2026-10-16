package com.whatname.iam.service.department;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.department.DepartmentRequests;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.mapper.department.OrganizationWriteMapper;
import com.whatname.iam.service.event.ExternalEventService;
import com.whatname.iam.service.event.TrustedEventContext;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DepartmentService {
    private final OrganizationWriteMapper mapper;
    private final DepartmentCollaboration integration;
    private final ExternalEventService events;
    public DepartmentService(OrganizationWriteMapper mapper,DepartmentCollaboration integration,ExternalEventService events) {
        this.mapper=mapper; this.integration=integration; this.events=events;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> tree(DepartmentCollaboration.Manager actor,boolean descendants) {
        List<Department> all=mapper.organization(); validateTree(all); integration.verifyOrganization(all);
        Set<Long> allowed=integration.scope(actor,"IAM_DEPTS_VIEW",all);
        List<Map<String,Object>> items=new ArrayList<>();
        for(Department d:all) if(allowed.contains(d.getId()) && (descendants || !allowed.contains(d.getParentId()))) {
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("deptId",d.getId().toString());
            // Out-of-scope parents are represented as zero, never leak the outside ancestor path/ID.
            item.put("parentId",allowed.contains(d.getParentId())?d.getParentId().toString():"0");
            item.put("deptName",d.getDeptName()); item.put("status",d.getStatus());
            item.put("effectivelyFrozen",frozen(d,index(all)));
            item.put("mergedIntoDeptId",d.getMergedIntoDeptId()!=null && allowed.contains(d.getMergedIntoDeptId())
                    ? d.getMergedIntoDeptId().toString():null);
            item.put("merged",d.getMergedIntoDeptId()!=null); items.add(item);
        }
        return Map.of("items",items);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Map<String,Object> users(DepartmentCollaboration.Manager actor,long id,boolean descendants,int page,int size) {
        if(page<1 || page>1_000_000 || size<1 || size>500) fail(ErrorCode.INVALID_ARGUMENT);
        List<Department> all=mapper.organization(); validateTree(all); integration.verifyOrganization(all);
        Set<Long> allowed=integration.scope(actor,"IAM_USERS_VIEW",all);
        if(!allowed.contains(id)) fail(ErrorCode.OUT_OF_SCOPE);
        Department root=require(index(all).get(id));
        List<Long> ids=all.stream().filter(d -> allowed.contains(d.getId()) &&
                (d.getId()==id || (descendants && d.getDeptPath().startsWith(root.getDeptPath())))).map(Department::getId).toList();
        long total=mapper.countUsers(ids);
        List<Map<String,Object>> items=new ArrayList<>();
        for(Map<String,Object> row:mapper.users(ids,size,((long)page-1)*size)) {
            Map<String,Object> item=new LinkedHashMap<>(row);
            item.put("userId",row.get("userId").toString()); item.put("deptId",row.get("deptId").toString()); items.add(item);
        }
        integration.verifyUsers(items);
        return Map.of("items",items,"page",page,"pageSize",size,"total",total);
    }

    public Map<String,Object> sync(TrustedEventContext ctx,String code,DepartmentRequests.Sync request) {
        if(!"HR".equals(ctx.sourceSystem()) || !ctx.sourceSystem().equals(request.sourceSystem())) fail(ErrorCode.INVALID_SERVICE);
        if(!code.equals(request.deptCode())) fail(ErrorCode.INVALID_DEPT_MAPPING);
        return events.execute(ctx,new ExternalEventService.Command(request.eventId(),"HR_DEPARTMENT",request.eventVersion(),
                "DEPT",code,request.occurredAt(),request),() -> applySync(ctx,request));
    }

    private Map<String,Object> applySync(TrustedEventContext ctx,DepartmentRequests.Sync r) {
        List<Department> all=locked(); Map<Long,Department> byId=index(all);
        List<Map<String,Object>> before=snapshot(all);
        Department current=byCode(all,r.deptCode());
        if(current==null && mapper.idByCode(r.deptCode())!=null) fail(ErrorCode.INVALID_DEPT_MAPPING);
        Department parent=r.parentDeptCode().isEmpty()?null:byCode(all,r.parentDeptCode());
        if(!r.parentDeptCode().isEmpty() && parent==null) fail(ErrorCode.INVALID_DEPT_MAPPING);
        if(parent!=null) active(parent);
        if(current!=null) {
            active(current); newer(r.eventVersion(),current);
            if(parent!=null && parent.getDeptPath().startsWith(current.getDeptPath())) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        Long managerId=null;
        if(r.managerSgNo()!=null) {
            if(r.managerSgNo().isBlank()) fail(ErrorCode.INVALID_ARGUMENT);
            managerId=mapper.manager(r.managerSgNo());
            if(managerId==null) fail(ErrorCode.INVALID_DEPT_MAPPING);
        }
        if(current==null) {
            current=new Department(); current.setDeptCode(r.deptCode()); current.setDeptName(r.deptName());
            current.setParentId(parent==null?0L:parent.getId()); current.setDeptPath("/"); current.setManagerId(managerId);
            current.setStatus(1); current.setHrVersion(r.eventVersion()); current.setSortOrder(0);
            mapper.insert(current); all.add(current); byId.put(current.getId(),current);
        } else {
            current.setDeptName(r.deptName()); current.setParentId(parent==null?0L:parent.getId());
            if(r.managerSgNo()!=null) current.setManagerId(managerId);
            current.setHrVersion(r.eventVersion());
        }
        Set<Long> changed=repath(all);
        changed.add(current.getId());
        for(Department d:all) if(changed.contains(d.getId())) mapper.update(d);
        integration.organizationChanged(changed,List.of());
        integration.signAndAudit("HR_DEPARTMENT",ctx,evidence(before,snapshot(all),changed,List.of(),r.eventId()));
        return Map.of("eventId",r.eventId(),"processingStatus","APPLIED","deptId",current.getId().toString());
    }

    public Map<String,Object> merge(TrustedEventContext ctx,DepartmentRequests.Merge r) {
        if(!"HR".equals(ctx.sourceSystem()) || !ctx.sourceSystem().equals(r.sourceSystem())) fail(ErrorCode.INVALID_SERVICE);
        return events.execute(ctx,new ExternalEventService.Command(r.eventId(),"HR_MERGE",r.eventVersion(),"DEPT",
                r.sourceDeptCode(),r.occurredAt(),r),() -> applyMerge(ctx,r));
    }
    private Map<String,Object> applyMerge(TrustedEventContext ctx,DepartmentRequests.Merge r) {
        List<Department> all=locked(); Map<Long,Department> byId=index(all);
        Department source=require(byCode(all,r.sourceDeptCode())); Department target=require(byCode(all,r.targetDeptCode()));
        active(source); active(target); newer(r.eventVersion(),source);
        if(source.getId().equals(target.getId()) || target.getDeptPath().startsWith(source.getDeptPath())) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
        if(frozen(source,byId)) fail(ErrorCode.BUSINESS_RULE_VIOLATION);
        List<Map<String,Object>> before=snapshot(all);
        List<Map<String,Object>> users=mapper.lockUsers(source.getId()); integration.verifyUsers(users);
        if(mapper.moveUsers(source.getId(),target.getId())!=users.size()) fail(ErrorCode.VERSION_CONFLICT);
        for(Department d:all) if(d.getParentId().equals(source.getId())) d.setParentId(target.getId());
        source.setManagerId(null); source.setMergedIntoDeptId(target.getId()); source.setHrVersion(r.eventVersion());
        Set<Long> changed=repath(all); changed.add(source.getId()); changed.add(target.getId());
        for(Department d:all) if(changed.contains(d.getId())) mapper.update(d);
        // C must disable affected invalid management roots in this transaction, or throw and roll back.
        integration.organizationChanged(changed,users);
        integration.signAndAudit("HR_MERGE",ctx,evidence(before,snapshot(all),changed,users,r.eventId()));
        return Map.of("eventId",r.eventId(),"processingStatus","APPLIED","rolesPreserved",true);
    }

    @Transactional(isolation=Isolation.SERIALIZABLE,rollbackFor=Exception.class,timeout=30)
    public Map<String,Object> freeze(DepartmentCollaboration.Manager actor,long id,boolean freeze,String reason) {
        if(reason==null || reason.isBlank() || reason.length()>512) fail(ErrorCode.INVALID_ARGUMENT);
        List<Department> all=locked();
        if(!integration.scope(actor,"IAM_DEPTS_FREEZE",all).contains(id)) fail(ErrorCode.OUT_OF_SCOPE);
        Department d=require(index(all).get(id)); active(d);
        List<Map<String,Object>> before=snapshot(all);
        d.setStatus(freeze?0:1); mapper.update(d);
        integration.signAndAudit(freeze?"DEPT_FREEZE":"DEPT_UNFREEZE",actor,
                evidence(before,snapshot(all),Set.of(id),List.of(),reason));
        return Map.of("deptId",Long.toString(id),"status",d.getStatus());
    }

    /** Reusable A-side assignment check. Writers must call inside the organization write lock;
     * C appointments must independently adopt this lifecycle rule, not just a foreign key check.
     */
    public Department requireAssignableDepartment(long id) {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Organization transaction required");
        Department d=require(index(locked()).get(id)); active(d); return d;
    }
    private List<Department> locked() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()
                || !Objects.equals(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),java.sql.Connection.TRANSACTION_SERIALIZABLE))
            throw new IllegalStateException("Serializable organization transaction required");
        List<Department> all=mapper.lockOrganization(); validateTree(all); integration.verifyOrganization(all); return all;
    }
    private void active(Department d) { if(d.getMergedIntoDeptId()!=null) fail(ErrorCode.BUSINESS_RULE_VIOLATION); }
    private void newer(Long version,Department d) { if(version==null || version<=d.getHrVersion()) fail(ErrorCode.INVALID_HR_ORDER); }
    private Department require(Department d) { if(d==null) fail(ErrorCode.INVALID_DEPT_MAPPING); return d; }
    private Department byCode(List<Department> all,String code) { return all.stream().filter(d -> d.getDeptCode().equals(code)).findFirst().orElse(null); }
    private Map<Long,Department> index(List<Department> all) { Map<Long,Department> m=new HashMap<>(); all.forEach(d -> m.put(d.getId(),d)); return m; }
    private boolean frozen(Department d,Map<Long,Department> all) {
        while(d!=null) { if(d.getStatus()==0) return true; d=all.get(d.getParentId()); } return false;
    }
    private void validateTree(List<Department> all) {
        Map<Long,Department> index=index(all);
        for(Department d:all) {
            String expected=path(d,index,new HashSet<>());
            if(!expected.equals(d.getDeptPath())) fail(ErrorCode.INTEGRITY_FAILURE);
            if(d.getMergedIntoDeptId()!=null && (d.getMergedIntoDeptId().equals(d.getId()) || !index.containsKey(d.getMergedIntoDeptId())
                    || all.stream().anyMatch(child -> child.getParentId().equals(d.getId())))) fail(ErrorCode.INTEGRITY_FAILURE);
        }
    }
    private Set<Long> repath(List<Department> all) {
        Set<Long> changed=new HashSet<>(); Map<Long,Department> index=index(all);
        for(Department d:all) { String p=path(d,index,new HashSet<>()); if(!p.equals(d.getDeptPath())) {d.setDeptPath(p);changed.add(d.getId());} }
        validateTree(all); return changed;
    }
    private String path(Department d,Map<Long,Department> all,Set<Long> visiting) {
        if(!visiting.add(d.getId())) { fail(ErrorCode.BUSINESS_RULE_VIOLATION); }
        String prefix="/";
        if(d.getParentId()!=0) { Department parent=all.get(d.getParentId()); if(parent==null) fail(ErrorCode.INTEGRITY_FAILURE); prefix=path(parent,all,visiting); }
        String path=prefix+d.getId()+"/"; if(path.length()>512) fail(ErrorCode.BUSINESS_RULE_VIOLATION); return path;
    }
    private List<Map<String,Object>> snapshot(List<Department> all) {
        return all.stream().map(d -> {
            Map<String,Object> s=new LinkedHashMap<>();s.put("id",d.getId().toString());s.put("parentId",d.getParentId().toString());
            s.put("code",d.getDeptCode());s.put("name",d.getDeptName());s.put("path",d.getDeptPath());s.put("status",d.getStatus());
            s.put("managerId",d.getManagerId()==null?null:d.getManagerId().toString());s.put("hrVersion",d.getHrVersion());
            s.put("mergedIntoDeptId",d.getMergedIntoDeptId()==null?null:d.getMergedIntoDeptId().toString());return s;
        }).toList();
    }
    private Map<String,Object> evidence(Object before,Object after,Set<Long> changed,Object users,String reason) {
        return Map.of("before",before,"after",after,"affectedDepartmentIds",changed.stream().map(Object::toString).toList(),"movedUsers",users,"reasonOrEventId",reason);
    }
    private void fail(ErrorCode code) { throw new BusinessException(code); }
}
