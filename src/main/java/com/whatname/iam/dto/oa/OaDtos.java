package com.whatname.iam.dto.oa;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.whatname.iam.dto.admin.AdminDtos.StrictString;
import java.time.OffsetDateTime;

public final class OaDtos {
    private OaDtos() { }
    public record Decision(
            @JsonDeserialize(using=StrictString.class) String sourceSystem,
            @JsonDeserialize(using=StrictString.class) String eventId,
            @JsonDeserialize(using=StrictString.class) String approvalId,
            @JsonDeserialize(using=StrictString.class) String approvalStatus,
            @JsonDeserialize(using=StrictString.class) String operation,
            @JsonDeserialize(using=StrictString.class) String targetUserId,
            @JsonDeserialize(using=StrictString.class) String roleId,
            OffsetDateTime approvedAt,
            @JsonDeserialize(using=StrictString.class) String grantSourceId,
            @JsonDeserialize(using=StrictString.class) String originalApprovalId) {
        @JsonAnySetter public void rejectUnknown(String name,JsonNode value) {
            throw new IllegalArgumentException("Unknown OA decision field");
        }
    }
}
