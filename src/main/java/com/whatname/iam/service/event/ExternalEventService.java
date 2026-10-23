package com.whatname.iam.service.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.repository.event.ExternalEventRepository;
import com.whatname.iam.service.department.DepartmentAccessService;
import com.whatname.iam.service.permission.ServiceOaCaller;

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

/** Event ID deduplication and business changes commit together; failures roll back together. */
@Service
public class ExternalEventService {

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
    private final ExternalEventRepository repository;

    private final DepartmentAccessService integration;
    private final ServiceOaCaller oa;
    private final ObjectMapper json;
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
        businessTx = new TransactionTemplate(tm);
        businessTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        businessTx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        businessTx.setTimeout(30);
    }

    public synchronized Map<String, Object> execute(
            TrustedEventContext context, Command command,
            Supplier<Map<String, Object>> work) {
        if (TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Call event processing outside a caller transaction");
        validate(context, command);
        verifyTrustedEvent(context, command.eventType());
        String canonical = canonical(context, command);
        String fingerprint = digest(canonical);
        try {
            return businessTx.execute(tx -> apply(context, command, work, canonical, fingerprint));
        } catch (DuplicateKeyException concurrentInsert) {
            // Retry only receipt insertion races, after the first transaction has rolled back.
            return businessTx.execute(tx -> apply(context, command, work, canonical, fingerprint));
        } catch (BusinessException failure) {
            throw failure;
        } catch (RuntimeException unavailable) {
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE, unavailable);
        }
    }

    private Map<String, Object> apply(TrustedEventContext context, Command command,
            Supplier<Map<String, Object>> work, String canonical, String fingerprint) {
        verifyTrustedEvent(context, command.eventType());
        var row = repository.lock(context.sourceSystem(), command.eventId());
        if (row != null && !row.isEmpty()) {
            if (!fingerprint.equals(row.get("digest")))
                throw new BusinessException(ErrorCode.IDEMPOTENCY_CONFLICT);
            if ("APPLIED".equals(row.get("status"))) return decode((String) row.get("result"));
            // Old checkpoint data is never taken over automatically. Inspect it manually.
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }
        repository.insert(context.sourceSystem(), command.eventId(), command.eventType(),
                command.eventVersion(), command.targetType(), command.targetRef(), fingerprint,
                canonical, command.occurredAt() == null ? null
                        : command.occurredAt().withOffsetSameInstant(ZoneOffset.ofHours(8)).toLocalDateTime());
        row = repository.lock(context.sourceSystem(), command.eventId());
        TransactionSynchronizationManager.registerSynchronization(new Applying(context, command));
        Map<String, Object> result = Objects.requireNonNull(work.get());
        assertSafeResult(json.valueToTree(result));
        String encoded = encode(result);
        if (encoded.length() > 65536) throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        if (repository.applied(id(row), encoded) != 1)
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        return result;
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
