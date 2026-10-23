package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.IdUtils;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.entity.grant.GrantData.*;
import com.whatname.iam.repository.grant.GrantRepository;
import com.whatname.iam.service.admin.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GrantOperations {
    private final GrantRepository repository;
    private final AdminPolicyService policy;
    private final RequestSecurity security;
    private final GrantIntegrity integrity;
    private final GrantAudit audit;

    public GrantOperations(
            GrantRepository repository,
            AdminPolicyService policy,
            RequestSecurity security,
            GrantIntegrity integrity,
            GrantAudit audit) {
        this.repository = repository;
        this.policy = policy;
        this.security = security;
        this.integrity = integrity;
        this.audit = audit;
    }

    public Roles roles(long actor, String userId) {
        long user = IdUtils.parse(userId);
        var context = begin(actor, user, null, false, false, "IAM_USERS_VIEW");
        var state = context.before();
        return new Roles(
                userId,
                version(state),
                state.effectiveRoles().stream()
                        .sorted(Comparator.comparing(EffectiveRole::roleId))
                        .map(
                                r ->
                                        new RoleView(
                                                r.roleId().toString(),
                                                true,
                                                activeCount(state, r.roleId())))
                        .toList());
    }

    public Sources sources(long actor, String userId, String roleId) {
        long user = IdUtils.parse(userId), role = IdUtils.parse(roleId);
        var context = begin(actor, user, null, false, false, "IAM_GRANTS_VIEW");
        if (!context.authority().roles().containsKey(role)) fail(RESOURCE_NOT_FOUND);
        return new Sources(
                userId,
                roleId,
                version(context.before()),
                context.before().sources().stream()
                        .filter(s -> s.roleId() == role)
                        .sorted(Comparator.comparing(Source::id))
                        .map(
                                s ->
                                        new SourceView(
                                                s.id().toString(),
                                                s.sourceType(),
                                                s.sourceRef(),
                                                s.sourceDeptId() == null
                                                        ? null
                                                        : s.sourceDeptId().toString(),
                                                s.status() == 1))
                        .toList());
    }

    public Granted grant(long actor, GrantRequest request) {
        if (request == null) fail(INVALID_ARGUMENT);
        long user = IdUtils.parse(request.userId()), role = IdUtils.parse(request.roleId());
        Long expected =
                request.expectedVersion() == null ? null : revision(request.expectedVersion());
        text(request.reason(), 512);
        text(request.sourceRef(), 128);
        boolean position = "DEPT_POSITION".equals(request.sourceType());
        if (!position && !"MANUAL".equals(request.sourceType())) fail(INVALID_ARGUMENT);
        Long dept = request.sourceDeptId() == null ? null : IdUtils.parse(request.sourceDeptId());
        if (position != (dept != null)) fail(INVALID_ARGUMENT);

        var context = begin(actor, user, role, true, true, "IAM_GRANTS_WRITE");
        if (position && !dept.equals(context.authority().users().get(user).deptId()))
            fail(INVALID_ARGUMENT);
        if (expected != null) checkVersion(context.before(), expected);
        // 查重与插入使用相同的数据库唯一键排序规则。
        var existing =
                repository.selectSourceByKey(user, role, request.sourceType(), request.sourceRef());
        if (existing != null) {
            if (!Objects.equals(existing.sourceDeptId(), dept)) fail(IDEMPOTENCY_CONFLICT);
            if (existing.status() != 1) fail(DUPLICATE_RESOURCE);
            return new Granted(
                    request.userId(),
                    request.roleId(),
                    existing.id().toString(),
                    true,
                    version(context.before()));
        }
        requireIncrementable(context.before().grantRevision());
        expectOne(
                repository.insertSource(
                        user,
                        role,
                        request.sourceType(),
                        request.sourceRef(),
                        dept,
                        context.actorId()));
        var inserted =
                repository.selectSourceByKey(user, role, request.sourceType(), request.sourceRef());
        if (inserted == null) fail(AUTH_STATE_UNAVAILABLE);
        if (activeCount(context.before(), role) == 0)
            expectOne(repository.insertEffectiveRole(user, role, context.actorId()));
        var after = finish(context, role, inserted.id(), "USER_ROLE_GRANT", request.reason());
        return new Granted(
                request.userId(), request.roleId(), inserted.id().toString(), true, version(after));
    }

    public Revoked revoke(long actor, RevokeRequest request) {
        if (request == null) fail(INVALID_ARGUMENT);
        long user = IdUtils.parse(request.userId()),
                role = IdUtils.parse(request.roleId()),
                source = IdUtils.parse(request.grantSourceId());
        Long expected =
                request.expectedVersion() == null ? null : revision(request.expectedVersion());
        text(request.reason(), 512);
        var context = begin(actor, user, role, false, true, "IAM_GRANTS_WRITE");
        if (expected != null) checkVersion(context.before(), expected);
        var existing =
                context.before().sources().stream()
                        .filter(s -> s.id() == source && s.userId() == user && s.roleId() == role)
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(GRANT_SOURCE_NOT_FOUND));
        int remaining = activeCount(context.before(), role);
        if (existing.status() == 1) {
            requireIncrementable(context.before().grantRevision());
            expectOne(repository.revokeSource(user, role, source, context.actorId()));
            if (--remaining == 0) expectOne(repository.deleteEffectiveRole(user, role));
            finish(context, role, source, "USER_ROLE_REVOKE", request.reason());
        }
        return new Revoked(
                request.userId(),
                request.roleId(),
                request.grantSourceId(),
                remaining > 0,
                remaining);
    }

    private Context begin(
            long actor, long user, Long role, boolean granting, boolean lock, String permission) {
        if (actor <= 0) fail(INVALID_TOKEN);
        // 管理锁顺序：管理员 → 部门 → 角色 → 委派清单 → 按 ID 排序的操作者与目标用户。
        var authority = policy.requireGrantContext(actor, user, role, granting, lock);
        // 等待管理锁后，功能权限必须读取当前事实；任何变更（包括自回收）都在检查通过后执行。
        security.require(actor, permission);
        var before = load(user, lock);
        integrity.verify(before);
        consistent(before);
        return new Context(actor, authority, before);
    }

    private GrantState load(long user, boolean lock) {
        Long revision = repository.selectRevision(user, lock);
        if (revision == null || revision < 1) fail(AUTH_STATE_UNAVAILABLE);
        return new GrantState(
                user,
                revision,
                repository.selectSources(user, lock),
                repository.selectEffectiveRoles(user, lock));
    }

    private GrantState finish(
            Context context, long role, long source, String operation, String reason) {
        var before = context.before();
        expectOne(repository.advanceRevision(before.userId(), before.grantRevision()));
        var after = load(before.userId(), true);
        consistent(after);
        integrity.sign(after);
        audit.record(
                new GrantAudit.Change(
                        operation,
                        context.actorId(),
                        before.userId(),
                        role,
                        source,
                        reason,
                        context.authority(),
                        before,
                        after));
        return after;
    }

    static void consistent(GrantState state) {
        Set<Long> backedRoles = new HashSet<>(), effectiveRoles = new HashSet<>();
        for (var source : state.sources()) {
            if (source.userId() != state.userId() || (source.status() != 0 && source.status() != 1))
                fail(INTEGRITY_FAILURE);
            if (source.status() == 1) backedRoles.add(source.roleId());
        }
        for (var role : state.effectiveRoles()) {
            if (role.userId() != state.userId() || !effectiveRoles.add(role.roleId()))
                fail(INTEGRITY_FAILURE);
        }
        if (!backedRoles.equals(effectiveRoles)) fail(INTEGRITY_FAILURE);
    }

    private static int activeCount(GrantState state, long role) {
        return Math.toIntExact(
                state.sources().stream()
                        .filter(s -> s.roleId() == role && s.status() == 1)
                        .count());
    }

    private static void checkVersion(GrantState state, long expected) {
        if (state.grantRevision() != expected) fail(VERSION_CONFLICT);
    }

    private static void requireIncrementable(long revision) {
        if (revision == Long.MAX_VALUE) fail(AUTH_STATE_UNAVAILABLE);
    }

    static long revision(String value) {
        if (value == null || !value.startsWith("v")) throw new BusinessException(INVALID_ARGUMENT);
        return IdUtils.parse(value.substring(1));
    }

    private static String version(GrantState state) {
        return "v" + state.grantRevision();
    }

    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) fail(INVALID_ARGUMENT);
    }

    private static void expectOne(int count) {
        if (count != 1) fail(AUTH_STATE_UNAVAILABLE);
    }

    private static void fail(ErrorCode code) {
        throw new BusinessException(code);
    }

    private record Context(long actorId, AdminState authority, GrantState before) {}
}
