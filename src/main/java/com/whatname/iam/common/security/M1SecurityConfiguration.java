package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.user.UserAccount;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class M1SecurityConfiguration {
    @Bean
    @ConditionalOnMissingBean(M1SecurityGateway.class)
    public M1SecurityGateway unavailableM1SecurityGateway(org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.admin.AManagementPolicy> policy,
                                                          ManagementDataSecurity data) {
        if(policy.getIfAvailable()!=null) return new CBackedM1SecurityGateway(policy.getObject(),data);
        // A deliberately partial application context still fails closed without C.
        return new M1SecurityGateway() {
            public void verifyIdentity(UserAccount user) { data.verifyIdentity(user); }
            public Set<Long> authorizeManagement(UserAccount actor,String permission) { throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
            public void signIdentity(UserAccount user) { data.signIdentity(user); }
            public void audit(String op,Long actor,Long target,String reason) { data.audit(op,actor,target,reason); }
        };
    }

    @Bean
    @ConditionalOnMissingBean(ManagementDataSecurity.class)
    public ManagementDataSecurity unavailableManagementDataSecurity(
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.permission.DataSignatureService> signatures,
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.permission.PermissionService> permissions,
            org.springframework.beans.factory.ObjectProvider<com.whatname.iam.service.audit.AuditService> audit) {
        if(signatures.getIfAvailable()!=null && permissions.getIfAvailable()!=null && audit.getIfAvailable()!=null)
            return new DBackedManagementDataSecurity(signatures.getObject(),permissions.getObject(),audit.getObject());
        return new ManagementDataSecurity() {
            public void verifyIdentity(UserAccount u) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
            public void requireFunction(long actor,String permission) { throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
            public void signIdentity(UserAccount u) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
            public void audit(String op,Long actor,Long target,String reason) { throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE); }
            public void verifyOrganization(java.util.List<com.whatname.iam.entity.department.Department> all) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
            public void verifyUsers(java.util.List<java.util.Map<String,Object>> users) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
            public void signOrganization(String op,Object actor,java.util.Map<String,Object> evidence) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
        };
    }
}
