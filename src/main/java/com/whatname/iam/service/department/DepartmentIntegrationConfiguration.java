package com.whatname.iam.service.department;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.service.event.TrustedEventContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DepartmentIntegrationConfiguration {
    @Bean
    @ConditionalOnMissingBean(DepartmentCollaboration.class)
    DepartmentCollaboration unavailableDepartmentCollaboration(
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.admin.AManagementPolicy> policy,
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.common.security.JwtTokenService> jwt,
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.auth.AuthService> auth,
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.common.security.ManagementDataSecurity> data,
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.admin.AdminOrganizationLifecycle> lifecycle) {
        if(policy.getIfAvailable()!=null && jwt.getIfAvailable()!=null && auth.getIfAvailable()!=null && data.getIfAvailable()!=null)
            return new CBackedDepartmentCollaboration(policy.getObject(),jwt.getObject(),auth.getObject(),data.getObject(),lifecycle.getIfAvailable());
        return new DepartmentCollaboration() {
            private BusinessException unavailable() { return new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
            public Manager authenticateManager(HttpServletRequest r, String p) { throw unavailable(); }
            public TrustedEventContext authenticateHr(HttpServletRequest r, String t) { throw unavailable(); }
            public void verifyTrustedEvent(TrustedEventContext c, String t) { throw unavailable(); }
            public void authorizeRecovery(TrustedEventContext c, String t) { throw unavailable(); }
            public Set<Long> scope(Manager a, String p, List<Department> d) { throw unavailable(); }
            public void verifyOrganization(List<Department> d) { throw unavailable(); }
            public void verifyUsers(List<Map<String, Object>> u) { throw unavailable(); }
            public void organizationChanged(Set<Long> d, List<Map<String, Object>> u) { throw unavailable(); }
            public void signAndAudit(String o, Object a, Map<String, Object> e) { throw unavailable(); }
        };
    }
}
