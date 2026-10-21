# HR02/03/04 personnel integration

Implemented routes are `PUT /api/v1/integrations/hr/employees/{sgNo}`, `POST /api/v1/integrations/hr/transfers` and `POST /api/v1/integrations/hr/terminations`.

`HrController` obtains service context through `DepartmentCollaboration.authenticateHr`; the body cannot establish trust. `ExternalEventService` verifies that context, claims the event and runs `HrService` in its SERIALIZABLE business transaction. Lock order is event row, full organization (including gaps), user, C/D facts. The transaction rechecks service authority. `DepartmentService.requireAssignableDepartment` verifies the organization and rejects merged targets. Freezing remains a separate business-access restriction; HR assignment to a frozen department does not unfreeze it.

HR02 requires realName, phone, deptCode, employmentType and occurredAt for every action. Missing/null action defaults to UPSERT. FORMAL/OUTSOURCED map to database 1/2; source is always 1. Initial usernames use each Han character's first pinyin initial (pinyin4j's first reading), retaining ASCII letters/digits in lower case. A collision appends the SG's final six characters (or the whole shorter SG); a second collision rejects 409. Unique indexes still arbitrate concurrent phone/SG/username writes. Unsupported name characters fail 422. Initial passwords are random, unknowable scrypt placeholders, uninitialized and absent from password history. Names/phones use the existing AES-GCM codec and normalized phone HMAC. Updates preserve ID, username, password and initialization state.

Lifecycle choices for unspecified boundaries are implementation decisions, not additional coordinator decisions:

| Current | UPSERT | SUSPEND | RESUME | REHIRE |
|---|---|---|---|---|
| Missing | Create active | 404 | 404 | 404 |
| Active | Preserve active | Pause, sv + 1 | Preserve active | 422 |
| Paused | Preserve paused | Preserve paused, no further sv bump | Active, same sv | 422 |
| Departed | 422 | 422 | 422 | Active, sv + 1, verify no old active entitlements |

Each accepted event advances only this person's hr_version to its supplied positive, strictly newer version. Versions need not be contiguous. A repeated APPLIED event returns its saved result without changing sv/versions again. A paused account retains grants; RESUME adds none and cannot restore old JWTs. REHIRE retains the account and lawful credentials but never reactivates historical grants/administrator qualifications. It fails if C cannot establish a clean entitlement state.

HR02 department changes and HR03 share `HrService.move`. HR03 validates current `fromDeptCode`, rejects departed users, equal from/to departments and merged targets. C must revoke exactly the old department's DEPT_POSITION sources, retain independent MANUAL/OA/other-department sources and history, recompute effective relations and update grant_revision. A does not change grant_revision. C then rechecks/restricts affected management scopes. No new role is granted. Transfer does not bump sv: JWTs contain no role snapshots, so M6 must read current grants on each request. This follows the existing schema's reset/pause/departure sv rules and the design's next-request authorization semantics; it is not a claim of real M6 integration.

HR04 requires effectiveAt with an offset and uses it as ExternalEventService.Command.occurredAt; no additional occurredAt request field exists. An injected `hrClock` rejects future notifications with 422, retaining REJECTED event evidence but leaving account, grants, sv and hr_version unchanged. Equality with Clock.now is effective. HR must use a new eventId when the time arrives. Already departed users reject 422. An applied departure revokes all sources/effective relations/administrator qualification via C, sets status=3 and increments sv. Account/source/audit history is retained.

`M1SecurityGateway` verifies/signs identity. `HrCollaboration` is the minimal C contract:

```java
void revokePosition(TrustedEventContext actor, String eventId, long userId, long oldDepartmentId);
void revokeAll(TrustedEventContext actor, String eventId, long userId);
void requireRehireEligible(long userId);
void departmentChanged(long userId, long oldDepartmentId, long newDepartmentId);
```

C must verify complete trusted source/qualification facts, retain history, sign changed sets and record before/after evidence, using the caller's transaction and datasource. Missing historical source information must reject. After C returns, A reloads grant_revision, signs the actual persisted identity and calls DepartmentCollaboration.signAndAudit. All account, C facts, signatures, audit and APPLIED result commit together; exceptions roll back business facts. The shared event service separately records REJECTED or PENDING_RECOVERY. PROCESSING returns 409; PENDING_RECOVERY returns 503; no automatic recovery or extra HTTP recovery route is provided.

Real C/D implementations are absent. The production configurations reject missing collaborators. The only allowing substitute is under `src/test`, with actual isolated MySQL writes and deliberately fake fingerprints (`TASK07_USER`, `TASK07_FAKE`), not D signatures. Those tests prove A orchestration/atomicity, not production C/D correctness, nonce/authentication, live M6 behavior or performance.

`HrBusinessIntegrationTest` runs only with TASK07_DB_TEST=true and a privately supplied TASK07_DB_PASSWORD. It uses HTTP 18087/18088 and sample SG/department/event/role prefixes T07-<run>-. End-of-run cleanup retires accounts, increments sv, revokes remaining fixture sources/qualifications, removes fixture effective relations and freezes fixture departments; no account/history is deleted. The original 111 DEV-DEPT departments are compared field-for-field. Test signatures remain historical fake artifacts for retired fixtures. See the external task07 handoff/evidence for actual results and source hashes. Build with JDK21, the Maven wrapper and the shared exclusive maven-build.lock; never clean a concurrent task's outputs.
