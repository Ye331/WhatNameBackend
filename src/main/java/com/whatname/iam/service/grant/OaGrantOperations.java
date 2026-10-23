package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.SERVICE_IDENTITY;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.IdUtils;
import com.whatname.iam.dto.oa.OaDtos.Decision;
import com.whatname.iam.entity.grant.GrantData.Source;
import com.whatname.iam.repository.event.ExternalEventRepository;
import com.whatname.iam.repository.grant.GrantRepository;
import com.whatname.iam.repository.oa.OaScopeRepository;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.event.TrustedEventContext;
import com.whatname.iam.service.permission.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.*;

/** 以受控 OA 服务身份修改授权，加入既有事件事务。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class OaGrantOperations {
    private final GrantRepository repository;
    private final ExternalEventRepository events;
    private final AdminServiceLocks locks;
    private final AdminRules rules;
    private final OaScopeRepository services;
    private final ServiceOaCaller callers;
    private final DataSignatureService signatures;
    private final GrantIntegrity integrity;
    private final AuditService audit;
    private final ObjectMapper json;

    public OaGrantOperations(
            GrantRepository repository,
            ExternalEventRepository events,
            AdminServiceLocks locks,
            AdminRules rules,
            OaScopeRepository services,
            ServiceOaCaller callers,
            DataSignatureService signatures,
            GrantIntegrity integrity,
            AuditService audit,
            ObjectMapper json) {
        this.repository = repository;
        this.events = events;
        this.locks = locks;
        this.rules = rules;
        this.services = services;
        this.callers = callers;
        this.signatures = signatures;
        this.integrity = integrity;
        this.audit = audit;
        this.json = json;
    }

    public Map<String, Object> apply(TrustedEventContext actor, Decision request) {
        long user = IdUtils.parse(request.targetUserId()), role = IdUtils.parse(request.roleId());
        var event = events.lock("OA", request.eventId());
        if ((event == null || event.isEmpty())
                || !"OA_DECISION".equals(event.get("eventType"))
                || !"PROCESSING".equals(event.get("status"))
                || !"USER".equals(event.get("targetType"))
                || !request.targetUserId().equals(event.get("targetRef")))
            fail(AUTH_STATE_UNAVAILABLE);
        var scope = requireScope(actor, request, true);
        var before = load(user);
        integrity.verify(before);
        GrantOperations.consistent(before);
        Source source;
        boolean changed;
        if ("GRANT".equals(request.operation())) {
            source = repository.selectSourceByKey(user, role, "OA_APPROVAL", request.approvalId());
            if (source != null && source.status() != 1) fail(DUPLICATE_RESOURCE);
            changed = source == null;
            if (changed) {
                incrementable(before);
                expectOne(repository.insertOaSource(user, role, request.approvalId()));
                source = repository.selectSourceByKey(user, role, "OA_APPROVAL", request.approvalId());
                if (before.effectiveRoles().stream().noneMatch(r -> r.roleId() == role))
                    expectOne(repository.insertOaEffectiveRole(user, role));
            }
        } else {
            if (request.grantSourceId() != null) {
                long sourceId = IdUtils.parse(request.grantSourceId());
                source =
                        before.sources().stream()
                                .filter(
                                        s ->
                                                s.id() == sourceId
                                                        && s.userId() == user
                                                        && s.roleId() == role
                                                        && "OA_APPROVAL".equals(s.sourceType()))
                                .findFirst()
                                .orElse(null);
            } else
                source =
                        repository.selectSourceByKey(
                                user, role, "OA_APPROVAL", request.originalApprovalId());
            if (source == null) fail(GRANT_SOURCE_NOT_FOUND);
            changed = source.status() == 1;
            if (changed) {
                incrementable(before);
                expectOne(repository.revokeOaSource(user, role, source.id()));
                long sourceId = source.id();
                boolean supported =
                        before.sources().stream()
                                .anyMatch(
                                        s ->
                                                s.status() == 1
                                                        && s.roleId() == role
                                                        && s.id() != sourceId);
                if (!supported) expectOne(repository.deleteEffectiveRole(user, role));
            }
        }
        if (source == null
                || source.sourceDeptId() != null
                || !"OA_APPROVAL".equals(source.sourceType())) fail(INTEGRITY_FAILURE);
        GrantState after = before;
        if (changed) {
            expectOne(repository.advanceRevision(user, before.grantRevision()));
            after = load(user);
            GrantOperations.consistent(after);
            integrity.sign(after);
        }
        audit.change(
                "OA_DECISION",
                null,
                "USER",
                user,
                request.eventId(),
                Map.of(
                        "schema",
                        "C_OA_CHANGE_V1",
                        "changed",
                        changed,
                        "origin",
                        Map.of(
                                "sourceSystem",
                                "OA",
                                "clientId",
                                actor.clientId(),
                                "requestId",
                                actor.requestId(),
                                "eventId",
                                request.eventId()),
                        "decision",
                        decision(request, source),
                        "serviceScope",
                        scope,
                        "before",
                        ChangeEvidence.grants(before),
                        "after",
                        ChangeEvidence.grants(after)));
        return Map.of(
                "eventId",
                request.eventId(),
                "processingStatus",
                "APPLIED",
                "grantSourceId",
                source.id().toString(),
                "idempotentReplay",
                false);
    }

    /** 返回已保留结果后，在原业务事务外复核当前可见范围；不重写来源、版本、签名或成功审计。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.SERIALIZABLE)
    public void requireReplayScope(TrustedEventContext actor, Decision request) {
        requireScope(actor, request, false);
    }

    private Map<String, Object> requireScope(
            TrustedEventContext actor, Decision request, boolean mutating) {
        if (actor == null || !"OA".equals(actor.sourceSystem()) || actor.clientId() == null)
            fail(INVALID_SERVICE);
        long user = IdUtils.parse(request.targetUserId()), roleId = IdUtils.parse(request.roleId());
        // 锁序：事件 → 管理员/用户 → 功能资源 → 服务身份 → 授权事实。
        // 入口认证不得提前持有服务行锁并带入此业务阶段，以免形成反向锁序。
        var state = locks.lock(user);
        var identity = services.lock(actor.clientId());
        if ((identity == null || identity.isEmpty()) || !actor.clientId().equals(identity.get("client_id")))
            fail(INVALID_SERVICE);
        long serviceId = ((Number) identity.get("id")).longValue();
        signatures.verify(SERVICE_IDENTITY, serviceId);
        if (((Number) identity.get("status")).intValue() != 1
                || identity.get("app_id") != null
                || !"OA_DECISION".equals(identity.get("allowed_scope"))) fail(INVALID_SERVICE);
        callers.verifyTrustedEvent(actor, "OA_DECISION");
        var roles = ids(identity.get("allowed_role_ids"));
        var roots = ids(identity.get("allowed_dept_ids"));
        var operations = strings(identity.get("allowed_operations"));
        if (!Set.of("GRANT", "REVOKE").containsAll(operations)) fail(INTEGRITY_FAILURE);
        if (!roles.contains(roleId) || !operations.contains(request.operation()))
            fail(OA_SCOPE_DENIED);
        var person = state.users().get(user);
        var role = state.roles().get(roleId);
        if (person == null || role == null) fail(RESOURCE_NOT_FOUND);
        if (role.roleType() < 1 || role.roleType() > 2 || (mutating && role.status() != 1))
            fail(OA_SCOPE_DENIED);
        Set<Long> chain = new HashSet<>();
        long dept = person.deptId();
        while (dept != 0) {
            var d = state.departments().get(dept);
            if (d == null || !chain.add(dept) || d.parentId() < 0) fail(INTEGRITY_FAILURE);
            dept = d.parentId();
        }
        for (long root : roots) {
            var d = state.departments().get(root);
            if (d == null) fail(INTEGRITY_FAILURE);
            // 已合并祖先不能让配置根自动取得更大或不同的范围。
            var visited = new HashSet<Long>();
            long current = root;
            while (current != 0) {
                var parent = state.departments().get(current);
                if (parent == null || !visited.add(current)) fail(INTEGRITY_FAILURE);
                if (parent.mergedIntoDeptId() != null) fail(OA_SCOPE_DENIED);
                current = parent.parentId();
            }
        }
        if (roots.stream().noneMatch(chain::contains)) fail(OA_SCOPE_DENIED);
        if (mutating && "GRANT".equals(request.operation())) rules.requireUsableUser(state, user);
        return Map.of(
                "serviceIdentityId",
                serviceId,
                "clientId",
                actor.clientId(),
                "allowedRoleIds",
                roles.stream().sorted().toList(),
                "allowedDepartmentRoots",
                roots.stream().sorted().toList(),
                "allowedOperations",
                operations.stream().sorted().toList(),
                "allowedScope",
                "OA_DECISION",
                "serviceStatus",
                1,
                "target",
                Map.of("userId", user, "deptId", person.deptId(), "status", person.status()),
                "role",
                Map.of("roleId", roleId, "roleType", role.roleType(), "status", role.status()),
                "departmentChain",
                chain.stream().sorted().toList());
    }

    private Set<String> strings(Object value) {
        try {
            JsonNode array = json.readTree(Objects.toString(value));
            if (array == null || !array.isArray()) fail(INTEGRITY_FAILURE);
            Set<String> result = new HashSet<>();
            for (var item : array) {
                if (!item.isTextual() || item.textValue().isBlank()) fail(INTEGRITY_FAILURE);
                result.add(item.textValue());
            }
            return result;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new BusinessException(INTEGRITY_FAILURE);
        }
    }

    private Set<Long> ids(Object value) {
        Set<Long> result = new HashSet<>();
        for (String id : strings(value)) {
            try {
                result.add(IdUtils.parse(id));
            } catch (BusinessException error) {
                throw new BusinessException(INTEGRITY_FAILURE);
            }
        }
        return result;
    }

    private GrantState load(long user) {
        Long revision = repository.selectRevision(user, true);
        if (revision == null || revision < 1) fail(INTEGRITY_FAILURE);
        return new GrantState(
                user,
                revision,
                repository.selectSources(user, true),
                repository.selectEffectiveRoles(user, true));
    }

    private Map<String, Object> decision(Decision r, Source source) {
        var result = new LinkedHashMap<String, Object>();
        result.put("approvalId", r.approvalId());
        result.put("approvalStatus", r.approvalStatus());
        result.put("operation", r.operation());
        result.put("targetUserId", r.targetUserId());
        result.put("roleId", r.roleId());
        result.put("approvedAt", r.approvedAt().toInstant().toString());
        result.put("grantSourceId", source.id());
        result.put("originalApprovalId", source.sourceRef());
        return result;
    }

    private static void incrementable(GrantState s) {
        if (s.grantRevision() == Long.MAX_VALUE) fail(AUTH_STATE_UNAVAILABLE);
    }

    private static void expectOne(int n) {
        if (n != 1) fail(AUTH_STATE_UNAVAILABLE);
    }

    private static void fail(com.whatname.iam.common.api.ErrorCode code) {
        throw new BusinessException(code);
    }
}
