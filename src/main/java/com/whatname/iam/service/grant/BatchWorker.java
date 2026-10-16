package com.whatname.iam.service.grant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.entity.grant.BatchData.Batch;
import com.whatname.iam.mapper.grant.BatchMapper;
import com.whatname.iam.mapper.grant.GrantMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE;

/** First-stage dispatch only: no startup scan or automatic retry. */
@Service
public class BatchWorker {
    private static final Logger log = LoggerFactory.getLogger(BatchWorker.class);
    private final BatchMapper mapper;
    private final GrantOperations grants;
    private final GrantMapper grantMapper;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    public BatchWorker(BatchMapper mapper, GrantOperations grants, GrantMapper grantMapper, ObjectMapper json, PlatformTransactionManager manager) {
        this.mapper = mapper; this.grants = grants; this.grantMapper = grantMapper; this.json = json;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public void run(long batchId) {
        try {
            Batch batch = transaction.execute(tx -> {
                var found = mapper.batch(batchId);
                if (found != null) mapper.start(batchId);
                return found;
            });
            if (batch == null) return;
            var items = transaction.execute(tx -> mapper.items(batchId));
            for (var item : items) {
                if (!"PENDING".equals(item.processingStatus())) continue;
                try {
                    transaction.executeWithoutResult(tx -> apply(batch, item.id()));
                } catch (BusinessException failure) {
                    // The failed business transaction has already rolled back, including any evidence writes.
                    transaction.executeWithoutResult(tx -> {
                        var current = mapper.lockItem(item.id());
                        if (current != null && "PENDING".equals(current.processingStatus())) mapper.failed(item.id(), failure.errorCode().name());
                    });
                }
            }
            transaction.executeWithoutResult(tx -> mapper.refresh(batchId));
        } catch (RuntimeException failure) {
            // Preserve committed outcomes; uncertain/infrastructure failures remain inspectable, never retried here.
            log.warn("Batch {} stopped with unfinished work: {}", batchId, failure.getClass().getSimpleName());
        }
    }
    private void apply(Batch batch, long itemId) {
        var item = mapper.lockItem(itemId);
        if (item == null || !"PENDING".equals(item.processingStatus())) return;
        Object result;
        long source;
        if ("GRANT".equals(batch.operation())) {
            var applied = grants.grant(batch.actorUserId(), new GrantRequest(item.userId().toString(), batch.roleId().toString(),
                    item.sourceType(), item.sourceRef(), item.sourceDeptId() == null ? null : item.sourceDeptId().toString(), batch.reason(), null));
            source = Long.parseLong(applied.grantSourceId()); result = applied;
        } else if ("REVOKE".equals(batch.operation())) {
            source = item.grantSourceId();
            result = grants.revoke(batch.actorUserId(), new RevokeRequest(item.userId().toString(), batch.roleId().toString(),
                    Long.toString(source), batch.reason(), null));
        } else throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        try {
            ObjectNode evidence = json.valueToTree(result);
            if (result instanceof Revoked) {
                Long revision = grantMapper.selectRevision(item.userId(), true);
                if (revision == null || revision < 1) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
                evidence.put("permissionRevision", "v" + revision);
            }
            if (mapper.applied(itemId, source, json.writeValueAsString(evidence)) != 1) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot serialize batch result", e); }
    }
}
