package com.whatname.iam.service.hr;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.event.TrustedEventContext;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.*;

@Configuration
public class HrIntegrationConfiguration {
    @Bean @ConditionalOnMissingBean(name="hrClock")
    Clock hrClock() { return Clock.systemUTC(); }
    @Bean @ConditionalOnMissingBean(HrCollaboration.class)
    HrCollaboration unavailableHrCollaboration() {
        return new HrCollaboration() {
            private void unavailable() { throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE); }
            public void revokePosition(TrustedEventContext a,String e,long u,long d) { unavailable(); }
            public void revokeAll(TrustedEventContext a,String e,long u) { unavailable(); }
            public void requireRehireEligible(long u) { unavailable(); }
            public void departmentChanged(long u,long old,long next) { unavailable(); }
        };
    }
}
