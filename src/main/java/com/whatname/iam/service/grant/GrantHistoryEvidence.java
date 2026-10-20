package com.whatname.iam.service.grant;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.GrantDtos.HistoryItem;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static com.whatname.iam.common.api.ErrorCode.INTEGRITY_FAILURE;

/** 从 D 已验签的历史快照提取来源差量，不读取当前授权事实。 */
final class GrantHistoryEvidence {
    private GrantHistoryEvidence() { }
    record Change(HistoryItem item,long roleId,long sourceId,String sourceType,Long department,Instant at) { }
    static List<Change> changes(Map<String,Object> row,Map<String,Object> retained) {
        try {
            var metadata=map(retained.get("metadata"));
            require(Objects.equals(metadata.get("source"),row.get("source_system"))
                && Objects.equals(metadata.get("externalEventId"),row.get("external_event_id"))
                && "1".equals(text(metadata.get("status"))) && "1".equals(text(row.get("status"))));
            long user=number(row.get("target_id"));
            var before=map(retained.get("before")); var after=map(retained.get("after"));
            require(number(before.get("userId"))==user && number(after.get("userId"))==user);
            var old=sources(before,user); var next=sources(after,user);
            require(next.keySet().containsAll(old.keySet()));
            var changes=new ArrayList<Change>();
            for(var entry:next.entrySet()) {
                var previous=old.get(entry.getKey()); var source=entry.getValue();
                String operation=null;
                if(previous==null) { require("1".equals(text(source.get("status")))); operation="GRANT"; }
                else if(!previous.equals(source)) {
                    require("1".equals(text(previous.get("status"))) && "0".equals(text(source.get("status"))));
                    var oldImmutable=new LinkedHashMap<>(previous); var newImmutable=new LinkedHashMap<>(source);
                    for(String field:List.of("status","revokedAt","revokedBy")) { oldImmutable.remove(field); newImmutable.remove(field); }
                    require(oldImmutable.equals(newImmutable)); operation="REVOKE";
                }
                if(operation!=null) {
                    var at=LocalDateTime.parse((String)source.get(operation.equals("GRANT")?"createdAt":"revokedAt")).atOffset(ZoneOffset.ofHours(8));
                    long role=number(source.get("roleId"));
                    changes.add(new Change(new HistoryItem(text(row.get("id")),Long.toString(user),Long.toString(role),
                        Long.toString(entry.getKey()),operation,DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(at)),role,entry.getKey(),
                        (String)source.get("sourceType"),source.get("sourceDeptId")==null?null:number(source.get("sourceDeptId")),at.toInstant()));
                }
            }
            long oldRevision=number(before.get("grantRevision")), newRevision=number(after.get("grantRevision"));
            require(changes.isEmpty()?oldRevision==newRevision:oldRevision<Long.MAX_VALUE && newRevision==oldRevision+1);
            String operation=(String)row.get("operation_type");
            switch(operation) {
                case "USER_ROLE_GRANT","USER_ROLE_REVOKE" -> {
                    require("C_CHANGE_V1".equals(retained.get("schema")) && changes.size()==1);
                    var c=changes.getFirst();
                    require(c.sourceId()==number(retained.get("sourceId")) && c.roleId()==number(retained.get("roleId"))
                        && c.item().operation().equals(operation.equals("USER_ROLE_GRANT")?"GRANT":"REVOKE"));
                }
                case "HR_POSITION_REVOKE","HR_GRANTS_REVOKE_ALL" -> {
                    require("C_HR_GRANT_CHANGE_V1".equals(retained.get("schema")) && !changes.isEmpty());
                    origin(retained,"HR",row.get("external_event_id"));
                    var ids=new HashSet<Long>();
                    for(Object value:list(retained.get("revokedSourceIds"))) require(ids.add(number(value)));
                    require(ids.equals(changes.stream().map(Change::sourceId).collect(java.util.stream.Collectors.toSet())));
                    for(var c:changes) {
                        require(c.item().operation().equals("REVOKE"));
                        if(operation.equals("HR_POSITION_REVOKE")) require("DEPT_POSITION".equals(c.sourceType())
                            && Objects.equals(c.department(),number(retained.get("oldDepartmentId"))));
                    }
                }
                case "OA_DECISION" -> {
                    require("C_OA_CHANGE_V1".equals(retained.get("schema")) && retained.get("changed") instanceof Boolean);
                    origin(retained,"OA",row.get("external_event_id"));
                    var decision=map(retained.get("decision"));
                    long source=number(decision.get("grantSourceId")), role=number(decision.get("roleId"));
                    require(number(decision.get("targetUserId"))==user && Set.of("GRANT","REVOKE").contains(decision.get("operation")));
                    if(Boolean.TRUE.equals(retained.get("changed"))) {
                        require(changes.size()==1);
                        var c=changes.getFirst();
                        require(c.sourceId()==source && c.roleId()==role && c.item().operation().equals(decision.get("operation")) && "OA_APPROVAL".equals(c.sourceType()));
                    } else {
                        require(changes.isEmpty() && old.equals(next));
                        var existing=next.get(source);
                        require(existing!=null && number(existing.get("roleId"))==role && "OA_APPROVAL".equals(existing.get("sourceType")));
                    }
                }
                default -> throw new BusinessException(INTEGRITY_FAILURE);
            }
            return List.copyOf(changes);
        } catch(BusinessException failure) { throw failure; }
        catch(RuntimeException failure) { throw new BusinessException(INTEGRITY_FAILURE); }
    }
    private static SortedMap<Long,Map<String,Object>> sources(Map<String,Object> snapshot,long user) {
        var result=new TreeMap<Long,Map<String,Object>>();
        for(Object value:list(snapshot.get("sources"))) {
            var source=map(value); long id=number(source.get("id"));
            require(number(source.get("userId"))==user && result.put(id,source)==null);
            number(source.get("roleId"));
            require(Set.of("MANUAL","DEPT_POSITION","OA_APPROVAL").contains(source.get("sourceType")));
            require(source.get("sourceRef") instanceof String ref && !ref.isBlank() && ref.length()<=128);
            require(Set.of("0","1").contains(text(source.get("status"))));
            require(source.get("createdAt") instanceof String); LocalDateTime.parse((String)source.get("createdAt"));
            if("DEPT_POSITION".equals(source.get("sourceType"))) number(source.get("sourceDeptId"));
            else require(source.get("sourceDeptId")==null);
            if("0".equals(text(source.get("status")))) {
                require(source.get("revokedAt") instanceof String); LocalDateTime.parse((String)source.get("revokedAt"));
            } else require(source.get("revokedAt")==null && source.get("revokedBy")==null);
        }
        var supported=new HashSet<Long>();
        for(var source:result.values()) if("1".equals(text(source.get("status")))) supported.add(number(source.get("roleId")));
        var roles=new HashSet<Long>();
        for(Object role:list(snapshot.get("roles"))) require(roles.add(number(role)));
        require(roles.equals(supported));
        return result;
    }
    private static void origin(Map<String,Object> retained,String system,Object event) {
        var origin=map(retained.get("origin"));
        require(event instanceof String && system.equals(origin.get("sourceSystem")) && event.equals(origin.get("eventId")));
    }
    @SuppressWarnings("unchecked") private static Map<String,Object> map(Object value) {
        require(value instanceof Map<?,?>); return (Map<String,Object>)value;
    }
    private static List<?> list(Object value) { require(value instanceof List<?>); return (List<?>)value; }
    private static String text(Object value) { return value==null?null:value.toString(); }
    private static long number(Object value) {
        String string=text(value); require(string!=null && string.matches("[1-9][0-9]{0,18}")); return Long.parseLong(string);
    }
    private static void require(boolean condition) { if(!condition) throw new BusinessException(INTEGRITY_FAILURE); }
}
