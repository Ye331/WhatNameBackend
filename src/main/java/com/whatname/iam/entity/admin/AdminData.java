package com.whatname.iam.entity.admin;

/** 管理员模块使用的最小数据库投影，不是签名序列化格式。 */
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
