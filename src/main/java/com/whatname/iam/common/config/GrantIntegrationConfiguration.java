package com.whatname.iam.common.config;

import com.whatname.iam.service.grant.*;
import com.whatname.iam.service.grant.integration.*;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.audit.AuditService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class GrantIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean(GrantPorts.Integrity.class)
    GrantPorts.Integrity grantIntegrity(ObjectProvider<DataSignatureService> signatures) {
        return new DGrantIntegrity(signatures);
    }
    @Bean @ConditionalOnMissingBean(GrantPorts.Audit.class)
    GrantPorts.Audit grantAudit(ObjectProvider<AuditService> audit) { return new DGrantAudit(audit); }
}
