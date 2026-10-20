package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.service.ServiceIdentityMapper;
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
    private final ServiceIdentityMapper identities;
    public ServiceOaCaller(ServiceRequestAuthenticator authenticator, ServiceIdentityMapper identities) {
        this.authenticator = authenticator;
        this.identities = identities;
    }

    @Override
    public TrustedEventContext authenticate(HttpServletRequest request, byte[] rawBody) {
        Map<String,Object> identity = authenticator.authenticate(request, rawBody, "OA_DECISION");
        return new TrustedEventContext("OA", (String) identity.get("client_id"),
                request.getHeader(ServiceRequestSignature.NONCE_HEADER));
    }

    @Override
    public void verifyTrustedEvent(TrustedEventContext context, String eventType) {
        if (context == null || !"OA".equals(context.sourceSystem()) || blank(context.clientId())
                || blank(context.requestId()) || !"OA_DECISION".equals(eventType))
            throw new BusinessException(INVALID_SERVICE);
        Map<String,Object> identity = identities.find(context.clientId());
        if (identity == null || ((Number) identity.get("status")).intValue() != 1
                || !"OA_DECISION".equals(identity.get("allowed_scope")) || identity.get("app_id") != null)
            throw new BusinessException(INVALID_SERVICE);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
