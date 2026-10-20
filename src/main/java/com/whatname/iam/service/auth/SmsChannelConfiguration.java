package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration
public class SmsChannelConfiguration {
    @Bean @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender unavailableSmsSender(Environment environment) {
        if (environment.acceptsProfiles(Profiles.of("sms-demo")))
            return new LocalDemoSmsSender(environment.getProperty("iam.sms.demo-directory", ""));
        return new SmsSender() {
            public void requireAvailable(){throw new BusinessException(ErrorCode.SMS_DELIVERY_UNAVAILABLE);}
            public void send(String phone,String purpose,String code){requireAvailable();}
        };
    }
}
