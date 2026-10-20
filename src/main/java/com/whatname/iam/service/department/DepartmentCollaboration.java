package com.whatname.iam.service.department;

import com.whatname.iam.entity.department.Department;
import com.whatname.iam.service.event.TrustedEventContext;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** C/D integration port. Implementations must use the calling transaction, no REQUIRES_NEW writes.
 * Scope is the exact set of currently authorized department IDs, not an unchecked client path.
 * Management authentication includes M6 function permission and current session/frozen-actor checks.
 */
public interface DepartmentCollaboration {
    record Manager(String actorId, com.whatname.iam.common.security.JwtTokenService.SessionPrincipal session) {
        public Manager(String actorId) { this(actorId,null); }
    }
    /** Must precede organization/user reads and locks in every management transaction. */
    default void prepareManagement(Manager actor) { }
    /** First call for a department write; production delegates to C's existing department check. */
    default void prepareDepartmentManagement(Manager actor,long departmentId) { prepareManagement(actor); }
    Manager authenticateManager(HttpServletRequest request, String permission);
    TrustedEventContext authenticateHr(HttpServletRequest request, String eventType);
    void verifyTrustedEvent(TrustedEventContext context, String eventType);
    void authorizeRecovery(TrustedEventContext context, String eventType);
    /** Recheck current M6 function permission, actor session/frozen chain and M5 qualification
     * inside the transaction as well as target scope. Unfreeze needs an eligible unrestricted
     * superior/recovery actor; login alone never grants a frozen administrator this operation. */
    Set<Long> scope(Manager actor, String permission, List<Department> organization);
    void verifyOrganization(List<Department> organization);
    /** Verify moved user facts before changing dept_id; no authorization grants are changed by A. */
    void verifyUsers(List<Map<String, Object>> users);
    /** Internal two-phase organization protocol: after authenticated event lock, before all
     * organization/user reads. Implementations without C lifecycle support must fail closed. */
    default void prepareOrganizationChange(TrustedEventContext actor, String eventId) {
        throw new com.whatname.iam.common.exception.BusinessException(
                com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE);
    }
    /** Restrict invalid management roots and revalidate affected scopes, never expand them implicitly. */
    void organizationChanged(Set<Long> departments, List<Map<String, Object>> movedUsers);
    /** Persist signatures (including merged_into_dept_id, user dept_id, tree membership) and audit. */
    void signAndAudit(String operation, Object actor, Map<String, Object> evidence);
}
