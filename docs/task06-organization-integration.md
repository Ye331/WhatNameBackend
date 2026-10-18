# Task06 integration contract

**Historical Task06 record; superseded by Task20 (2026-10-18).** The unapproved event-owner column and migration02 below have been withdrawn; do not execute that historical migration. Current event schema matches main. Only the approved department-merge migration remains. Nonterminal online recovery is refused because existing interfaces cannot prove former workers have stopped. Current A-to-unchanged-C adaptation and limits are described in [the current integration section](task12-c-integration.md). Historical evidence below does not prove current source passed.

This module provides DP01/02/03/04, HR01/05 and ExternalEventService. HR02/03/04 personnel handling is outside this implementation. C/D implementations are absent in the baseline. DepartmentIntegrationConfiguration installs a refusal adapter (503) when no DepartmentCollaboration bean exists. Only src/test has an allowing fixture. No production property enables it.

## Existing database

Inspect information_schema and data first. Apply db/migrations/20261016_01_department_merge.sql, then 20261016_02_external_event_owner.sql once. Do not rerun the empty schema or use --force. The original 111 DEV-DEPT rows are preserved. New fields: nullable sys_dept.merged_into_dept_id with RESTRICT FK and insert/update guards; nullable sys_external_event.claim_token with no changes to existing event status. All 23 original tables remain.

status remains 0 frozen / 1 normal. Non-null merged_into_dept_id alone denotes a retained empty merged source. It cannot receive employees or appointments. All assignment writers must lock the department and check this field in their transaction. DepartmentService.requireAssignableDepartment(long) requires an existing SERIALIZABLE transaction and takes the full organization lock. Task05 independently uses lockEligibleDepartment FOR UPDATE and checks this column. C appointments must adopt equivalent checking; foreign-key existence is insufficient.

## Public external-event entry

```java
Map<String,Object> execute(TrustedEventContext context,
    ExternalEventService.Command command, Supplier<Map<String,Object>> applyAndAudit);
Map<String,Object> recover(TrustedEventContext context,
    ExternalEventService.Command command, Supplier<Map<String,Object>> applyAndAudit);
```

Caller orchestration must run outside an ambient transaction (enforced). The callback uses the same injected Spring MySQL transaction manager/datasource and participates in the event business transaction. C must not open REQUIRES_NEW for grant writes, signatures or audit. It must not report success until callback work and audit have completed. Callback results must be small sanitized maps without passwords/tokens/phones/names/credentials. Field-name checks provide a guard, not a replacement for selecting safe result fields.

```java
// D has verified OA client/key/signature/timestamp/nonce/scope and issues this context.
TrustedEventContext trusted = new TrustedEventContext("OA", verifiedClientId, requestId);
var command = new ExternalEventService.Command(
    "OA-APPROVAL-001", "OA_DECISION", null, "USER", "SG000123", null,
    Map.of("approvalId", "APPROVAL-001", "decision", "APPROVE", "roleId", "8"));
return events.execute(trusted, command, () -> {
    // C verifies OA operation/role/department scope; writes grants + source/history;
    // D verifies/signs affected facts and writes audit in this SAME transaction.
    return Map.of("eventId", "OA-APPROVAL-001", "processingStatus", "APPLIED");
});
```

Constructing TrustedEventContext is insufficient: DepartmentCollaboration.verifyTrustedEvent is called before claiming. The actual adapter must validate the issued context; never trust sourceSystem/clientId from the body. HTTP HR also calls authenticateHr first. D owns service signatures and nonce; this module never consumes a body nonce as authentication. Allowed event operations must be bound to verified service scopes.

## Transaction and recovery boundaries

Explicit TransactionTemplate boundaries avoid self-invocation proxy bugs. Claim/encrypted payload: REQUIRES_NEW READ_COMMITTED short transaction. Business: REQUIRES_NEW SERIALIZABLE, event row first, then whole sys_dept PRIMARY scan FOR UPDATE (including insertion gaps), then source users, then C/D facts/signatures/audit, and finally APPLIED + result in that same transaction. All RuntimeExceptions roll back; checked callback exceptions must be wrapped and propagated. Organization methods never catch a failed signature/audit and continue.

After rollback, a separate short transaction records REJECTED for definitive 4xx business errors or PENDING_RECOVERY for transient/integrity/audit/database failures. The UPDATE requires this claim_token and PROCESSING, so it never overwrites APPLIED. If even failure persistence fails, PROCESSING remains retained. Same-key same-content APPLIED returns retained result; changed content 409; PROCESSING 409 VERSION_CONFLICT; PENDING_RECOVERY 503; REJECTED replays original code. Client retries use fresh D nonce.

No automatic takeover or scheduler exists. recover additionally requires D/operator authorization, matching stable digest and decrypted retained canonical payload. It locks the event row (waits for any active transaction) and rotates a UUID ownership token. A delayed prior worker fails the token check before its callback; conditional APPLIED/failure updates fence its token too. Already APPLIED only replays; REJECTED requires a new eventId after correcting the cause. Call the handler registered for the recorded event type; do not accept arbitrary client recovery callbacks. Operator requestId is available to D for recovery authorization auditing.

All organization writers use SERIALIZABLE and lock the full PRIMARY scan before checking paths/cycles. No C/D adapter may acquire organization rows in a different order and then call this event orchestrator. Task05 has its own actor/session locking order; concurrent actor-user/organization writes can deadlock safely and must surface a retryable failure (not partial success). This correctness-first full organization lock is not a throughput/performance claim.

## C/D adapter responsibilities

Implement DepartmentCollaboration: current M6/session/actor frozen-chain authentication, current transactional function+M5 scope recheck, exact allowed department IDs, trusted HR/OA context validation, recovery authorization, organization/user signature verification, invalid-management-root restriction/review, and same-transaction signing+audit. A does not write C grants/admin tables as a substitute. The evidence map has before/after organization facts, affected IDs, pre-move user projections and event/reason. D must reread and sign actual post-move user facts as well as tree membership.

D signature format must include merged_into_dept_id in addition to id,parent_id,dept_code,dept_name,dept_path,manager_id,status,hr_version and relevant membership sets; moved user dept_id changes must be covered. This is a required integration delta, not an assertion that D has accepted a final canonical format.

DP01 defaults includeDescendants=true; false returns roots of the exact allowed set. Both return flat data.items. Out-of-scope ancestors are represented by parentId="0" and no path. Frozen/merged nodes remain visible in scope; merged target ID is only shown if in scope. DP02 defaults false (direct employees); true intersects subtree with allowed IDs before both count and paging; merged source returns empty rather than redirecting to target. IDs are strings.

## Verification

Explicit DEPARTMENT_DB_TEST=true enables DepartmentBusinessIntegrationTest on temporary ports18086/18087 and DepartmentMapperTest. Supply DEPARTMENT_DB_PASSWORD privately. Use JDK21 and the shared exclusive tmp/maven-build.lock. Do not run concurrently with task05 Maven builds. Test C/D port lives only in src/test, writes actual append-only audit rows, and injects verification/sign/audit/scope failures. It does not implement real Ed25519/M5/M6/nonce. Production default refusal is separately exercised over all six HTTP routes.

Tests use unique T06-<run>- department/account prefixes. Permanent-account/source/event/audit retention triggers prohibit deletion: fixtures are precisely retired (users left status3 with incremented sv, departments frozen, test role disabled, test sources revoked and retained). Original 111 seed rows are compared across every field including timestamps. Retained fixture rows are intentional development-test evidence and are not production users/signatures.

### Final verification, 2026-10-16 Asia/Shanghai

The resumed run finished at 12:57:18: DepartmentBusinessIntegrationTest 18 tests + DepartmentMapperTest 3 tests, all passed, zero skipped. This includes six actual HTTP routes, all six default-adapter refusals, source10/target40/event11, ancestor freeze presentation, hidden merge destinations, unsafe-result transaction rollback, ambient-transaction rejection and recovery ciphertext tampering. Only the C/D integration port is simulated; actual MySQL, Spring transactions, MyBatis and HTTP are used. Freeze/login/M6 and real C/D integration remain unverified.

The original 111 departments' full original-column fingerprint is unchanged from task04: `f47ad78eb5e9e8d7904fb9b19f43d6e5ab0bed3a9ad7c44af2173804b9751d98`; the new merge column is NULL. Both migrations are present, 23 tables and 18 triggers remain, including `tr_user_no_delete`. MySQL reports the merge FK as NO ACTION (non-cascading). No migration or empty-schema DDL was rerun during resumption.

Retained personal test fixtures:

| Prefix | Departments, all frozen | Accounts, all left | Origin |
|---|---:|---:|---|
| T06-mvagsjpl- | 40 | 11 | Earlier cleanup failed on account deletion; precisely retired during resumption |
| T06-mvahs70y- | 42 | 11 | First resumed run, 18 tests passed |
| T06-mvahx5m6- | 45 | 11 | Final resumed run, 21 tests passed |

Two retained fixture roles are disabled, two retained role sources are revoked, and no active test user-role rows remain. Events/audit stay retained; earlier event-only prefix `T06-mvagriu0-` also remains. PENDING_RECOVERY rows are intentional failure/tamper evidence and must not be replayed as production work. Test ciphertext uses explicitly injected test keys only. Personal fixtures do not provide production signatures or real HR users.

Independent Surefire copies: `D:\Desktop\OOAD\tmp\task06-final-reports`; final log: `D:\Desktop\OOAD\tmp\task06-final-integration-tests.log`. Exact retained IDs, migration metadata and seed fingerprint: `D:\Desktop\OOAD\tmp\task06-database-evidence.json`; read-only reproduction: `D:\Desktop\OOAD\tmp\task06-final-evidence.py`. Contract parsing and 263 local references are checked separately in `D:\Desktop\OOAD\tmp\task06-contract-verification.json`. The complete handoff and file allowlist are in `D:\Desktop\OOAD\子任务06_交接摘要.md`.
