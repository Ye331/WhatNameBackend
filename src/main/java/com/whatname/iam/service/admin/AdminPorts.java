package com.whatname.iam.service.admin;

import java.util.Set;

/** 内部协作接口；正式实现必须加入调用方事务。 */
public final class AdminPorts {
    private AdminPorts() {}
    public interface Identity { long currentUserId(); }
    public interface Functions { void require(long actorId, String permission); }
    public interface Integrity {
        /** 根据这些 ID 读取完整规范记录并验签，不能直接对精简字段投影验签。 */
        void verify(AdminState state);
        void sign(AdminState state, Set<Long> changedAdminIds);
    }
    public interface Audit { void record(Change change); }
    public interface ManagementRoles { boolean canDelegate(long actorId, long roleId); }
    public record Change(String operation, long actorId, long targetId, String reason,
                         AdminState before, AdminState after, Set<Long> changedAdminIds) {
        public Change { changedAdminIds = Set.copyOf(changedAdminIds); }
    }
}
