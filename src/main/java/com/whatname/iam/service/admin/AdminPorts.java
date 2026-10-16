package com.whatname.iam.service.admin;

import java.util.Set;

/** Internal integration seams. Production implementations must join the calling transaction. */
public final class AdminPorts {
    private AdminPorts() {}
    public interface Identity { long currentUserId(); }
    public interface Functions { void require(long actorId, String permission); }
    public interface Integrity {
        /** Verify canonical signed records by these IDs, not the reduced projections themselves. */
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
