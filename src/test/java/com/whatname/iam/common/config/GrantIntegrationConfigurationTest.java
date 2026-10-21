package com.whatname.iam.common.config;

import static com.whatname.iam.common.api.ErrorCode.*;

import static org.assertj.core.api.Assertions.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.grant.*;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class GrantIntegrationConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(GrantIntegrationConfiguration.class);

    @Test
    void missingIntegrityAndAuditAdaptersFailClosed() {
        runner.run(
                context -> {
                    assertThat(context)
                            .hasSingleBean(GrantPorts.Integrity.class)
                            .hasSingleBean(GrantPorts.Audit.class);
                    assertThatThrownBy(
                                    () -> context.getBean(GrantPorts.Integrity.class).verify(null))
                            .isInstanceOfSatisfying(
                                    BusinessException.class,
                                    e -> assertThat(e.errorCode()).isEqualTo(INTEGRITY_FAILURE));
                    assertThatThrownBy(() -> context.getBean(GrantPorts.Integrity.class).sign(null))
                            .isInstanceOfSatisfying(
                                    BusinessException.class,
                                    e -> assertThat(e.errorCode()).isEqualTo(INTEGRITY_FAILURE));
                    assertThatThrownBy(() -> context.getBean(GrantPorts.Audit.class).record(null))
                            .isInstanceOfSatisfying(
                                    BusinessException.class,
                                    e -> assertThat(e.errorCode()).isEqualTo(AUDIT_UNAVAILABLE));
                });
    }

    @Test
    void suppliedAdaptersReplaceDefaults() {
        GrantPorts.Integrity integrity =
                new GrantPorts.Integrity() {
                    public void verify(GrantState state) {}

                    public void sign(GrantState state) {}
                };
        GrantPorts.Audit audit = change -> {};
        runner.withBean(GrantPorts.Integrity.class, () -> integrity)
                .withBean(GrantPorts.Audit.class, () -> audit)
                .run(
                        context -> {
                            assertThat(context)
                                    .hasSingleBean(GrantPorts.Integrity.class)
                                    .hasSingleBean(GrantPorts.Audit.class);
                            assertThat(context.getBean(GrantPorts.Integrity.class))
                                    .isSameAs(integrity);
                            assertThat(context.getBean(GrantPorts.Audit.class)).isSameAs(audit);
                        });
    }
}
