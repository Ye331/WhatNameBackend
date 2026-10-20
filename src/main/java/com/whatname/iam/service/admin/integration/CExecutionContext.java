package com.whatname.iam.service.admin.integration;

import com.whatname.iam.common.exception.BusinessException;
import org.springframework.transaction.support.*;
import java.util.Map;
import static com.whatname.iam.common.api.ErrorCode.*;

/** 仅在当前事务中保留执行依据；挂起/恢复与事务一致，完成后清除，不能作为跨请求允许缓存。 */
public final class CExecutionContext {
    private static final Object KEY = new Object();
    private Long batchActor;
    private Map<String,Object> origin;
    private long authorizedActor;
    private Map<String,Object> authorization;
    private CExecutionContext() { }

    public static CExecutionContext current() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        var existing = (CExecutionContext) TransactionSynchronizationManager.getResource(KEY);
        if (existing != null) return existing;
        var context = new CExecutionContext();
        TransactionSynchronizationManager.bindResource(KEY, context);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            public void suspend() { TransactionSynchronizationManager.unbindResource(KEY); }
            public void resume() { TransactionSynchronizationManager.bindResource(KEY, context); }
            public void afterCompletion(int status) { TransactionSynchronizationManager.unbindResourceIfPossible(KEY); }
        });
        return context;
    }

    /** 只由 BatchWorker 在锁定持久任务项后调用；HTTP 请求字段不能建立此上下文。 */
    public static void batch(long actorId, long batchId, long itemId) {
        var context = current();
        if (actorId <= 0 || batchId <= 0 || itemId <= 0 || context.authorization != null || context.origin != null)
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        context.batchActor = actorId;
        context.origin = Map.of("kind", "PERSISTED_BATCH", "batchId", batchId, "itemId", itemId);
    }
    public Long batchActor() { return batchActor; }
    public Map<String,Object> origin() { return origin; }
    public void authorized(long actorId, Map<String,Object> evidence) {
        authorizedActor = actorId;
        authorization = Map.copyOf(evidence);
    }
    public Map<String,Object> authorization(long actorId) {
        if (authorization == null || actorId != authorizedActor) throw new BusinessException(AUDIT_UNAVAILABLE);
        return authorization;
    }
}
