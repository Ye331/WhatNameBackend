package com.whatname.iam.common.config;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EntityScan("com.whatname.iam.entity")
@EnableJpaRepositories("com.whatname.iam.repository")
public class JpaConfig {}
