package com.whatname.iam.service.grant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.IamApplication;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.*;

import java.sql.SQLTransactionRollbackException;
import java.time.Duration;
import java.util.Map;

/** Test-only separate JVM: disposable credentials arrive on stdin, never command line or disk. */
public final class BatchRecoveryProcess {
    static String crash;

    @TestConfiguration
    static class CrashConfiguration {
        @Bean
        CrashInterceptor crashInterceptor() {
            return new CrashInterceptor();
        }
    }

    @org.aspectj.lang.annotation.Aspect
    static class CrashInterceptor extends com.whatname.iam.support.RepositoryProbe {
        public Object intercept(Invocation call) throws Throwable {
            String id = call.id();
            Object result = call.proceed();
            boolean applied = id.endsWith("BatchRepository.applied");
            boolean reserved = id.endsWith("BatchRepository.reserve");
            if (("APPLIED".equals(crash) && applied) || ("RESERVE".equals(crash) && reserved)) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            public void afterCommit() {
                                Runtime.getRuntime().halt(23);
                            }
                        });
            }
            if ("ROLLBACK".equals(crash) && applied) {
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            public void afterCompletion(int status) {
                                if (status != STATUS_COMMITTED) Runtime.getRuntime().halt(23);
                            }
                        });
                throw new SQLTransactionRollbackException("fixture deadlock", "40001", 1213);
            }
            return result;
        }
    }

    public static void main(String[] args) throws Exception {
        crash = args[1];
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = new ObjectMapper().readValue(System.in, Map.class);
        var app = new SpringApplication(IamApplication.class, CrashConfiguration.class);
        app.addInitializers(
                context ->
                        context.getEnvironment()
                                .getPropertySources()
                                .addFirst(
                                        new org.springframework.core.env.MapPropertySource(
                                                "disposable-fixture", properties)));
        try (var context =
                app.run("--spring.config.import=", "--spring.main.web-application-type=none")) {
            var db = context.getBean(JdbcTemplate.class);
            long until = System.nanoTime() + Duration.ofSeconds(40).toNanos();
            while (System.nanoTime() < until) {
                String state =
                        db.queryForObject(
                                "SELECT processing_status FROM sys_grant_batch WHERE id=?",
                                String.class,
                                Long.parseLong(args[0]));
                if (!"PENDING".equals(state) && !"PROCESSING".equals(state)) {
                    System.out.println("CHILD_TERMINAL=" + state);
                    return;
                }
                Thread.sleep(100);
            }
            throw new AssertionError("child recovery did not finish");
        }
    }
}
