package com.whatname.iam.service.auth;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SmsChannelConfiguration {
    @Bean @ConditionalOnMissingBean(SmsSender.class)
    public SmsSender unavailableSmsSender() {
        return new SmsSender() {
            public void requireAvailable(){throw new BusinessException(ErrorCode.SMS_DELIVERY_UNAVAILABLE);}
            public void send(String phone,String purpose,String code){requireAvailable();}
        };
    }
}
