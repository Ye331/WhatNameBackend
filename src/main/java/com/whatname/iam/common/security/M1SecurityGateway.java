package com.whatname.iam.common.security;

import com.whatname.iam.entity.user.UserAccount;
import java.util.Set;

/**
 * Temporary A-side boundary until actual C/D methods are integrated. Implementations MUST join
 * the caller's MySQL transaction, verify current facts, lock authorization/scope for writes,
 * and persist required signature/audit before returning. No production allow implementation.
 */
public interface M1SecurityGateway {
    void verifyIdentity(UserAccount user);
    /** M6 function check followed by M5 current scope; IDs come from trusted DB facts only. */
    Set<Long> authorizeManagement(UserAccount actor, String permission);
    void signIdentity(UserAccount user);
    /** Sanitized operation and reason; never passwords, codes or full JWT. */
    void audit(String operation, Long actorId, Long targetId, String reason);
}
