package com.whatname.iam.common.security;

import com.whatname.iam.service.admin.AccountManagementPolicy;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.PermissionService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 完整应用直接装配真实服务；缺少必需模块时启动失败。 */
@Configuration
public class AccountSecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(AccountSecurity.class)
    public AccountSecurity accountSecurity(
            AccountManagementPolicy policy, ManagementDataSecurity data) {
        return new AccountSecurityService(policy, data);
    }

    @Bean
    @ConditionalOnMissingBean(ManagementDataSecurity.class)
    public ManagementDataSecurity managementDataSecurity(
            DataSignatureService signatures, PermissionService permissions, AuditService audit) {
        return new ManagementDataSecurityService(signatures, permissions, audit);
    }
}
