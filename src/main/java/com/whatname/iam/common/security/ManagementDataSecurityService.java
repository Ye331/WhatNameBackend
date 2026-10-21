package com.whatname.iam.common.security;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.department.DepartmentCollaboration;
import com.whatname.iam.service.event.ExternalEventService;
import com.whatname.iam.service.event.TrustedEventContext;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.PermissionService;

import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;

import java.util.*;

/** A's adapter only. D owns the canonical payload, keys, permission calculation and audit seal. */
@Transactional(propagation = Propagation.MANDATORY)
public class ManagementDataSecurityService implements ManagementDataSecurity {
    private final DataSignatureService signatures;
    private final PermissionService permissions;
    private final AuditService audit;

    public ManagementDataSecurityService(
            DataSignatureService signatures, PermissionService permissions, AuditService audit) {
        this.signatures = signatures;
        this.permissions = permissions;
        this.audit = audit;
    }

    public void verifyIdentity(UserAccount user) {
        var current = facts(USER_IDENTITY, user.id);
        sameIdentity(user, current);
        signatures.verify(USER_IDENTITY, user.id);
        state().users.put(user.id, safeUser(user));
        state().identities.put(user.id, new LinkedHashMap<>(current));
    }

    public void requireFunction(long actorId, String permission) {
        permissions.requireFunction(actorId, permission);
    }

    public void signIdentity(UserAccount user) {
        var state = state();
        sameIdentity(user, facts(USER_IDENTITY, user.id));
        if (!state.users.containsKey(user.id)) {
            if (!AAccountCreation.contains(user)) throw new BusinessException(INTEGRITY_FAILURE);
            var grants = facts(USER_GRANTS, user.id);
            if (!List.of().equals(grants.get("roles")) || !List.of().equals(grants.get("sources")))
                throw new BusinessException(INTEGRITY_FAILURE);
            signatures.sign(USER_GRANTS, user.id);
            state.newUsers.add(user.id);
        }
        signatures.sign(USER_IDENTITY, user.id);
        state.signedUsers.add(user.id);
    }

    public void audit(String operation, Long actorId, Long targetId, String reason) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("reason", reason == null ? "" : reason);
        // Only the validated operation reason; never entities, credentials or login device/IP.
        if (targetId != null) {
            var before = state().users.get(targetId);
            if (before != null) evidence.put("before", before);
            evidence.put("after", safeFacts(facts(USER_IDENTITY, targetId)));
        }
        // A already writes one login-result row including IP/device. Do not call D.login again.
        audit.change(operation, actorId, "USER", targetId, null, evidence);
    }

    public void verifyOrganization(List<Department> organization) {
        var canonical = organizationFacts();
        if (canonical.size() != organization.size()) throw new BusinessException(INTEGRITY_FAILURE);
        for (Department d : organization) {
            var row = canonical.get(d.getId());
            if (row == null) throw new BusinessException(INTEGRITY_FAILURE);
            equal(row, "id", d.getId());
            equal(row, "parent_id", d.getParentId());
            equal(row, "dept_code", d.getDeptCode());
            equal(row, "dept_name", d.getDeptName());
            equal(row, "dept_path", d.getDeptPath());
            equal(row, "status", d.getStatus());
            equal(row, "merged_into_dept_id", d.getMergedIntoDeptId());
            equal(row, "manager_id", d.getManagerId());
            equal(row, "hr_version", d.getHrVersion());
            signatures.verify(DEPARTMENT, d.getId());
        }
        signatures.verify(ORGANIZATION, 0);
        state().organization = canonical;
    }

    public void verifyUsers(List<Map<String, Object>> users) {
        for (var row : users) {
            long id =
                    Long.parseLong(
                            Objects.requireNonNull(
                                            row.containsKey("userId")
                                                    ? row.get("userId")
                                                    : row.get("id"))
                                    .toString());
            var current = facts(USER_IDENTITY, id);
            equal(current, "id", id);
            for (var pair :
                    Map.of(
                                    "deptId",
                                    "dept_id",
                                    "sgNo",
                                    "employee_no",
                                    "status",
                                    "status",
                                    "sessionVersion",
                                    "session_version",
                                    "grantRevision",
                                    "grant_revision",
                                    "hrVersion",
                                    "hr_version")
                            .entrySet())
                if (row.containsKey(pair.getKey()))
                    equal(current, pair.getValue(), row.get(pair.getKey()));
            signatures.verify(USER_IDENTITY, id);
            state().users.put(id, safeFacts(current));
            state().projectedUsers.put(id, new LinkedHashMap<>(current));
        }
    }

    public void signOrganization(String operation, Object actor, Map<String, Object> evidence) {
        if (actor instanceof TrustedEventContext event) {
            signHr(operation, event, evidence);
            return;
        }
        if (!(actor instanceof DepartmentCollaboration.Manager manager)
                || manager.session() == null
                || !manager.actorId().equals(Long.toString(manager.session().userId()))
                || !Set.of("DEPT_FREEZE", "DEPT_UNFREEZE").contains(operation))
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        if (state().organization == null) throw new BusinessException(INTEGRITY_FAILURE);
        var ids = (Collection<?>) evidence.get("affectedDepartmentIds");
        if (ids == null || ids.size() != 1) throw new BusinessException(INTEGRITY_FAILURE);
        long id = Long.parseLong(ids.iterator().next().toString());
        signatures.sign(DEPARTMENT, id);
        signatures.sign(ORGANIZATION, 0);
        // Select the departmental fields, rather than forwarding arbitrary collaboration evidence.
        var retained = new LinkedHashMap<String, Object>();
        retained.put("before", safeDepartments(evidence.get("before"), Set.of(id)));
        retained.put("after", safeDepartments(evidence.get("after"), Set.of(id)));
        retained.put("affectedDepartmentIds", List.of(Long.toString(id)));
        retained.put("reason", Objects.toString(evidence.get("reasonOrEventId"), ""));
        audit.change(operation, manager.session().userId(), "DEPARTMENT", id, null, retained);
    }

    private void signHr(String operation, TrustedEventContext actor, Map<String, Object> evidence) {
        boolean person = Set.of("HR_EMPLOYEE", "HR_TRANSFER", "HR_TERMINATION").contains(operation);
        boolean organization = Set.of("HR_DEPARTMENT", "HR_MERGE").contains(operation);
        String eventId = Objects.toString(evidence.get(person ? "eventId" : "reasonOrEventId"), "");
        // A context value is not authority. Only the event runner can establish this evidence,
        // after the existing service-authentication port succeeds in this write transaction.
        var command = ExternalEventService.applying(actor, operation, eventId);
        if (!"HR".equals(actor.sourceSystem()) || !(person || organization) || command == null)
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        var retained = new LinkedHashMap<String, Object>();
        retained.put("sourceSystem", actor.sourceSystem());
        retained.put("clientId", actor.clientId());
        retained.put("serviceRequestId", actor.requestId());
        if (person) {
            long id = id(evidence.get("userId"));
            if (!state().signedUsers.contains(id)) throw new BusinessException(INTEGRITY_FAILURE);
            var current = facts(USER_IDENTITY, id);
            if (!"USER".equals(command.targetType()))
                throw new BusinessException(INTEGRITY_FAILURE);
            equal(current, "employee_no", command.targetRef());
            equal(current, "hr_version", command.eventVersion());
            equal(current, "source", 1);
            var old = state().identities.get(id);
            if (old == null) {
                if (!state().newUsers.contains(id) || !"HR_EMPLOYEE".equals(operation))
                    throw new BusinessException(INTEGRITY_FAILURE);
            } else {
                unchangedExcept(
                        old,
                        current,
                        Set.of(
                                "real_name_ciphertext",
                                "phone",
                                "phone_hash",
                                "dept_id",
                                "employment_type",
                                "status",
                                "session_version",
                                "grant_revision",
                                "hr_version"));
            }
            for (String key :
                    List.of("deptId", "status", "hrVersion", "sessionVersion", "grantRevision"))
                equal(
                        current,
                        Map.of(
                                        "deptId",
                                        "dept_id",
                                        "status",
                                        "status",
                                        "hrVersion",
                                        "hr_version",
                                        "sessionVersion",
                                        "session_version",
                                        "grantRevision",
                                        "grant_revision")
                                .get(key),
                        evidence.get(key));
            // C owns the grants/revision write and its signature; A never repairs this collection.
            signatures.verify(USER_GRANTS, id);
            signatures.verify(USER_IDENTITY, id);
            retained.put("before", state().users.getOrDefault(id, Map.of()));
            retained.put("after", safeFacts(current));
            audit.change(operation, null, "USER", id, eventId, retained);
            return;
        }
        var before = state().organization;
        if (before == null) throw new BusinessException(INTEGRITY_FAILURE);
        var after = organizationFacts();
        sameDepartments(evidence.get("before"), before);
        sameDepartments(evidence.get("after"), after);
        Set<Long> affected = ids(evidence.get("affectedDepartmentIds"));
        if (affected.isEmpty()
                || !after.keySet().containsAll(affected)
                || !after.keySet().containsAll(before.keySet()))
            throw new BusinessException(INTEGRITY_FAILURE);
        Set<Long> changed = new HashSet<>();
        after.forEach(
                (id, row) -> {
                    if (!Objects.equals(before.get(id), row)) changed.add(id);
                });
        if (!affected.containsAll(changed)) throw new BusinessException(INTEGRITY_FAILURE);
        var added = new HashSet<>(after.keySet());
        added.removeAll(before.keySet());
        var moved = movedUsers(evidence.get("movedUsers"));
        if ("HR_DEPARTMENT".equals(operation)) {
            if (added.size() > 1 || !moved.isEmpty())
                throw new BusinessException(INTEGRITY_FAILURE);
            var targets =
                    after.values().stream()
                            .filter(row -> command.targetRef().equals(row.get("dept_code")))
                            .toList();
            if (!"DEPT".equals(command.targetType()) || targets.size() != 1)
                throw new BusinessException(INTEGRITY_FAILURE);
            var target = targets.get(0);
            long targetId = id(target.get("id"));
            equal(target, "hr_version", command.eventVersion());
            if (!added.isEmpty() && !added.equals(Set.of(targetId)))
                throw new BusinessException(INTEGRITY_FAILURE);
            if (!added.isEmpty()) {
                equal(target, "status", 1);
                equal(target, "merged_into_dept_id", null);
            }
            for (var entry : before.entrySet())
                unchangedExcept(
                        entry.getValue(),
                        after.get(entry.getKey()),
                        entry.getKey() == targetId
                                ? Set.of(
                                        "parent_id",
                                        "dept_name",
                                        "dept_path",
                                        "manager_id",
                                        "hr_version")
                                : Set.of("dept_path"));
        } else {
            if (!added.isEmpty()) throw new BusinessException(INTEGRITY_FAILURE);
            signMergedUsers(command, before, after, moved, retained);
        }
        for (long id : affected) signatures.sign(DEPARTMENT, id);
        signatures.sign(ORGANIZATION, 0);
        retained.put("before", safeDepartments(evidence.get("before"), affected));
        retained.put("after", safeDepartments(evidence.get("after"), affected));
        retained.put(
                "affectedDepartmentIds", affected.stream().sorted().map(Object::toString).toList());
        audit.change(operation, null, "DEPARTMENT", null, eventId, retained);
    }

    private void signMergedUsers(
            ExternalEventService.Command command,
            Map<Long, Map<String, Object>> before,
            Map<Long, Map<String, Object>> after,
            Map<Long, Map<String, Object>> moved,
            Map<String, Object> retained) {
        var sources =
                after.entrySet().stream()
                        .filter(
                                entry ->
                                        entry.getValue().get("merged_into_dept_id") != null
                                                && !Objects.equals(
                                                        entry.getValue().get("merged_into_dept_id"),
                                                        before.get(entry.getKey())
                                                                .get("merged_into_dept_id")))
                        .toList();
        if (sources.size() != 1 || !moved.keySet().equals(state().projectedUsers.keySet()))
            throw new BusinessException(INTEGRITY_FAILURE);
        long source = sources.get(0).getKey(),
                target = id(sources.get(0).getValue().get("merged_into_dept_id"));
        if (!"DEPT".equals(command.targetType())) throw new BusinessException(INTEGRITY_FAILURE);
        equal(after.get(source), "dept_code", command.targetRef());
        equal(after.get(source), "hr_version", command.eventVersion());
        for (var entry : before.entrySet()) {
            long id = entry.getKey();
            var old = entry.getValue();
            var current = after.get(id);
            Set<String> changes =
                    id == source
                            ? Set.of("manager_id", "merged_into_dept_id", "hr_version")
                            : id(old.get("parent_id")) == source
                                    ? Set.of("parent_id", "dept_path")
                                    : Set.of("dept_path");
            unchangedExcept(old, current, changes);
            if (id != source && id(old.get("parent_id")) == source)
                equal(current, "parent_id", target);
        }
        for (var entry : moved.entrySet()) {
            long id = entry.getKey();
            var old = state().projectedUsers.get(id);
            var current = facts(USER_IDENTITY, id);
            equal(old, "dept_id", source);
            equal(current, "dept_id", target);
            for (String field : USER_IDENTITY.fields.split(","))
                if (!Set.of("dept_id", "grant_revision").contains(field))
                    equal(current, field, old.get(field));
            if (new java.math.BigDecimal(current.get("grant_revision").toString())
                            .compareTo(
                                    new java.math.BigDecimal(old.get("grant_revision").toString()))
                    < 0) throw new BusinessException(INTEGRITY_FAILURE);
            signatures.verify(USER_GRANTS, id);
            signatures.sign(USER_IDENTITY, id);
            var userEvidence = new LinkedHashMap<String, Object>(retained);
            userEvidence.put("before", safeFacts(old));
            userEvidence.put("after", safeFacts(current));
            // Keep each user's historical facts without exceeding D's fixed per-entry bound.
            // All entries, departmental signatures and APPLIED still share this transaction.
            audit.change("HR_MERGE", null, "USER", id, command.eventId(), userEvidence);
        }
        retained.put("movedUserCount", moved.size());
    }

    private void sameDepartments(Object value, Map<Long, Map<String, Object>> expected) {
        if (!(value instanceof Collection<?> rows) || rows.size() != expected.size())
            throw new BusinessException(INTEGRITY_FAILURE);
        var seen = new HashSet<Long>();
        var fields =
                Map.of(
                        "id",
                        "id",
                        "parentId",
                        "parent_id",
                        "code",
                        "dept_code",
                        "name",
                        "dept_name",
                        "path",
                        "dept_path",
                        "status",
                        "status",
                        "managerId",
                        "manager_id",
                        "hrVersion",
                        "hr_version",
                        "mergedIntoDeptId",
                        "merged_into_dept_id");
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) throw new BusinessException(INTEGRITY_FAILURE);
            long id = id(row.get("id"));
            var record = expected.get(id);
            if (record == null || !seen.add(id)) throw new BusinessException(INTEGRITY_FAILURE);
            fields.forEach((input, column) -> equal(record, column, row.get(input)));
        }
    }

    private Set<Long> ids(Object value) {
        if (!(value instanceof Collection<?> values))
            throw new BusinessException(INTEGRITY_FAILURE);
        Set<Long> ids = new HashSet<>();
        for (Object item : values)
            if (!ids.add(id(item))) throw new BusinessException(INTEGRITY_FAILURE);
        return ids;
    }

    private Map<Long, Map<String, Object>> movedUsers(Object value) {
        if (!(value instanceof Collection<?> rows)) throw new BusinessException(INTEGRITY_FAILURE);
        var result = new HashMap<Long, Map<String, Object>>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) throw new BusinessException(INTEGRITY_FAILURE);
            long id = id(row.get("id"));
            var verified = state().projectedUsers.get(id);
            if (verified == null || result.put(id, verified) != null)
                throw new BusinessException(INTEGRITY_FAILURE);
            for (var pair :
                    Map.of(
                                    "deptId",
                                    "dept_id",
                                    "sgNo",
                                    "employee_no",
                                    "status",
                                    "status",
                                    "sessionVersion",
                                    "session_version",
                                    "grantRevision",
                                    "grant_revision",
                                    "hrVersion",
                                    "hr_version")
                            .entrySet()) equal(verified, pair.getValue(), row.get(pair.getKey()));
        }
        return result;
    }

    private long id(Object value) {
        try {
            return Long.parseLong(Objects.requireNonNull(value).toString());
        } catch (RuntimeException failure) {
            throw new BusinessException(INTEGRITY_FAILURE);
        }
    }

    private void unchangedExcept(
            Map<String, Object> before, Map<String, Object> after, Set<String> allowed) {
        before.forEach(
                (field, value) -> {
                    if (!allowed.contains(field)) equal(after, field, value);
                });
    }

    private Map<String, Object> facts(
            com.whatname.iam.service.permission.SignedDataType type, long id) {
        @SuppressWarnings("unchecked")
        var facts = (Map<String, Object>) signatures.snapshot(type, id).get("facts");
        return facts;
    }

    private Map<Long, Map<String, Object>> organizationFacts() {
        @SuppressWarnings("unchecked")
        var rows = (List<Map<String, Object>>) facts(ORGANIZATION, 0).get("departments");
        var result = new HashMap<Long, Map<String, Object>>();
        rows.forEach(
                row -> result.put(((Number) row.get("id")).longValue(), new LinkedHashMap<>(row)));
        return result;
    }

    private void sameIdentity(UserAccount u, Map<String, Object> row) {
        equal(row, "id", u.id);
        equal(row, "username", u.username);
        equal(row, "real_name_ciphertext", u.realNameCiphertext);
        equal(row, "phone", u.phone);
        equal(row, "phone_hash", u.phoneHash);
        equal(row, "password_hash", u.passwordHash);
        equal(row, "employee_no", u.employeeNo);
        equal(row, "dept_id", u.deptId);
        equal(row, "status", u.status);
        equal(row, "source", u.source);
        equal(row, "employment_type", u.employmentType);
        equal(row, "password_initialized", u.passwordInitialized);
        equal(row, "session_version", u.sessionVersion);
        equal(row, "grant_revision", u.grantRevision);
        equal(row, "hr_version", u.hrVersion);
        equal(row, "created_by", u.createdBy);
        equal(row, "temp_reason", u.tempReason);
    }

    private void equal(Map<String, Object> row, String field, Object expected) {
        Object actual = row.get(field);
        boolean numeric =
                actual instanceof Number
                        && (expected instanceof Number
                                || expected instanceof String text && text.matches("-?[0-9]+"));
        boolean same =
                numeric
                        ? new java.math.BigDecimal(actual.toString())
                                        .compareTo(new java.math.BigDecimal(expected.toString()))
                                == 0
                        : Objects.equals(actual, expected);
        if (!same) throw new BusinessException(INTEGRITY_FAILURE);
    }

    private Map<String, Object> safeUser(UserAccount u) {
        return Map.of(
                "userId",
                u.id.toString(),
                "deptId",
                u.deptId.toString(),
                "status",
                u.status,
                "sessionVersion",
                u.sessionVersion,
                "grantRevision",
                u.grantRevision,
                "hrVersion",
                u.hrVersion);
    }

    private Map<String, Object> safeFacts(Map<String, Object> row) {
        return Map.of(
                "userId",
                row.get("id").toString(),
                "deptId",
                row.get("dept_id").toString(),
                "status",
                row.get("status"),
                "sessionVersion",
                row.get("session_version"),
                "grantRevision",
                row.get("grant_revision"),
                "hrVersion",
                row.get("hr_version"));
    }

    private List<Map<String, Object>> safeDepartments(Object value, Set<Long> affected) {
        if (!(value instanceof Collection<?> rows)) throw new BusinessException(INTEGRITY_FAILURE);
        var result = new ArrayList<Map<String, Object>>();
        for (Object item : rows) {
            if (!(item instanceof Map<?, ?> row)) throw new BusinessException(INTEGRITY_FAILURE);
            if (!affected.contains(id(row.get("id")))) continue;
            var safe = new LinkedHashMap<String, Object>();
            for (String key :
                    List.of(
                            "id",
                            "parentId",
                            "code",
                            "name",
                            "path",
                            "status",
                            "managerId",
                            "hrVersion",
                            "mergedIntoDeptId")) safe.put(key, row.get(key));
            result.add(safe);
        }
        return result;
    }

    private Verified state() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        for (var synchronization : TransactionSynchronizationManager.getSynchronizations())
            if (synchronization instanceof Verified verified && verified.owner == this)
                return verified;
        var verified = new Verified(this);
        TransactionSynchronizationManager.registerSynchronization(verified);
        return verified;
    }

    /**
     * Synchronizations suspend with REQUIRES_NEW and disappear on completion, unlike a ThreadLocal.
     */
    private static final class Verified implements TransactionSynchronization {
        final ManagementDataSecurityService owner;
        final Map<Long, Map<String, Object>> users = new HashMap<>();
        final Set<Long> signedUsers = new HashSet<>();
        final Set<Long> newUsers = new HashSet<>();
        final Map<Long, Map<String, Object>> identities = new HashMap<>();
        final Map<Long, Map<String, Object>> projectedUsers = new HashMap<>();
        Map<Long, Map<String, Object>> organization;

        Verified(ManagementDataSecurityService owner) {
            this.owner = owner;
        }
    }
}
