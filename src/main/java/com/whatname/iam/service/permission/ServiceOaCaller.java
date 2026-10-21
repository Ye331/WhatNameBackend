package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.event.TrustedEventContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

/** Production TrustedOaCaller: an OA_DECISION identity whose request verifies end to end.
 * authenticate() returns the issued context (requestId = the consumed nonce) and
 * verifyTrustedEvent() re-checks the identity's current qualification on every event call. */
@Service
public class ServiceOaCaller implements TrustedOaCaller {
    private final ServiceRequestAuthenticator authenticator;
    public ServiceOaCaller(ServiceRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public TrustedEventContext authenticate(HttpServletRequest request, byte[] rawBody) {
        Map<String,Object> identity = authenticator.authenticate(request, rawBody, "OA_DECISION");
        return authenticator.issuedContext("OA", (String) identity.get("client_id"),
                request.getHeader(ServiceRequestSignature.NONCE_HEADER), "OA_DECISION");
    }

    @Override
    public void verifyTrustedEvent(TrustedEventContext context, String eventType) {
        if (context == null || !"OA".equals(context.sourceSystem()) || blank(context.clientId())
                || blank(context.requestId()) || !"OA_DECISION".equals(eventType))
            throw new BusinessException(INVALID_SERVICE);
        authenticator.verifyAuthenticatedContext(context, "OA_DECISION");
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
