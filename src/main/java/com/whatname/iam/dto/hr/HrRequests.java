package com.whatname.iam.dto.hr;

import jakarta.validation.constraints.*;

import java.time.OffsetDateTime;

public final class HrRequests {
    private HrRequests() {}

    public enum Action {
        UPSERT,
        REHIRE,
        SUSPEND,
        RESUME
    }

    public enum EmploymentType {
        FORMAL,
        OUTSOURCED
    }

    public record Employee(
            @NotBlank @Size(max = 32) String sourceSystem,
            @NotBlank @Size(max = 128) String eventId,
            @NotNull @Positive Long eventVersion,
            @NotBlank @Size(max = 64) String sgNo,
            @NotBlank @Size(max = 64) String realName,
            @NotBlank @Size(max = 32) String phone,
            @NotBlank @Size(max = 64) String deptCode,
            Action employeeAction,
            @NotNull EmploymentType employmentType,
            @NotNull OffsetDateTime occurredAt) {
        public Employee {
            if (employeeAction == null) employeeAction = Action.UPSERT;
        }

        @Override
        public String toString() {
            return "HrEmployee[redacted]";
        }
    }

    public record Transfer(
            @NotBlank @Size(max = 32) String sourceSystem,
            @NotBlank @Size(max = 128) String eventId,
            @NotNull @Positive Long eventVersion,
            @NotBlank @Size(max = 64) String sgNo,
            @NotBlank @Size(max = 64) String fromDeptCode,
            @NotBlank @Size(max = 64) String toDeptCode,
            @NotNull OffsetDateTime occurredAt) {}

    // HR04 has no occurredAt: effectiveAt supplies the business time to ExternalEventService.
    public record Termination(
            @NotBlank @Size(max = 32) String sourceSystem,
            @NotBlank @Size(max = 128) String eventId,
            @NotNull @Positive Long eventVersion,
            @NotBlank @Size(max = 64) String sgNo,
            @NotNull OffsetDateTime effectiveAt) {}
}
