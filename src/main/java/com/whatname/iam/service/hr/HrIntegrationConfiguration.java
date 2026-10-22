package com.whatname.iam.service.hr;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class HrIntegrationConfiguration {
    @Bean
    @ConditionalOnMissingBean(name = "hrClock")
    Clock hrClock() {
        return Clock.systemUTC();
    }
}
