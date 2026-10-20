package com.whatname.iam.service.permission;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.stereotype.Service;

/** Production TrustedApiCaller: an API_AUTH identity whose request verifies end to end and is
 * bound to a non-null application. Replaces the deny-by-default bean in PermissionIntegrationConfiguration. */
@Service
public class ServiceApiCaller implements TrustedApiCaller {
    private final ServiceRequestAuthenticator authenticator;
    public ServiceApiCaller(ServiceRequestAuthenticator authenticator) { this.authenticator = authenticator; }

    @Override
    public long authenticatedApplication(HttpServletRequest request, byte[] rawBody) {
        Map<String,Object> identity = authenticator.authenticate(request, rawBody, "API_AUTH");
        return ((Number) identity.get("app_id")).longValue();
    }
}
