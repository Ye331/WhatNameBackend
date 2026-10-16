package com.whatname.iam.service.grant;

import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.mapper.grant.GrantMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.entity.grant.GrantData.*;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.whatname.iam.common.api.ErrorCode.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class GrantOperations {
    private final GrantMapper mapper;
    private final AdminPolicyService policy;
    private final AdminPorts.Functions functions;
    private final GrantPorts.Integrity integrity;
    private final GrantPorts.Audit audit;
    public GrantOperations(GrantMapper mapper, AdminPolicyService policy,
                        AdminPorts.Functions functions, GrantPorts.Integrity integrity, GrantPorts.Audit audit) {
        this.mapper = mapper;
        this.policy = policy;
        this.functions = functions;
        this.integrity = integrity;
        this.audit = audit;
    }

    public Roles roles(long actor, String userId) {
        long user = id(userId);
        var context = begin(actor, user, null, false, false, "IAM_USERS_VIEW");
        var state = context.before();
        return new Roles(userId, version(state), state.effectiveRoles().stream()
                .sorted(Comparator.comparing(EffectiveRole::roleId))
                .map(r -> new RoleView(r.roleId().toString(), true, activeCount(state, r.roleId()))).toList());
    }

    public Sources sources(long actor, String userId, String roleId) {
        long user = id(userId), role = id(roleId);
        var context = begin(actor, user, null, false, false, "IAM_GRANTS_VIEW");
        if (!context.authority().roles().containsKey(role)) fail(RESOURCE_NOT_FOUND);
        return new Sources(userId, roleId, version(context.before()), context.before().sources().stream()
                .filter(s -> s.roleId() == role).sorted(Comparator.comparing(Source::id))
                .map(s -> new SourceView(s.id().toString(), s.sourceType(), s.sourceRef(),
                        s.sourceDeptId() == null ? null : s.sourceDeptId().toString(), s.status() == 1)).toList());
    }

    public Granted grant(long actor, GrantRequest request) {
        if (request == null) fail(INVALID_ARGUMENT);
        long user = id(request.userId()), role = id(request.roleId());
        Long expected = request.expectedVersion() == null ? null : revision(request.expectedVersion());
        text(request.reason(), 512);
        text(request.sourceRef(), 128);
        boolean position = "DEPT_POSITION".equals(request.sourceType());
        if (!position && !"MANUAL".equals(request.sourceType())) fail(INVALID_ARGUMENT);
        Long dept = request.sourceDeptId() == null ? null : id(request.sourceDeptId());
        if (position != (dept != null)) fail(INVALID_ARGUMENT);

        var context = begin(actor, user, role, true, true, "IAM_GRANTS_WRITE");
        if (position && !dept.equals(context.authority().users().get(user).deptId())) fail(INVALID_ARGUMENT);
        if (expected != null) checkVersion(context.before(), expected);
        // Use the database's unique-key collation for lookup as well as insertion.
        var existing = mapper.selectSourceByKey(user, role, request.sourceType(), request.sourceRef());
        if (existing != null) {
            if (!Objects.equals(existing.sourceDeptId(), dept)) fail(IDEMPOTENCY_CONFLICT);
            if (existing.status() != 1) fail(DUPLICATE_RESOURCE);
            return new Granted(request.userId(), request.roleId(), existing.id().toString(), true, version(context.before()));
        }
        requireIncrementable(context.before().grantRevision());
        expectOne(mapper.insertSource(user, role, request.sourceType(), request.sourceRef(), dept, context.actorId()));
        var inserted = mapper.selectSourceByKey(user, role, request.sourceType(), request.sourceRef());
        if (inserted == null) fail(AUTH_STATE_UNAVAILABLE);
        if (activeCount(context.before(), role) == 0) expectOne(mapper.insertEffectiveRole(user, role, context.actorId()));
        var after = finish(context, role, inserted.id(), "USER_ROLE_GRANT", request.reason());
        return new Granted(request.userId(), request.roleId(), inserted.id().toString(), true, version(after));
    }

    public Revoked revoke(long actor, RevokeRequest request) {
        if (request == null) fail(INVALID_ARGUMENT);
        long user = id(request.userId()), role = id(request.roleId()), source = id(request.grantSourceId());
        Long expected = request.expectedVersion() == null ? null : revision(request.expectedVersion());
        text(request.reason(), 512);
        var context = begin(actor, user, role, false, true, "IAM_GRANTS_WRITE");
        if (expected != null) checkVersion(context.before(), expected);
        var existing = context.before().sources().stream()
                .filter(s -> s.id() == source && s.userId() == user && s.roleId() == role)
                .findFirst().orElseThrow(() -> new BusinessException(GRANT_SOURCE_NOT_FOUND));
        int remaining = activeCount(context.before(), role);
        if (existing.status() == 1) {
            requireIncrementable(context.before().grantRevision());
            expectOne(mapper.revokeSource(user, role, source, context.actorId()));
            if (--remaining == 0) expectOne(mapper.deleteEffectiveRole(user, role));
            finish(context, role, source, "USER_ROLE_REVOKE", request.reason());
        }
        return new Revoked(request.userId(), request.roleId(), request.grantSourceId(), remaining > 0, remaining);
    }

    private Context begin(long actor, long user, Long role, boolean granting, boolean lock, String permission) {
        if (actor <= 0) fail(INVALID_TOKEN);
        // M5 locks admins -> departments -> roles -> delegation -> sorted actor/target users.
        var authority = policy.requireGrantContext(actor, user, role, granting, lock);
        // M6 must read current facts after waiting for M5 locks, before any mutation (including self-revocation).
        functions.require(actor, permission);
        var before = load(user, lock);
        integrity.verify(before);
        consistent(before);
        return new Context(actor, authority, before);
    }

    private GrantState load(long user, boolean lock) {
        Long revision = mapper.selectRevision(user, lock);
        if (revision == null || revision < 1) fail(AUTH_STATE_UNAVAILABLE);
        return new GrantState(user, revision, mapper.selectSources(user, lock), mapper.selectEffectiveRoles(user, lock));
    }

    private GrantState finish(Context context, long role, long source, String operation, String reason) {
        var before = context.before();
        expectOne(mapper.advanceRevision(before.userId(), before.grantRevision()));
        var after = load(before.userId(), true);
        consistent(after);
        integrity.sign(after);
        audit.record(new GrantPorts.Change(operation, context.actorId(), before.userId(), role, source,
                reason, context.authority(), before, after));
        return after;
    }

    private static void consistent(GrantState state) {
        Set<Long> backedRoles = new HashSet<>(), effectiveRoles = new HashSet<>();
        for (var source : state.sources()) {
            if (source.userId() != state.userId() || (source.status() != 0 && source.status() != 1)) fail(INTEGRITY_FAILURE);
            if (source.status() == 1) backedRoles.add(source.roleId());
        }
        for (var role : state.effectiveRoles()) {
            if (role.userId() != state.userId() || !effectiveRoles.add(role.roleId())) fail(INTEGRITY_FAILURE);
        }
        if (!backedRoles.equals(effectiveRoles)) fail(INTEGRITY_FAILURE);
    }
    private static int activeCount(GrantState state, long role) {
        return Math.toIntExact(state.sources().stream().filter(s -> s.roleId() == role && s.status() == 1).count());
    }
    private static void checkVersion(GrantState state, long expected) {
        if (state.grantRevision() != expected) fail(VERSION_CONFLICT);
    }
    private static void requireIncrementable(long revision) {
        if (revision == Long.MAX_VALUE) fail(AUTH_STATE_UNAVAILABLE);
    }
    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new BusinessException(INVALID_ARGUMENT);
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw new BusinessException(INVALID_ARGUMENT); }
    }
    static long revision(String value) {
        if (value == null || !value.startsWith("v")) throw new BusinessException(INVALID_ARGUMENT);
        return id(value.substring(1));
    }
    private static String version(GrantState state) { return "v" + state.grantRevision(); }
    private static void text(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) fail(INVALID_ARGUMENT);
    }
    private static void expectOne(int count) { if (count != 1) fail(AUTH_STATE_UNAVAILABLE); }
    private static void fail(ErrorCode code) { throw new BusinessException(code); }
    private record Context(long actorId, AdminState authority, GrantState before) {}
}
