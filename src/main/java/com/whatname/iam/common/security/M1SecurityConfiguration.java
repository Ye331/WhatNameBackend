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
    public M1SecurityGateway unavailableM1SecurityGateway() {
        return new M1SecurityGateway() {
            public void verifyIdentity(UserAccount u) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
            public Set<Long> authorizeManagement(UserAccount a,String p) { throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
            public void signIdentity(UserAccount u) { throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
            public void audit(String op,Long actor,Long target,String reason) { throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE); }
        };
    }
}
