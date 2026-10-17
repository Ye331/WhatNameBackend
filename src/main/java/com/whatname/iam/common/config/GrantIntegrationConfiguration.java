package com.whatname.iam.common.config;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.grant.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import static com.whatname.iam.common.api.ErrorCode.*;

@Configuration(proxyBeanMethods = false)
public class GrantIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean(GrantPorts.Integrity.class)
    GrantPorts.Integrity grantIntegrity() {
        return new GrantPorts.Integrity() {
            public void verify(GrantState state) { throw new BusinessException(INTEGRITY_FAILURE); }
            public void sign(GrantState state) { throw new BusinessException(INTEGRITY_FAILURE); }
        };
    }
    @Bean @ConditionalOnMissingBean(GrantPorts.Audit.class)
    GrantPorts.Audit grantAudit() { return change -> { throw new BusinessException(AUDIT_UNAVAILABLE); }; }
}
