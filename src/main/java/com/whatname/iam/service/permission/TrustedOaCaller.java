package com.whatname.iam.service.permission;

import com.whatname.iam.service.event.TrustedEventContext;

import jakarta.servlet.http.HttpServletRequest;

/**
 * D 负责的服务请求认证边界：校验方法、路径、原始请求体签名、客户端/密钥/范围、时间戳和 nonce。 nonce
 * 的消费须独立于业务回滚；锁后复核本次请求证明及当前资格，构造上下文不能代替认证。 业务内的复核遵守 C 的统一锁序。
 */
public interface TrustedOaCaller {
    TrustedEventContext authenticate(HttpServletRequest request, byte[] rawBody);

    void verifyTrustedEvent(TrustedEventContext context, String eventType);

    /** A 既有恢复授权接点，默认拒绝；授权成功也不能证明旧执行者已停止，A 仍拒绝非终态接管。 */
    default void authorizeRecovery(TrustedEventContext context, String eventType) {
        throw new com.whatname.iam.common.exception.BusinessException(
                com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
}
