package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;

import org.springframework.transaction.support.*;

import java.util.Map;

/** 仅在当前事务中保留执行依据；挂起/恢复与事务一致，完成后清除，不能作为跨请求允许缓存。 */
public final class AuthorizationContext {
    private static final Object KEY = new Object();
    private long authorizedActor;
    private Map<String, Object> authorization;

    private AuthorizationContext() {}

    public static AuthorizationContext current() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        var existing = (AuthorizationContext) TransactionSynchronizationManager.getResource(KEY);
        if (existing != null) return existing;
        var context = new AuthorizationContext();
        TransactionSynchronizationManager.bindResource(KEY, context);
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    public void suspend() {
                        TransactionSynchronizationManager.unbindResource(KEY);
                    }

                    public void resume() {
                        TransactionSynchronizationManager.bindResource(KEY, context);
                    }

                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(KEY);
                    }
                });
        return context;
    }

    public void authorized(long actorId, Map<String, Object> evidence) {
        authorizedActor = actorId;
        authorization = Map.copyOf(evidence);
    }

    public Map<String, Object> authorization(long actorId) {
        if (authorization == null || actorId != authorizedActor)
            throw new BusinessException(AUDIT_UNAVAILABLE);
        return authorization;
    }
}
