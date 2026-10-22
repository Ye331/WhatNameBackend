package com.whatname.iam.dto.grant;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.whatname.iam.dto.admin.AdminDtos.StrictString;

import java.util.List;

public final class GrantDtos {
    private GrantDtos() {}

    public record GrantRequest(
            @JsonDeserialize(using = StrictString.class) String userId,
            @JsonDeserialize(using = StrictString.class) String roleId,
            @JsonDeserialize(using = StrictString.class) String sourceType,
            @JsonDeserialize(using = StrictString.class) String sourceRef,
            @JsonDeserialize(using = StrictString.class) String sourceDeptId,
            @JsonDeserialize(using = StrictString.class) String reason,
            @JsonDeserialize(using = StrictString.class) String expectedVersion) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown grant request field");
        }
    }

    public record RevokeRequest(
            @JsonDeserialize(using = StrictString.class) String userId,
            @JsonDeserialize(using = StrictString.class) String roleId,
            @JsonDeserialize(using = StrictString.class) String grantSourceId,
            @JsonDeserialize(using = StrictString.class) String reason,
            @JsonDeserialize(using = StrictString.class) String expectedVersion) {
        @JsonAnySetter
        public void rejectUnknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown revoke request field");
        }
    }

    public record RoleView(String roleId, boolean active, int activeSourceCount) {}

    public record Roles(String userId, String grantRevision, List<RoleView> roles) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SourceView(
            String grantSourceId,
            String sourceType,
            String sourceRef,
            String sourceDeptId,
            boolean active) {}

    public record Sources(
            String userId, String roleId, String grantRevision, List<SourceView> sources) {}

    public record Granted(
            String userId,
            String roleId,
            String grantSourceId,
            boolean roleEffective,
            String permissionRevision) {}

    public record Revoked(
            String userId,
            String roleId,
            String revokedSourceId,
            boolean roleEffective,
            int remainingActiveSources) {}

    public record HistoryItem(
            String eventId,
            String userId,
            String roleId,
            String grantSourceId,
            String operation,
            String occurredAt) {}

    public record HistoryPage(List<HistoryItem> items, int page, int pageSize, long total) {}
}
