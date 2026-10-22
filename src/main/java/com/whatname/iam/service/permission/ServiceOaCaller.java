package com.whatname.iam.service.permission;

import static com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE;
import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.event.TrustedEventContext;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * OA 服务身份认证：authenticate 校验请求签名并返回上下文（requestId 为已消费的 nonce）， verifyTrustedEvent
 * 在每次事件处理时复核该服务身份的当前资格。
 */
@Service
public class ServiceOaCaller {
    private final ServiceRequestAuthenticator authenticator;

    public ServiceOaCaller(ServiceRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    public TrustedEventContext authenticate(HttpServletRequest request, byte[] rawBody) {
        Map<String, Object> identity = authenticator.authenticate(request, rawBody, "OA_DECISION");
        return authenticator.issuedContext(
                "OA",
                (String) identity.get("client_id"),
                request.getHeader(ServiceRequestSignature.NONCE_HEADER),
                "OA_DECISION");
    }

    public void verifyTrustedEvent(TrustedEventContext context, String eventType) {
        if (context == null
                || !"OA".equals(context.sourceSystem())
                || blank(context.clientId())
                || blank(context.requestId())
                || !"OA_DECISION".equals(eventType)) throw new BusinessException(INVALID_SERVICE);
        authenticator.verifyAuthenticatedContext(context, "OA_DECISION");
    }

    /** OA 事件不提供恢复接管；授权成功也不能证明旧执行者已停止。 */
    public void authorizeRecovery(TrustedEventContext context, String eventType) {
        throw new BusinessException(AUTH_STATE_UNAVAILABLE);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
