package com.whatname.iam.service.grant;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.BatchDtos.*;
import com.whatname.iam.entity.grant.BatchData;
import com.whatname.iam.mapper.grant.BatchMapper;
import com.whatname.iam.service.admin.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.*;
import java.util.concurrent.Executor;
import static com.whatname.iam.common.api.ErrorCode.*;

@Service
public class BatchService {
    private static final Logger log = LoggerFactory.getLogger(BatchService.class);
    private final BatchMapper mapper;
    private final AdminPolicyService policy;
    private final AdminRules rules;
    private final AdminPorts.Identity identity;
    private final AdminPorts.Functions functions;
    private final BatchWorker worker;
    private final Executor executor;
    private final TransactionTemplate write;
    private final TransactionTemplate read;
    public BatchService(BatchMapper mapper, AdminPolicyService policy, AdminRules rules, AdminPorts.Identity identity,
                        AdminPorts.Functions functions, BatchWorker worker, @Qualifier("grantBatchExecutor") Executor executor,
                        PlatformTransactionManager manager) {
        this.mapper = mapper; this.policy = policy; this.rules = rules; this.identity = identity;
        this.functions = functions; this.worker = worker; this.executor = executor;
        write = new TransactionTemplate(manager);
        write.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read = new TransactionTemplate(manager);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        read.setReadOnly(true);
    }
    public Submission submit(String key, Request request) {
        var input = BatchInput.parse(key, request);
        long actor = actor();
        try {
            return write.execute(tx -> {
                policy.requireAdministrator(actor);
                functions.require(actor, "IAM_GRANTS_WRITE");
                var existing = mapper.byKey(actor, key);
                if (existing != null) return replay(actor, input, existing);
                policy.requireDelegableRole(actor, input.roleId());
                mapper.insertBatch(actor, key, input.digest(), input.operation(), input.roleId(), input.reason(), input.targets().size());
                var batch = mapper.byKey(actor, key);
                if (batch == null) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
                var items = input.targets().stream().map(t -> new BatchData.Item(null, batch.id(), BatchInput.id(t.userId()),
                        t.sourceType(), t.sourceRef(), t.sourceDeptId() == null ? null : BatchInput.id(t.sourceDeptId()),
                        t.grantSourceId() == null ? null : BatchInput.id(t.grantSourceId()), "PENDING", null, null, 0)).toList();
                for (int offset = 0; offset < items.size(); offset += 500) {
                    var chunk = items.subList(offset, Math.min(offset + 500, items.size()));
                    if (mapper.insertItems(chunk) != chunk.size()) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
                }
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() {
                        try { executor.execute(() -> worker.run(batch.id())); }
                        catch (RuntimeException failure) {
                            log.warn("Batch {} persisted but not dispatched: {}", batch.id(), failure.getClass().getSimpleName());
                        }
                    }
                });
                return new Submission(true, new Accepted(batch.id().toString(), "PENDING", items.size()));
            });
        } catch (DuplicateKeyException race) {
            // A unique-key loser must read the winner in a fresh transaction, not its failed snapshot.
            return read.execute(tx -> {
                functions.require(actor, "IAM_GRANTS_WRITE");
                var batch = mapper.byKey(actor, key);
                if (batch == null) throw race;
                return replay(actor, input, batch);
            });
        }
    }
    public Result result(String batchId, int page, int pageSize) {
        long batch = BatchInput.id(batchId), actor = actor();
        if (page < 1 || pageSize < 1 || pageSize > 500) throw new BusinessException(INVALID_ARGUMENT);
        return read.execute(tx -> view(actor, batch, page, pageSize));
    }
    private Submission replay(long actor, BatchInput input, BatchData.Batch batch) {
        if (!batch.payloadDigest().equals(input.digest())) throw new BusinessException(IDEMPOTENCY_CONFLICT);
        return new Submission(false, view(actor, batch.id(), 1, 20));
    }
    private Result view(long actor, long batchId, int page, int size) {
        functions.require(actor, "IAM_GRANTS_VIEW");
        var batch = mapper.batch(batchId);
        if (batch == null) throw new BusinessException(RESOURCE_NOT_FOUND);
        var all = mapper.items(batchId);
        var authority = policy.requireBatchReadContext(actor, all.stream().map(BatchData.Item::userId).toList());
        var appointment = rules.requireActive(authority, actor);
        var visible = all.stream().filter(i -> {
            var user = authority.users().get(i.userId());
            return user != null && rules.contains(authority, appointment, user.deptId());
        }).toList();
        if (visible.isEmpty()) throw new BusinessException(RESOURCE_NOT_FOUND);
        int succeeded = 0, failed = 0, pending = 0;
        for (var item : visible) {
            switch (item.processingStatus()) {
                case "APPLIED" -> succeeded++;
                case "FAILED" -> failed++;
                case "PENDING" -> pending++;
                default -> throw new BusinessException(AUTH_STATE_UNAVAILABLE);
            }
        }
        String status = batch.processingStatus();
        // Completed facts remain authoritative even if the worker stopped before refreshing the header.
        if (all.stream().noneMatch(i -> "PENDING".equals(i.processingStatus()))) {
            long applied = all.stream().filter(i -> "APPLIED".equals(i.processingStatus())).count();
            status = applied == all.size() ? "COMPLETED" : applied == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        }
        var items = visible.stream().skip(((long) page - 1) * size).limit(size)
                .map(i -> new Item(i.userId().toString(), i.processingStatus(),
                        "APPLIED".equals(i.processingStatus()) && i.grantSourceId() != null ? i.grantSourceId().toString() : null,
                        "FAILED".equals(i.processingStatus()) ? i.resultCode() : null)).toList();
        return new Result(batch.id().toString(), status, succeeded, failed, pending, items, page, size, visible.size());
    }
    private long actor() {
        long actor = identity.currentUserId();
        if (actor <= 0) throw new BusinessException(INVALID_TOKEN);
        return actor;
    }
}
