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
import org.springframework.dao.TransientDataAccessException;
import java.sql.SQLException;
import static com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE;

/** 任务锁仅保护调度；次数预留、每项业务和失败结果各自独立提交。 */
@Service
public class BatchWorker {
    private static final Logger log = LoggerFactory.getLogger(BatchWorker.class);
    private final BatchMapper mapper;
    private final GrantOperations grants;
    private final GrantMapper grantMapper;
    private final ObjectMapper json;
    private final TransactionTemplate transaction;
    private final TransactionTemplate guard;
    public BatchWorker(BatchMapper mapper, GrantOperations grants, GrantMapper grantMapper, ObjectMapper json, PlatformTransactionManager manager) {
        this.mapper = mapper;
        this.grants = grants;
        this.grantMapper = grantMapper;
        this.json = json;
        this.transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        guard = new TransactionTemplate(manager);
        guard.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public void run(long batchId) {
        try {
            guard.executeWithoutResult(task -> {
                // NOWAIT：另一进程正在运行时退出；进程退出由数据库释放锁，不按超时猜测接管。
                Batch batch = mapper.lockBatch(batchId);
                if (batch == null) return;
                mapper.start(batchId);
                while (true) {
                    var items = transaction.execute(tx -> mapper.pendingItems(batchId, 100));
                    if (items.isEmpty()) break;
                    for (var item : items) executeItem(batch, item.id());
                }
                mapper.refresh(batchId);
            });
        } catch (RuntimeException failure) {
            // 无法核实/写入结果时停止；保留已提交项，后续扫描重新取得任务锁。
            log.warn("Batch {} stopped with unfinished work: {}", batchId, failure.getClass().getSimpleName());
        }
    }
    private void executeItem(Batch batch, long itemId) {
        while (true) {
            boolean reserved = Boolean.TRUE.equals(transaction.execute(tx -> {
                var item = mapper.lockItem(itemId);
                if (item == null || !"PENDING".equals(item.processingStatus())) return false;
                if (item.attempts() >= 4) {
                    mapper.failed(itemId, AUTH_STATE_UNAVAILABLE.name());
                    return false;
                }
                if (mapper.reserve(itemId) != 1) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
                return true;
            }));
            if (!reserved) return;
            try {
                transaction.executeWithoutResult(tx -> apply(batch, itemId));
                return;
            } catch (RuntimeException failure) {
                boolean retry = Boolean.TRUE.equals(transaction.execute(tx -> {
                    // 包括 COMMIT 确认丢失：必须先看真实状态，不覆盖已经提交的 APPLIED。
                    var current = mapper.lockItem(itemId);
                    if (current == null || !"PENDING".equals(current.processingStatus())) return false;
                    boolean infrastructure = !(failure instanceof BusinessException business)
                            || business.errorCode() == AUTH_STATE_UNAVAILABLE
                            || business.errorCode() == com.whatname.iam.common.api.ErrorCode.AUDIT_UNAVAILABLE;
                    if (infrastructure && retryableDatabaseFailure(failure) && current.attempts() < 4) return true;
                    String code = failure instanceof BusinessException business ? business.errorCode().name() : AUTH_STATE_UNAVAILABLE.name();
                    if (mapper.failed(itemId, code) != 1) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
                    return false;
                }));
                if (!retry) return;
            }
        }
    }
    private static boolean retryableDatabaseFailure(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TransientDataAccessException) return true;
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                if (sql.getErrorCode() == 1205 || sql.getErrorCode() == 1213
                        || "40001".equals(state) || (state != null && state.startsWith("08"))) return true;
            }
        }
        return false;
    }
    private void apply(Batch batch, long itemId) {
        var item = mapper.lockItem(itemId);
        if (item == null || !"PENDING".equals(item.processingStatus())) return;
        com.whatname.iam.service.admin.integration.CExecutionContext.batch(batch.actorUserId(), batch.id(), item.id());
        Object result;
        long source;
        if ("GRANT".equals(batch.operation())) {
            var applied = grants.grant(batch.actorUserId(), new GrantRequest(item.userId().toString(), batch.roleId().toString(),
                    item.sourceType(), item.sourceRef(), item.sourceDeptId() == null ? null : item.sourceDeptId().toString(), batch.reason(), null));
            source = Long.parseLong(applied.grantSourceId());
            result = applied;
        } else if ("REVOKE".equals(batch.operation())) {
            source = item.grantSourceId();
            result = grants.revoke(batch.actorUserId(), new RevokeRequest(item.userId().toString(), batch.roleId().toString(),
                    Long.toString(source), batch.reason(), null));
        } else {
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        }
        try {
            ObjectNode evidence = json.valueToTree(result);
            if (result instanceof Revoked) {
                Long revision = grantMapper.selectRevision(item.userId(), true);
                if (revision == null || revision < 1) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
                evidence.put("permissionRevision", "v" + revision);
            }
            if (mapper.applied(itemId, source, json.writeValueAsString(evidence)) != 1) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize batch result", e);
        }
    }
}
