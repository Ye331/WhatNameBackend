package com.whatname.iam.service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.SensitiveDataCodec;
import com.whatname.iam.mapper.event.ExternalEventMapper;
import com.whatname.iam.service.department.DepartmentCollaboration;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** No proxy self-invocation: every boundary is an explicit TransactionTemplate.
 * Call outside a caller transaction: receive/failure are independent short transactions;
 * callback, signatures/audit and APPLIED are one SERIALIZABLE transaction.
 */
@Service
public class ExternalEventService {
    public record Command(String eventId, String eventType, Long eventVersion, String targetType,
                          String targetRef, OffsetDateTime occurredAt, Object businessPayload) {
        @Override public String toString() { return "ExternalEventCommand[redacted]"; }
    }
    private record Claim(long id, String token, Map<String,Object> replay) { }
    private final ExternalEventMapper mapper;
    private final SensitiveDataCodec codec;
    private final DepartmentCollaboration integration;
    private final ObjectMapper json;
    private final TransactionTemplate shortTx;
    private final TransactionTemplate businessTx;

    public ExternalEventService(ExternalEventMapper mapper, SensitiveDataCodec codec,
            DepartmentCollaboration integration, ObjectMapper json, PlatformTransactionManager tm) {
        this.mapper=mapper; this.codec=codec; this.integration=integration; this.json=json;
        shortTx=new TransactionTemplate(tm);
        shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        shortTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        shortTx.setTimeout(15);
        businessTx=new TransactionTemplate(tm);
        businessTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        businessTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        businessTx.setTimeout(30);
    }

    public Map<String,Object> execute(TrustedEventContext context, Command command,
                                      Supplier<Map<String,Object>> applyAndAudit) {
        return run(context,command,applyAndAudit,false);
    }

    /** Explicit operator-authorized recovery only. Row locking waits for an active business
     * transaction; rotating the token fences a crashed/delayed former owner. APPLIED never reruns.
     * Caller must use the handler registered for this event type, never a client-provided callback.
     */
    public Map<String,Object> recover(TrustedEventContext context, Command command,
                                      Supplier<Map<String,Object>> applyAndAudit) {
        integration.authorizeRecovery(context,command.eventType());
        return run(context,command,applyAndAudit,true);
    }

    private Map<String,Object> run(TrustedEventContext ctx, Command c,
                                  Supplier<Map<String,Object>> work, boolean recovery) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Event orchestration must be called outside a caller transaction");
        validate(ctx,c);
        integration.verifyTrustedEvent(ctx,c.eventType());
        String canonical=canonical(ctx,c);
        String digest=digest(canonical);
        Claim claim;
        try { claim=claim(ctx,c,digest,canonical,recovery); }
        catch (DuplicateKeyException raced) { claim=claim(ctx,c,digest,canonical,recovery); }
        if (claim.replay()!=null) return claim.replay();
        Claim owned=claim;
        try {
            return businessTx.execute(tx -> {
                // Always event row first, then organization, then users, then C/D facts/audit.
                Map<String,Object> row=mapper.lock(ctx.sourceSystem(),c.eventId());
                if (row==null || !owned.token().equals(row.get("token")) || !"PROCESSING".equals(row.get("status")))
                    throw new BusinessException(ErrorCode.VERSION_CONFLICT);
                Map<String,Object> result=Objects.requireNonNull(work.get());
                assertSafeResult(json.valueToTree(result));
                String encoded=encode(result);
                if (encoded.length()>65536) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
                if (mapper.applied(owned.id(),owned.token(),encoded)!=1)
                    throw new BusinessException(ErrorCode.VERSION_CONFLICT);
                return result;
            });
        } catch (RuntimeException error) {
            ErrorCode code=error instanceof BusinessException b ? b.errorCode() : ErrorCode.AUTH_STATE_UNAVAILABLE;
            boolean definitive=code.status().is4xxClientError();
            try {
                shortTx.executeWithoutResult(tx -> mapper.failed(owned.id(),owned.token(),
                        definitive ? "REJECTED" : "PENDING_RECOVERY",code.name()));
            } catch (RuntimeException failureRecordUnavailable) {
                // Retained PROCESSING remains fenced; only explicit controlled recovery is allowed.
            }
            if (error instanceof BusinessException) throw error;
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
    }

    private Claim claim(TrustedEventContext ctx, Command c, String digest,String plaintext,boolean recovery) {
        return shortTx.execute(tx -> {
            Map<String,Object> row=mapper.lock(ctx.sourceSystem(),c.eventId());
            if (row!=null) {
                if (!digest.equals(row.get("digest"))) throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                String status=(String)row.get("status");
                if ("APPLIED".equals(status)) return new Claim(id(row),null,decode((String)row.get("result")));
                if ("REJECTED".equals(status)) {
                    ErrorCode code;
                    try { code=ErrorCode.valueOf((String)row.get("summary")); }
                    catch (RuntimeException e) { code=ErrorCode.BUSINESS_RULE_VIOLATION; }
                    throw new BusinessException(code);
                }
                if (!recovery) throw new BusinessException("PROCESSING".equals(status)
                        ? ErrorCode.VERSION_CONFLICT : ErrorCode.AUTH_STATE_UNAVAILABLE);
                // Verify encrypted retained payload before recovering, not just a caller's digest.
                if (!plaintext.equals(codec.decrypt((String)row.get("ciphertext"))))
                    throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                String token=UUID.randomUUID().toString();
                if (mapper.recover(id(row),token)!=1) throw new BusinessException(ErrorCode.VERSION_CONFLICT);
                return new Claim(id(row),token,null);
            }
            if (recovery) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            String token=UUID.randomUUID().toString();
            mapper.insert(ctx.sourceSystem(),c.eventId(),c.eventType(),c.eventVersion(),c.targetType(),
                    c.targetRef(),digest,codec.encrypt(plaintext),c.occurredAt()==null?null:
                    c.occurredAt().withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime(),token);
            return new Claim(id(mapper.lock(ctx.sourceSystem(),c.eventId())),token,null);
        });
    }
    private static long id(Map<String,Object> row) { return ((Number)row.get("id")).longValue(); }
    private void validate(TrustedEventContext ctx,Command c) {
        if (ctx==null || c==null || !("HR".equals(ctx.sourceSystem()) || "OA".equals(ctx.sourceSystem())))
            throw new BusinessException(ErrorCode.INVALID_SERVICE);
        if (c.eventId()==null || c.eventId().isBlank() || c.eventId().length()>128 || c.eventId().chars().anyMatch(Character::isISOControl)
                || c.eventType()==null || !c.eventType().matches("[A-Z0-9_]{1,64}")
                || c.targetRef()==null || c.targetRef().isBlank() || c.targetRef().length()>64
                || !("USER".equals(c.targetType()) || "DEPT".equals(c.targetType()))
                || c.businessPayload()==null || ("HR".equals(ctx.sourceSystem()) && c.occurredAt()==null))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        if (("HR".equals(ctx.sourceSystem()) && c.eventVersion()==null)
                || (c.eventVersion()!=null && c.eventVersion()<=0))
            throw new BusinessException(ErrorCode.INVALID_HR_ORDER);
    }
    private String canonical(TrustedEventContext ctx,Command c) {
        Map<String,Object> body=new TreeMap<>();
        body.put("sourceSystem",ctx.sourceSystem()); body.put("eventId",c.eventId());
        body.put("eventType",c.eventType()); body.put("eventVersion",c.eventVersion());
        body.put("targetType",c.targetType()); body.put("targetRef",c.targetRef());
        body.put("occurredAt",c.occurredAt()==null?null:c.occurredAt().toInstant().toString());
        JsonNode payload=json.valueToTree(c.businessPayload());
        if (payload.isObject()) for(String transientField:List.of("nonce","requestId","signature","clientId","keyId","timestamp"))
            ((com.fasterxml.jackson.databind.node.ObjectNode)payload).remove(transientField);
        body.put("businessPayload",sorted(payload));
        String canonical=encode(body);
        if(canonical.length()>1_000_000) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        return canonical;
    }
    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String,Object> result=new TreeMap<>();
            node.fields().forEachRemaining(e -> result.put(e.getKey(),sorted(e.getValue()))); return result;
        }
        if (node.isArray()) { List<Object> a=new ArrayList<>(); node.forEach(n -> a.add(sorted(n))); return a; }
        return node;
    }
    private void assertSafeResult(JsonNode node) {
        if(node.isObject()) node.fields().forEachRemaining(e -> {
            String k=e.getKey().toLowerCase(Locale.ROOT);
            if(k.matches(".*(password|secret|token|phone|ciphertext|credential|realname).*") )
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            assertSafeResult(e.getValue());
        });
        else if(node.isArray()) node.forEach(this::assertSafeResult);
    }
    private String encode(Object o) {
        try { return json.writeValueAsString(o); }
        catch(JsonProcessingException e) { throw new BusinessException(ErrorCode.INVALID_ARGUMENT); }
    }
    private Map<String,Object> decode(String s) {
        try { return json.readValue(s,new TypeReference<>(){}); }
        catch(JsonProcessingException e) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
    }
    private String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(); }
    }
}
