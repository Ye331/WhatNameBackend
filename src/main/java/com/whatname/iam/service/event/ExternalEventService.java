package com.whatname.iam.service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.PhoneNumbers;
import com.whatname.iam.repository.event.ExternalEventRepository;
import com.whatname.iam.service.department.DepartmentAccessService;
import com.whatname.iam.service.permission.ServiceOaCaller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Supplier;

/**
 * No proxy self-invocation: every boundary is an explicit TransactionTemplate. Call outside a
 * caller transaction: receive/failure are independent short transactions; callback,
 * signatures/audit and APPLIED are one SERIALIZABLE transaction.
 */
@Service
public class ExternalEventService {
    private static final Logger log = LoggerFactory.getLogger(ExternalEventService.class);

    public record Command(
            String eventId,
            String eventType,
            Long eventVersion,
            String targetType,
            String targetRef,
            OffsetDateTime occurredAt,
            Object businessPayload) {
        @Override
        public String toString() {
            return "ExternalEventCommand[redacted]";
        }
    }

    private record Claim(long id, Map<String, Object> replay) {}

    private record FailureDisposition(Map<String, Object> replay, String state) {}

    private final ExternalEventRepository repository;

    private final DepartmentAccessService integration;
    private final ServiceOaCaller oa;
    private final ObjectMapper json;
    private final TransactionTemplate shortTx;
    private final TransactionTemplate businessTx;

    public ExternalEventService(
            ExternalEventRepository repository,

            DepartmentAccessService integration,
            ObjectMapper json,
            PlatformTransactionManager tm,
            ServiceOaCaller oa) {
        this.repository = repository;
        this.integration = integration;
        this.json = json;
        this.oa = oa;
        shortTx = new TransactionTemplate(tm);
        shortTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        shortTx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        shortTx.setTimeout(15);
        businessTx = new TransactionTemplate(tm);
        businessTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        businessTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        businessTx.setTimeout(30);
    }

    public Map<String, Object> execute(
            TrustedEventContext context,
            Command command,
            Supplier<Map<String, Object>> applyAndAudit) {
        return run(context, command, applyAndAudit, false);
    }

    /**
     * Existing terminal results may be replayed after recovery authorization. Nonterminal events
     * are refused: authorization and a row lock cannot prove that an earlier worker has stopped.
     * Offline recovery requires operators to stop event processing and verify that all former
     * workers and database transactions have ended; this method does not perform that procedure.
     */
    public Map<String, Object> recover(
            TrustedEventContext context,
            Command command,
            Supplier<Map<String, Object>> applyAndAudit) {
        return run(context, command, applyAndAudit, true);
    }

    private Map<String, Object> run(
            TrustedEventContext ctx,
            Command c,
            Supplier<Map<String, Object>> work,
            boolean recovery) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive())
            throw new IllegalStateException(
                    "Event orchestration must be called outside a caller transaction");
        validate(ctx, c);
        verifyTrustedEvent(ctx, c.eventType());
        if (recovery) {
            if ("OA".equals(ctx.sourceSystem())) oa.authorizeRecovery(ctx, c.eventType());
            else integration.authorizeRecovery(ctx, c.eventType());
        }
        String canonical = canonical(ctx, c);
        String digest = digest(canonical);
        Claim claim;
        try {
            try {
                claim = claim(ctx, c, digest, canonical, recovery);
            } catch (DuplicateKeyException raced) {
                claim = claim(ctx, c, digest, canonical, recovery);
            }
        } catch (RuntimeException receiptUnavailable) {
            if (receiptUnavailable instanceof BusinessException) throw receiptUnavailable;
            // An unknown receipt commit never grants permission to execute or to take over.
            log.warn(
                    "External event receipt unavailable source={} type={} failure={}",
                    ctx.sourceSystem(),
                    c.eventType(),
                    receiptUnavailable.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
        if (claim.replay() != null) return claim.replay();
        Claim owned = claim;
        try {
            return businessTx.execute(
                    tx -> {
                        // Event row first. Organization callbacks prepare the admin
                        // admin/department/role/list/user
                        // and resource locks before organization/person facts are read or changed.
                        Map<String, Object> row = repository.lock(ctx.sourceSystem(), c.eventId());
                        if ((row == null || row.isEmpty())
                                || owned.id() != id(row)
                                || !digest.equals(row.get("digest"))
                                || !"PROCESSING".equals(row.get("status")))
                            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
                        // Recheck the same source-specific authority in the actual write
                        // transaction.
                        // Only this private path can register the transaction-local execution
                        // evidence.
                        verifyTrustedEvent(ctx, c.eventType());
                        TransactionSynchronizationManager.registerSynchronization(
                                new Applying(ctx, c));
                        Map<String, Object> result = Objects.requireNonNull(work.get());
                        assertSafeResult(json.valueToTree(result));
                        String encoded = encode(result);
                        if (encoded.length() > 65536)
                            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
                        if (repository.applied(owned.id(), encoded) != 1)
                            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
                        return result;
                    });
        } catch (RuntimeException error) {
            ErrorCode code =
                    error instanceof BusinessException b
                            ? b.errorCode()
                            : ErrorCode.AUTH_STATE_UNAVAILABLE;
            boolean definitive = code.status().is4xxClientError();
            try {
                FailureDisposition disposition =
                        shortTx.execute(
                                tx -> {
                                    // A commit acknowledgement can be lost. Inspect the real locked
                                    // state before
                                    // recording a failure, and never replace an already committed
                                    // result.
                                    Map<String, Object> row =
                                            repository.lock(ctx.sourceSystem(), c.eventId());
                                    if ((row == null || row.isEmpty())
                                            || owned.id() != id(row)
                                            || !digest.equals(row.get("digest")))
                                        return new FailureDisposition(null, "UNMATCHED");
                                    String state = (String) row.get("status");
                                    if ("APPLIED".equals(state))
                                        return new FailureDisposition(
                                                decode((String) row.get("result")), state);
                                    if ("PROCESSING".equals(state)) {
                                        state = definitive ? "REJECTED" : "PENDING_RECOVERY";
                                        if (repository.failed(owned.id(), state, code.name()) != 1)
                                            throw new IllegalStateException(
                                                    "External event failure state was not saved");
                                    }
                                    return new FailureDisposition(null, state);
                                });
                if (disposition.replay() != null) return disposition.replay();
                if (!(error instanceof BusinessException)
                        || "UNMATCHED".equals(disposition.state()))
                    log.warn(
                            "External event failed rowId={} source={} type={} code={} failure={}"
                                    + " state={}",
                            owned.id(),
                            ctx.sourceSystem(),
                            c.eventType(),
                            code.name(),
                            error.getClass().getSimpleName(),
                            disposition.state());
            } catch (RuntimeException failureRecordUnavailable) {
                // Preserve the retained state. Ordinary retries and online recovery never take
                // over.
                log.warn(
                        "External event failure record unavailable rowId={} source={} type={}"
                                + " code={} failure={} recordFailure={}",
                        owned.id(),
                        ctx.sourceSystem(),
                        c.eventType(),
                        code.name(),
                        error.getClass().getSimpleName(),
                        failureRecordUnavailable.getClass().getSimpleName());
            }
            if (error instanceof BusinessException) throw error;
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
    }

    private void verifyTrustedEvent(TrustedEventContext context, String eventType) {
        if ("OA".equals(context.sourceSystem())) {
            if (!"OA_DECISION".equals(eventType))
                throw new BusinessException(ErrorCode.INVALID_SERVICE);
            oa.verifyTrustedEvent(context, eventType);
        } else {
            integration.verifyTrustedEvent(context, eventType);
        }
    }

    /**
     * Internal implementation detail, not a service-authentication or recovery entry point.
     * Synchronizations suspend with REQUIRES_NEW and disappear when this transaction ends.
     */
    public static Command applying(TrustedEventContext context, String eventType, String eventId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) return null;
        return TransactionSynchronizationManager.getSynchronizations().stream()
                .filter(
                        sync ->
                                sync instanceof Applying applying
                                        && applying.context.equals(context)
                                        && applying.command.eventType().equals(eventType)
                                        && applying.command.eventId().equals(eventId))
                .map(sync -> ((Applying) sync).command)
                .findFirst()
                .orElse(null);
    }

    private record Applying(TrustedEventContext context, Command command)
            implements TransactionSynchronization {}

    private Claim claim(
            TrustedEventContext ctx, Command c, String digest, String plaintext, boolean recovery) {
        return shortTx.execute(
                tx -> {
                    Map<String, Object> row = repository.lock(ctx.sourceSystem(), c.eventId());
                    if (row != null && !row.isEmpty()) {
                        if (!digest.equals(row.get("digest")))
                            throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
                        String status = (String) row.get("status");
                        if ("APPLIED".equals(status))
                            return new Claim(id(row), decode((String) row.get("result")));
                        if ("REJECTED".equals(status)) {
                            ErrorCode code;
                            try {
                                code = ErrorCode.valueOf((String) row.get("summary"));
                            } catch (RuntimeException e) {
                                code = ErrorCode.BUSINESS_RULE_VIOLATION;
                            }
                            throw new BusinessException(code);
                        }
                        if (!recovery)
                            throw new BusinessException(
                                    "PROCESSING".equals(status)
                                            ? ErrorCode.VERSION_CONFLICT
                                            : ErrorCode.AUTH_STATE_UNAVAILABLE);
                        // Verify retained evidence, but do not confuse authorization with proof of
                        // quiescence.
                        if (!plaintext.equals((String) row.get("payloadJson")))
                            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
                        throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
                    }
                    if (recovery) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
                    repository.insert(
                            ctx.sourceSystem(),
                            c.eventId(),
                            c.eventType(),
                            c.eventVersion(),
                            c.targetType(),
                            c.targetRef(),
                            digest,
                            (plaintext),
                            c.occurredAt() == null
                                    ? null
                                    : c.occurredAt()
                                            .withOffsetSameInstant(ZoneOffset.ofHours(8))
                                            .toLocalDateTime());
                    return new Claim(id(repository.lock(ctx.sourceSystem(), c.eventId())), null);
                });
    }

    private static long id(Map<String, Object> row) {
        return ((Number) row.get("id")).longValue();
    }

    private void validate(TrustedEventContext ctx, Command c) {
        if (ctx == null
                || c == null
                || !("HR".equals(ctx.sourceSystem()) || "OA".equals(ctx.sourceSystem())))
            throw new BusinessException(ErrorCode.INVALID_SERVICE);
        if (c.eventId() == null
                || c.eventId().isBlank()
                || c.eventId().length() > 128
                || c.eventId().chars().anyMatch(Character::isISOControl)
                || c.eventType() == null
                || !c.eventType().matches("[A-Z0-9_]{1,64}")
                || c.targetRef() == null
                || c.targetRef().isBlank()
                || c.targetRef().length() > 64
                || !("USER".equals(c.targetType()) || "DEPT".equals(c.targetType()))
                || c.businessPayload() == null
                || ("HR".equals(ctx.sourceSystem()) && c.occurredAt() == null))
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        if (("HR".equals(ctx.sourceSystem()) && c.eventVersion() == null)
                || (c.eventVersion() != null && c.eventVersion() <= 0))
            throw new BusinessException(ErrorCode.INVALID_HR_ORDER);
    }

    private String canonical(TrustedEventContext ctx, Command c) {
        Map<String, Object> body = new TreeMap<>();
        body.put("sourceSystem", ctx.sourceSystem());
        body.put("eventId", c.eventId());
        body.put("eventType", c.eventType());
        body.put("eventVersion", c.eventVersion());
        body.put("targetType", c.targetType());
        body.put("targetRef", c.targetRef());
        body.put(
                "occurredAt",
                c.occurredAt() == null ? null : c.occurredAt().toInstant().toString());
        JsonNode payload = json.valueToTree(c.businessPayload());
        if (payload.isObject())
            for (String transientField :
                    List.of("nonce", "requestId", "signature", "clientId", "keyId", "timestamp"))
                ((com.fasterxml.jackson.databind.node.ObjectNode) payload).remove(transientField);
        body.put("businessPayload", sorted(payload));
        String canonical = encode(body);
        if (canonical.length() > 1_000_000) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        return canonical;
    }

    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.fields().forEachRemaining(e -> result.put(e.getKey(), sorted(e.getValue())));
            return result;
        }
        if (node.isArray()) {
            List<Object> a = new ArrayList<>();
            node.forEach(n -> a.add(sorted(n)));
            return a;
        }
        return node;
    }

    private void assertSafeResult(JsonNode node) {
        if (node.isObject())
            node.fields()
                    .forEachRemaining(
                            e -> {
                                String k = e.getKey().toLowerCase(Locale.ROOT);
                                if (k.matches(
                                        ".*(password|secret|token|phone|payloadJson|credential|realname).*"))
                                    throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
                                assertSafeResult(e.getValue());
                            });
        else if (node.isArray()) node.forEach(this::assertSafeResult);
    }

    private String encode(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }

    private Map<String, Object> decode(String s) {
        try {
            return json.readValue(s, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException();
        }
    }
}
