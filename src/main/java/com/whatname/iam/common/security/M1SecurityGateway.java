package com.whatname.iam.common.security;

import com.whatname.iam.entity.user.UserAccount;
import java.util.Set;

/**
 * A-side boundary; the default management adapter now uses real C M5. D remains mandatory. Implementations MUST join
 * the caller's MySQL transaction, verify current facts, lock authorization/scope for writes,
 * and persist required signature/audit before returning. No production allow implementation.
 */
public interface M1SecurityGateway {
    /** Acquire M5 facts before any user/organization lock. Include ALL existing target users.
     * Test gateways may substitute the entire boundary; production uses C's ordered context. */
    default void prepareManagement(long actorId, long... targetUserIds) { }
    void verifyIdentity(UserAccount user);
    /** M5 current scope and M6 function check after prepareManagement; IDs are trusted DB facts. */
    Set<Long> authorizeManagement(UserAccount actor, String permission);
    void signIdentity(UserAccount user);
    /** Sanitized operation and reason; never passwords, codes or full JWT. */
    void audit(String operation, Long actorId, Long targetId, String reason);
}
