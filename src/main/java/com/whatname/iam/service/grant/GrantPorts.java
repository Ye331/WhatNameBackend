package com.whatname.iam.service.grant;

import com.whatname.iam.service.admin.AdminState;

/** Adapters must verify canonical records and join the calling database transaction. */
public final class GrantPorts {
    private GrantPorts() {}
    public interface Integrity {
        // Verify full canonical user identity (including grant revision), sources and relationship sets.
        void verify(GrantState state);
        // Sign updated user identity and sets, including empty sets after final revocation.
        void sign(GrantState state);
    }
    public interface Audit { void record(Change change); }
    public record Change(String operation, long actorId, long userId, long roleId, long sourceId,
                         String reason, AdminState authority, GrantState before, GrantState after) {}
}
