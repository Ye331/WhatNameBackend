package com.whatname.iam.service.permission;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.stereotype.Service;

import java.util.Map;

/** 使用真实请求签名校验业务服务身份，并返回该服务绑定的应用。 */
@Service
public class ServiceApiCaller implements TrustedApiCaller {
    private final ServiceRequestAuthenticator authenticator;

    public ServiceApiCaller(ServiceRequestAuthenticator authenticator) {
        this.authenticator = authenticator;
    }

    @Override
    public long authenticatedApplication(HttpServletRequest request, byte[] rawBody) {
        Map<String, Object> identity = authenticator.authenticate(request, rawBody, "API_AUTH");
        return ((Number) identity.get("app_id")).longValue();
    }
}
