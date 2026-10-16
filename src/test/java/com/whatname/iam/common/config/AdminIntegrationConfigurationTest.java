package com.whatname.iam.common.config;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.admin.AdminPorts;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.Set;
import static com.whatname.iam.common.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;

class AdminIntegrationConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AdminIntegrationConfiguration.class);

    @Test void missingProductionIntegrationsFailClosed() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(AdminPorts.Identity.class).hasSingleBean(AdminPorts.Functions.class)
                    .hasSingleBean(AdminPorts.Integrity.class).hasSingleBean(AdminPorts.Audit.class);
            assertThatThrownBy(() -> context.getBean(AdminPorts.Identity.class).currentUserId())
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(INVALID_TOKEN));
            assertThatThrownBy(() -> context.getBean(AdminPorts.Functions.class).require(1, "IAM_ADMINS_WRITE"))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
            assertThatThrownBy(() -> context.getBean(AdminPorts.Integrity.class).verify(null))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(INTEGRITY_FAILURE));
            assertThatThrownBy(() -> context.getBean(AdminPorts.Integrity.class).sign(null, Set.of(1L)))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(INTEGRITY_FAILURE));
            assertThatThrownBy(() -> context.getBean(AdminPorts.Audit.class).record(null))
                    .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(AUDIT_UNAVAILABLE));
            assertThat(context.getBean(AdminPorts.ManagementRoles.class).canDelegate(1, 200)).isFalse();
        });
    }

    @Test void anActualIdentityProviderReplacesTheDefault() {
        runner.withBean(AdminPorts.Identity.class, () -> () -> 42L).run(context -> {
            assertThat(context).hasSingleBean(AdminPorts.Identity.class);
            assertThat(context.getBean(AdminPorts.Identity.class).currentUserId()).isEqualTo(42L);
        });
    }
}
