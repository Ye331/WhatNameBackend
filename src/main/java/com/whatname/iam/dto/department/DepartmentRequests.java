package com.whatname.iam.dto.department;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;

public final class DepartmentRequests {
    private DepartmentRequests() {}

    public record Sync(
            @NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 128) String eventId,
            @NotNull @Positive Long eventVersion,
            @NotBlank @Size(max = 64) String deptCode,
            @NotBlank @Size(max = 128) String deptName,
            @NotNull @Size(max = 64) String parentDeptCode,
            @Size(min = 1, max = 64) String managerSgNo,
            @NotNull OffsetDateTime occurredAt) {}

    public record Merge(
            @NotBlank @Size(max = 64) String sourceSystem,
            @NotBlank @Size(max = 128) String eventId,
            @NotNull @Positive Long eventVersion,
            @NotBlank @Size(max = 64) String sourceDeptCode,
            @NotBlank @Size(max = 64) String targetDeptCode,
            @NotNull OffsetDateTime occurredAt) {}

    public record Reason(@NotBlank @Size(max = 512) String reason) {}
}
