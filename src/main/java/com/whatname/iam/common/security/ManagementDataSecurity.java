package com.whatname.iam.common.security;

import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import java.util.List;
import java.util.Map;

/** D boundary for A management. All calls join the caller's transaction. Implementations must
 * not acquire M5 locks from identity verification, nor open independent evidence transactions.
 * No production implementation exists yet; the configured fallback rejects every operation. */
public interface ManagementDataSecurity {
    void verifyIdentity(UserAccount user);
    void requireFunction(long actorId, String permission);
    void signIdentity(UserAccount user);
    void audit(String operation, Long actorId, Long targetId, String reason);
    void verifyOrganization(List<Department> organization);
    void verifyUsers(List<Map<String,Object>> users);
    void signOrganization(String operation, Object actor, Map<String,Object> evidence);
}
