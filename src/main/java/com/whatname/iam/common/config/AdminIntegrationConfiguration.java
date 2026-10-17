package com.whatname.iam.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.admin.AdminPorts;
import com.whatname.iam.service.admin.AdminState;
import java.util.Set;
import static com.whatname.iam.common.api.ErrorCode.*;

@Configuration(proxyBeanMethods = false)
public class AdminIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean(AdminPorts.Identity.class)
    AdminPorts.Identity adminIdentity() {
        return () -> { throw new BusinessException(INVALID_TOKEN); };
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Functions.class)
    AdminPorts.Functions adminFunctions() {
        return (actor, permission) -> { throw new BusinessException(AUTH_STATE_UNAVAILABLE); };
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Integrity.class)
    AdminPorts.Integrity adminIntegrity() {
        return new AdminPorts.Integrity() {
            public void verify(AdminState state) { throw new BusinessException(INTEGRITY_FAILURE); }
            public void sign(AdminState state, Set<Long> changed) { throw new BusinessException(INTEGRITY_FAILURE); }
        };
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Audit.class)
    AdminPorts.Audit adminAudit() {
        return change -> { throw new BusinessException(AUDIT_UNAVAILABLE); };
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.ManagementRoles.class)
    AdminPorts.ManagementRoles adminManagementRoles() { return (actor, role) -> false; }
}
