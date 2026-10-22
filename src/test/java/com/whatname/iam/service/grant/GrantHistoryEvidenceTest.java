package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.INTEGRITY_FAILURE;

import static org.assertj.core.api.Assertions.*;

import com.whatname.iam.common.exception.BusinessException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;

/** 审计封印验证后的投影规则；端到端验证见 GrantHistoryIT。 */
class GrantHistoryEvidenceTest {
    Map<String, Object> source() {
        var source = new LinkedHashMap<String, Object>();
        source.put("id", "90");
        source.put("userId", "20");
        source.put("roleId", "30");
        source.put("sourceType", "MANUAL");
        source.put("sourceRef", "retained");
        source.put("sourceDeptId", null);
        source.put("status", "1");
        source.put("createdAt", "2026-10-01T10:00:00");
        source.put("revokedAt", null);
        source.put("revokedBy", null);
        return source;
    }

    Map<String, Object> snapshot(String revision, List<?> sources, List<?> roles) {
        return Map.of(
                "userId", "20", "grantRevision", revision, "sources", sources, "roles", roles);
    }

    List<GrantHistoryEvidence.Change> project(List<?> roles) {
        var retained = new LinkedHashMap<String, Object>();
        retained.put("metadata", Map.of("status", "1"));
        retained.put("schema", "C_CHANGE_V1");
        retained.put("sourceId", "90");
        retained.put("roleId", "30");
        retained.put("before", snapshot("1", List.of(), List.of()));
        retained.put("after", snapshot("2", List.of(source()), roles));
        return GrantHistoryEvidence.changes(
                Map.of(
                        "id",
                        100L,
                        "target_id",
                        20L,
                        "status",
                        1,
                        "operation_type",
                        "USER_ROLE_GRANT"),
                retained);
    }

    @Test
    void aRetainedGrantProducesItsSourceAndSealedTime() {
        var changes = project(List.of("30"));
        assertThat(changes).hasSize(1);
        assertThat(changes.getFirst().item().grantSourceId()).isEqualTo("90");
        assertThat(changes.getFirst().item().occurredAt()).isEqualTo("2026-10-01T10:00:00+08:00");
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "extra", "duplicate"})
    void contradictoryRetainedRoleSetsCannotBecomeHistory(String kind) {
        List<String> roles =
                switch (kind) {
                    case "missing" -> List.of();
                    case "extra" -> List.of("30", "31");
                    default -> List.of("30", "30");
                };
        assertThatThrownBy(() -> project(roles))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        error -> assertThat(error.errorCode()).isEqualTo(INTEGRITY_FAILURE));
    }
}
