package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.department.DepartmentCollaboration;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.PermissionService;
import com.whatname.iam.service.permission.SignedDataType;
import java.util.*;
import org.springframework.stereotype.Service;

/** Production D adapter for A's existing ManagementDataSecurity port. */
@Service
public final class DManagementDataSecurity implements ManagementDataSecurity {
    private final DataSignatureService signatures;
    private final PermissionService permissions;
    private final AuditService audit;
    public DManagementDataSecurity(DataSignatureService signatures, PermissionService permissions, AuditService audit) {
        this.signatures=signatures; this.permissions=permissions; this.audit=audit;
    }
    @Override public void verifyIdentity(UserAccount user) {
        if (user==null || user.id<=0) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        signatures.verify(SignedDataType.USER_IDENTITY,user.id);
        signatures.verify(SignedDataType.USER_GRANTS,user.id);
    }
    @Override public void requireFunction(long actorId,String permission) { permissions.requireFunction(actorId,permission); }
    @Override public void signIdentity(UserAccount user) {
        if (user==null || user.id<=0) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        signatures.sign(SignedDataType.USER_IDENTITY,user.id);
        signatures.sign(SignedDataType.USER_GRANTS,user.id);
    }
    @Override public void audit(String operation,Long actorId,Long targetId,String reason) {
        audit.change(operation,actorId,"USER",targetId,null,Map.of("reason",reason==null?"":reason));
    }
    @Override public void verifyOrganization(List<Department> organization) {
        if (organization==null) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        signatures.verify(SignedDataType.ORGANIZATION,0);
    }
    @Override public void verifyUsers(List<Map<String,Object>> users) {
        if (users==null) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        for (Map<String,Object> row:users) signatures.verify(SignedDataType.USER_IDENTITY,id(row,"userId","id"));
    }
    @Override public void signOrganization(String operation,Object actor,Map<String,Object> evidence) {
        signatures.sign(SignedDataType.ORGANIZATION,0);
        for (long id:ids(evidence,"affectedDepartmentIds")) signatures.sign(SignedDataType.DEPARTMENT,id);
        for (long id:userIds(evidence)) signatures.sign(SignedDataType.USER_IDENTITY,id);
        Long actorId=actor instanceof DepartmentCollaboration.Manager m && m.session()!=null ? m.session().userId() : null;
        boolean userTarget=evidence!=null && evidence.get("userId")!=null;
        Long target=userTarget ? firstId(evidence,"userId") : firstId(evidence,"targetId");
        if (target==null && !userTarget) target=firstCollectionId(evidence,"affectedDepartmentIds");
        String targetType=userTarget ? "USER" : "DEPARTMENT";
        String externalEventId=evidence==null ? null : stringValue(evidence.get("eventId"));
        audit.change(operation,actorId,target,targetType,externalEventId,evidence==null?Map.of():evidence);
    }
    private static long id(Map<String,Object> row,String... keys) {
        for(String key:keys) if(row!=null && row.get(key)!=null) return parse(row.get(key));
        throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
    }
    private static List<Long> ids(Map<String,Object> evidence,String key) {
        Object value=evidence==null?null:evidence.get(key); if(!(value instanceof Collection<?> c)) return List.of();
        return c.stream().map(DManagementDataSecurity::parse).distinct().sorted().toList();
    }
    private static List<Long> userIds(Map<String,Object> evidence) {
        Object moved=evidence==null?null:evidence.get("movedUsers");
        List<Long> out=new ArrayList<>();
        if(moved instanceof Collection<?> c) for(Object value:c) if(value instanceof Map<?,?> m) {
            Object id=m.get("userId"); if(id==null) id=m.get("id"); if(id!=null) out.add(parse(id));
        }
        Object direct=evidence==null?null:evidence.get("userId"); if(direct!=null) out.add(parse(direct));
        return out.stream().distinct().sorted().toList();
    }
    private static Long firstId(Map<String,Object> e,String... keys){ try{return id(e,keys);}catch(BusinessException x){return null;} }
    private static Long firstCollectionId(Map<String,Object> e,String key) {
        List<Long> values=ids(e,key); return values.isEmpty()?null:values.getFirst();
    }
    private static String stringValue(Object value) {
        if (value==null) return null;
        String result=String.valueOf(value);
        return result.isBlank() ? null : result;
    }
    private static long parse(Object value){ try{return Long.parseLong(String.valueOf(value));}catch(Exception e){throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);} }
}
