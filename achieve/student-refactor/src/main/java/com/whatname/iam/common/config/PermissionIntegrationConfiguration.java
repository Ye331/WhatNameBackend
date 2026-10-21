package com.whatname.iam.common.config;

import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.permission.TrustedApiCaller;
import com.whatname.iam.service.permission.TrustedOaCaller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods = false)
public class PermissionIntegrationConfiguration {
    @Bean
    @ConditionalOnMissingBean(TrustedOaCaller.class)
    TrustedOaCaller unavailableTrustedOaCaller() {
        return new TrustedOaCaller() {
            public com.whatname.iam.service.event.TrustedEventContext authenticate(
                    jakarta.servlet.http.HttpServletRequest request, byte[] rawBody) {
                throw new BusinessException(INVALID_SERVICE);
            }

            public void verifyTrustedEvent(
                    com.whatname.iam.service.event.TrustedEventContext context, String eventType) {
                throw new BusinessException(INVALID_SERVICE);
            }
        };
    }

    @Bean
    @ConditionalOnMissingBean(TrustedApiCaller.class)
    TrustedApiCaller unavailableTrustedApiCaller() {
        return (request, body) -> {
            throw new BusinessException(INVALID_SERVICE);
        };
    }
}
