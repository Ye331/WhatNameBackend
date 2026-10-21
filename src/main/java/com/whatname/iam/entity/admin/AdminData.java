package com.whatname.iam.entity.admin;

/** Minimal database projections for M5. These are not signature serialization formats. */
public final class AdminData {
    private AdminData() {}

    public record Appointment(
            Long userId,
            int adminLevel,
            Long deptId,
            int status,
            int delegableRevision,
            Long appointedBy) {}

    public record Department(Long id, Long parentId, int status, Long mergedIntoDeptId) {
        public Department(Long id, Long parentId, int status) {
            this(id, parentId, status, null);
        }
    }

    public record User(Long id, Long deptId, int status) {}

    public record Role(Long id, int roleType, int status) {}

    public record Delegation(Long adminUserId, Long roleId, Long configuredBy) {}
}
