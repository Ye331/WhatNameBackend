package com.whatname.iam.common.security;

import com.whatname.iam.entity.user.UserAccount;
import java.util.Set;

/**
 * A-side boundary; the default management adapter now uses real C M5. D remains mandatory. Implementations MUST join
 * the caller's MySQL transaction, verify current facts, lock authorization/scope for writes,
 * and persist required signature/audit before returning. No production allow implementation.
 */
public interface M1SecurityGateway {
    /** First call in a fresh A transaction, before A user/organization reads or locks.
     * Include the operation's existing target user so C obtains actor/target in ID order. */
    default void prepareManagement(long actorId, long... targetUserIds) { }
    /** Existing C department-scope check is the first lock acquisition for new-user writes. */
    default void prepareDepartmentManagement(long actorId,long departmentId) { prepareManagement(actorId); }
    void verifyIdentity(UserAccount user);
    /** M5 current scope and M6 function check after prepareManagement; IDs are trusted DB facts. */
    Set<Long> authorizeManagement(UserAccount actor, String permission);
    void signIdentity(UserAccount user);
    /** Sanitized operation and reason; never passwords, codes or full JWT. */
    void audit(String operation, Long actorId, Long targetId, String reason);
}
