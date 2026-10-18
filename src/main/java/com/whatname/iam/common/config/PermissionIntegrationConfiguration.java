package com.whatname.iam.common.config;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.permission.TrustedApiCaller;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;
import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

@Configuration(proxyBeanMethods = false)
public class PermissionIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean(TrustedApiCaller.class)
    TrustedApiCaller unavailableTrustedApiCaller() {
        return (request, body) -> { throw new BusinessException(INVALID_SERVICE); };
    }
}
