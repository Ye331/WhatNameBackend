package com.whatname.iam.service.department;

import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.common.security.ManagementDataSecurity;
import com.whatname.iam.mapper.service.ServiceIdentityMapper;
import com.whatname.iam.service.admin.AccountManagementPolicy;
import com.whatname.iam.service.admin.AdminOrganizationLifecycle;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.permission.ServiceRequestAuthenticator;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DepartmentIntegrationConfiguration {
    @Bean
    @ConditionalOnMissingBean(DepartmentCollaboration.class)
    DepartmentCollaboration departmentCollaboration(
            AccountManagementPolicy policy,
            JwtTokenService jwt,
            AuthService auth,
            ManagementDataSecurity data,
            AdminOrganizationLifecycle lifecycle,
            ServiceRequestAuthenticator services,
            ServiceIdentityMapper identities) {
        return new DepartmentAccessService(
                policy, jwt, auth, data, lifecycle, services, identities);
    }
}
