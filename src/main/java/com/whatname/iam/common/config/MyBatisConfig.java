package com.whatname.iam.common.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

@Configuration
@MapperScan("com.whatname.iam.mapper")
public class MyBatisConfig {}
