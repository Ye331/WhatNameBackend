package com.whatname.iam.service.audit;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.audit.AuditMapper;
import com.whatname.iam.service.permission.*;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.whatname.iam.common.api.ErrorCode.*;

@Service
@Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
public class AuditQueryService {
    private final AuditMapper mapper;
    private final PermissionReadScope scope;
    private final DataSignatureService signatures;
    private final ObjectMapper json;
    public AuditQueryService(AuditMapper mapper, PermissionReadScope scope, DataSignatureService signatures, ObjectMapper json) {
        this.mapper = mapper; this.scope = scope; this.signatures = signatures; this.json = json;
    }
    public Map<String,Object> operations(String authorization, boolean authorizations, String userId, int page, int size) {
        page(page, size);
        Long user = userId == null ? null : UserPermissionService.id(userId);
        var permitted = scope.departments(authorization, "IAM_AUDIT_VIEW");
        if (permitted.departments().isEmpty()) return paged(List.of(), page, size, 0);
        var items = mapper.operations(permitted.departments(), permitted.global(), authorizations, user, ((long) page - 1) * size, size)
                .stream().map(row -> operationView(row, authorizations)).toList();
        return paged(items, page, size, mapper.operationCount(permitted.departments(), permitted.global(), authorizations, user));
    }
    public Map<String,Object> logins(String authorization, String userId, int page, int size) {
        page(page, size);
        Long user = userId == null ? null : UserPermissionService.id(userId);
        var permitted = scope.departments(authorization, "IAM_AUDIT_VIEW");
        if (permitted.departments().isEmpty()) return paged(List.of(), page, size, 0);
        var items = mapper.logins(permitted.departments(), permitted.global(), user, ((long) page - 1) * size, size).stream().map(row -> {
            var view = new LinkedHashMap<String,Object>();
            view.put("userId", string(row.get("user_id")));
            Integer type = integer(row.get("login_type"));
            view.put("loginMethod", Objects.equals(type, 1) ? "USERNAME_PASSWORD" : Objects.equals(type, 2) ? "PHONE_PASSWORD" : null);
            view.put("success", Objects.equals(integer(row.get("login_status")), 1)); view.put("occurredAt", time(row.get("created_at")));
            return view;
        }).toList();
        return paged(items, page, size, mapper.loginCount(permitted.departments(), permitted.global(), user));
    }
    /** eventId is the decimal operation-log primary key; external HR/OA IDs remain in sealed metadata. */
    public Map<String,Object> evidence(String authorization, String eventId) {
        long id = UserPermissionService.id(eventId);
        var permitted = scope.departments(authorization, "IAM_AUDIT_VIEW");
        if (permitted.departments().isEmpty() || mapper.visible(id, permitted.departments(), permitted.global()) != 1)
            throw new BusinessException(RESOURCE_NOT_FOUND);
        var row = mapper.operation(id);
        var retained = evidence(row);
        var result = new LinkedHashMap<String,Object>();
        result.put("eventId", eventId); result.put("requestId", row.get("request_id"));
        result.put("evidenceType", row.get("operation_type")); result.put("permissionVersion", retained.get("permissionVersion"));
        result.put("integrityVerified", true); result.put("evidence", retained);
        return result;
    }
    private Map<String,Object> operationView(Map<String,Object> row, boolean authorization) {
        var result = new LinkedHashMap<String,Object>();
        result.put("eventId", string(row.get("id"))); result.put("occurredAt", time(row.get("created_at")));
        if (authorization) {
            var retained = evidence(row);
            result.put("decisionId", retained.get("decisionId")); result.put("userId", string(row.get("user_id")));
            result.put("apiId", string(row.get("api_id"))); result.put("allowed", Objects.equals(integer(row.get("auth_decision")), 1));
            result.put("permissionVersion", retained.get("permissionVersion"));
        } else {
            result.put("operationType", row.get("operation_type")); result.put("actorUserId", string(row.get("user_id")));
            result.put("targetId", string(row.get("target_id")));
        }
        return result;
    }
    @SuppressWarnings("unchecked")
    private Map<String,Object> evidence(Map<String,Object> row) {
        try {
            var retained = json.readValue((String) row.get("evidence"), new TypeReference<Map<String,Object>>() {});
            var seal = (Map<String,Object>) retained.remove("seal");
            if (!signatures.verifySeal(retained, seal)) throw new BusinessException(INTEGRITY_FAILURE);
            var metadata = (Map<String,Object>) retained.get("metadata");
            if (!Objects.equals(metadata.get("requestId"), row.get("request_id"))
                    || !Objects.equals(metadata.get("actorId"), string(row.get("user_id")))
                    || !Objects.equals(metadata.get("operation"), row.get("operation_type"))
                    || !Objects.equals(metadata.get("targetType"), row.get("target_type"))
                    || !Objects.equals(metadata.get("targetId"), string(row.get("target_id")))
                    || !Objects.equals(metadata.get("appId"), string(row.get("app_id")))
                    || !Objects.equals(metadata.get("apiId"), string(row.get("api_id")))
                    || !Objects.equals(metadata.get("allowed"), string(row.get("auth_decision")))
                    || !Objects.equals(metadata.get("reason"), row.get("auth_reason")))
                throw new BusinessException(INTEGRITY_FAILURE);
            retained.put("seal", seal); return retained;
        } catch (BusinessException error) { throw error; }
        catch (Exception error) { throw new BusinessException(INTEGRITY_FAILURE); }
    }
    private void page(int page, int size) {
        if (page < 1 || page > 1_000_000 || size < 1 || size > 500) throw new BusinessException(INVALID_ARGUMENT);
    }
    private Map<String,Object> paged(List<?> items, int page, int size, long total) {
        return Map.of("items", items, "page", page, "pageSize", size, "total", total);
    }
    private static String string(Object value) { return value == null ? null : value.toString(); }
    private static Integer integer(Object value) { return value == null ? null : ((Number) value).intValue(); }
    private static String time(Object value) {
        LocalDateTime local = value instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) value;
        return local.atOffset(ZoneOffset.ofHours(8)).toString();
    }
}
