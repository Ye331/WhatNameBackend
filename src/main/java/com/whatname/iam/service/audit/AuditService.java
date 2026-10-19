package com.whatname.iam.service.audit;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.web.RequestIdFilter;
import com.whatname.iam.mapper.audit.AuditMapper;
import com.whatname.iam.service.permission.CanonicalEvidence;
import com.whatname.iam.service.permission.DataSignatureService;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.whatname.iam.common.api.ErrorCode.*;

/** Append-only writes in the business transaction. No raw request/response or credentials. */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AuditService {
    private final AuditMapper mapper;
    private final DataSignatureService signatures;
    public AuditService(AuditMapper mapper, DataSignatureService signatures) { this.mapper = mapper; this.signatures = signatures; }

    public void decision(Long userId, long appId, Long apiId, boolean allowed, String reason,
                         String method, String route, Map<String,Object> evidence) {
        var entry = base(userId, "AUTH_CHECK", "USER", userId);
        entry.put("appId", appId); entry.put("apiId", apiId);
        entry.put("authAt", LocalDateTime.now(ZoneOffset.ofHours(8)));
        entry.put("allowed", allowed ? 1 : 0); entry.put("reason", reason);
        entry.put("method", method); entry.put("route", route);
        persist(entry, evidence);
    }

    /** Evidence must contain only the caller's selected non-sensitive before/after facts. */
    public void change(String operation, Long actorId, String targetType, Long targetId,
                       String externalEventId, Map<String,Object> evidence) {
        if (operation == null || !operation.matches("[A-Z][A-Z0-9_]{0,63}") || "AUTH_CHECK".equals(operation)
                || targetType == null || !targetType.matches("[A-Z][A-Z0-9_]{0,63}")
                || (externalEventId != null && (externalEventId.isBlank() || externalEventId.length() > 128)))
            throw new BusinessException(INVALID_ARGUMENT);
        var entry = base(actorId, operation, targetType, targetId);
        entry.put("externalEventId", externalEventId); entry.put("status", 1);
        persist(entry, evidence);
    }

    public void login(Long userId, int type, boolean success, String reasonCode) {
        if ((type != 1 && type != 2) || (reasonCode != null && !reasonCode.matches("[A-Z0-9_]{1,64}")))
            throw new BusinessException(INVALID_ARGUMENT);
        try { mapper.login(userId, type, success ? 1 : 0, reasonCode); }
        catch (RuntimeException failure) { throw new BusinessException(AUDIT_UNAVAILABLE); }
    }

    private Map<String,Object> base(Long actorId, String operation, String targetType, Long targetId) {
        var entry = new LinkedHashMap<String,Object>();
        String requestId = MDC.get(RequestIdFilter.MDC_KEY);
        if (requestId == null || !requestId.matches("[A-Za-z0-9._-]{1,64}")) requestId = UUID.randomUUID().toString();
        entry.put("actorId", actorId); entry.put("requestId", requestId);
        entry.put("operation", operation); entry.put("targetType", targetType); entry.put("targetId", targetId);
        entry.put("source", "IAM");
        return entry;
    }
    private void persist(Map<String,Object> entry, Map<String,Object> evidence) {
        try {
            rejectSensitive(evidence);
            var retained = new LinkedHashMap<String,Object>(evidence);
            // Bind log metadata as well as decision facts; clients cannot swap a decision's owner.
            var metadata = new LinkedHashMap<String,Object>(entry);
            if (metadata.get("authAt") instanceof LocalDateTime time) metadata.put("authAt", time.toString());
            retained.put("metadata", metadata);
            retained.put("seal", signatures.seal(retained));
            String encoded = CanonicalEvidence.json(retained);
            if (encoded.length() > 1_000_000) throw new BusinessException(AUDIT_UNAVAILABLE);
            entry.put("evidence", encoded); mapper.insert(entry);
        } catch (RuntimeException failure) { throw new BusinessException(AUDIT_UNAVAILABLE, failure); }
    }

    private void rejectSensitive(Object value) {
        if (value instanceof Map<?,?> map) {
            for (var item : map.entrySet()) {
                String key = item.getKey().toString().replace("_", "").toLowerCase(Locale.ROOT);
                if (key.contains("password") || key.contains("ciphertext") || key.contains("phone")
                        || key.contains("credential") || key.contains("privatekey") || key.contains("token") || key.contains("sms"))
                    throw new BusinessException(INVALID_ARGUMENT);
                rejectSensitive(item.getValue());
            }
        } else if (value instanceof Collection<?> items) items.forEach(this::rejectSensitive);
    }
}
