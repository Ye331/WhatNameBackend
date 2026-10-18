package com.whatname.iam.service.hr;

import com.whatname.iam.service.event.TrustedEventContext;

/** C-owned writes must join the caller's SERIALIZABLE transaction on the same datasource.
 * Verify signatures and completeness of source/qualification facts before any write. Missing
 * historical source information must fail. Never grant or reactivate historical entitlements.
 * C owns grant_revision; A reloads the resulting user before D signs identity. C must persist
 * source-set/qualification signatures and before/after audit evidence in this transaction.
 */
public interface HrCollaboration {
    /** Revoke only DEPT_POSITION sources belonging to oldDepartmentId; preserve independent
     * MANUAL/OA/other-department sources, recompute effective relations and retain history. */
    void revokePosition(TrustedEventContext actor, String eventId, long userId, long oldDepartmentId);
    /** Revoke all effective sources, effective relations and administrator qualifications;
     * retain history, update relevant revisions and signatures in this transaction. */
    void revokeAll(TrustedEventContext actor, String eventId, long userId);
    /** Verify there are no active entitlements/administrator qualifications to resurrect. */
    void requireRehireEligible(long userId);
    /** Recheck and restrict affected administrator scopes after a move; never expand scope. */
    void departmentChanged(long userId, long oldDepartmentId, long newDepartmentId);
}
