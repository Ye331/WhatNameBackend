package com.whatname.iam.common.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import com.whatname.iam.service.admin.AdminPorts;
import com.whatname.iam.service.admin.integration.*;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.mapper.admin.CResourceLockMapper;
import com.whatname.iam.service.permission.*;
import com.whatname.iam.service.audit.AuditService;
import org.springframework.beans.factory.ObjectProvider;

@Configuration(proxyBeanMethods = false)
public class AdminIntegrationConfiguration {
    @Bean
    CRequestSecurity cRequestSecurity(ObjectProvider<JwtTokenService> jwt, ObjectProvider<PermissionService> permissions,
                                      ObjectProvider<DataSignatureService> signatures) {
        return new CRequestSecurity(jwt,permissions,signatures);
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Identity.class)
    AdminPorts.Identity adminIdentity(CRequestSecurity security) {
        return security::currentUserId;
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Functions.class)
    AdminPorts.Functions adminFunctions(CRequestSecurity security) {
        return security::require;
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Integrity.class)
    AdminPorts.Integrity adminIntegrity(ObjectProvider<DataSignatureService> signatures, ObjectProvider<CResourceLockMapper> locks) {
        return new DAdminIntegrity(signatures,locks);
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.Audit.class)
    AdminPorts.Audit adminAudit(ObjectProvider<AuditService> audit) {
        return new DAdminAudit(audit);
    }
    @Bean @ConditionalOnMissingBean(AdminPorts.ManagementRoles.class)
    AdminPorts.ManagementRoles adminManagementRoles() { return (actor, role) -> false; }
}
