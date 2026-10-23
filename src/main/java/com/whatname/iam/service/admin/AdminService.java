package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.IdUtils;
import com.whatname.iam.dto.admin.AdminDtos.*;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.entity.admin.AdminData.*;
import com.whatname.iam.repository.admin.AdminRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@Transactional(readOnly = true)
public class AdminService {
    private final AdminRepository repository;
    private final AdminStateLoader loader;
    private final AdminRules rules;
    private final RequestSecurity security;
    private final AdminIntegrity integrity;
    private final AdminAudit audit;

    public AdminService(
            AdminRepository repository,
            AdminRules rules,
            RequestSecurity security,
            AdminIntegrity integrity,
            AdminAudit audit) {
        this.repository = repository;
        this.loader = new AdminStateLoader(repository);
        this.rules = rules;
        this.security = security;
        this.integrity = integrity;
        this.audit = audit;
    }

    public PageResponse<Summary> list(String deptId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 500) fail(INVALID_ARGUMENT);
        Long filterId = deptId == null ? null : IdUtils.parse(deptId);
        var context = begin("IAM_ADMINS_VIEW", false, null);
        var actor = rules.requireActive(context.state(), context.actorId());
        if (filterId != null
                && (!context.state().departments().containsKey(filterId)
                        || !rules.contains(context.state(), actor, filterId)))
            fail(RESOURCE_NOT_FOUND);
        var visible =
                context.state().appointments().values().stream()
                        .filter(item -> rules.contains(context.state(), actor, item.deptId()))
                        .filter(
                                item ->
                                        filterId == null
                                                || rules.contains(
                                                        context.state(),
                                                        new Appointment(
                                                                0L, 2, filterId, 1, 1, null),
                                                        item.deptId()))
                        .sorted(Comparator.comparing(Appointment::userId))
                        .toList();
        var items =
                visible.stream()
                        .skip(((long) page - 1) * pageSize)
                        .limit(pageSize)
                        .map(AdminService::summary)
                        .toList();
        return new PageResponse<>(items, page, pageSize, visible.size());
    }

    @Transactional
    public Summary appoint(AppointmentRequest request) {
        if (request == null) fail(INVALID_ARGUMENT);
        long targetId = IdUtils.parse(request.userId());
        long deptId = IdUtils.parse(request.deptId());
        reason(request.reason());
        if (request.adminLevel() < 1 || request.adminLevel() > 65535) fail(INVALID_ARGUMENT);
        var context = begin("IAM_ADMINS_WRITE", true, targetId);
        var state = context.state();
        var actor = rules.requireActive(state, context.actorId());
        var user = state.users().get(targetId);
        if (user == null || !rules.contains(state, actor, user.deptId())) fail(RESOURCE_NOT_FOUND);
        if (!state.departments().containsKey(deptId)) fail(RESOURCE_NOT_FOUND);
        rules.requireUsableUser(state, targetId);
        rules.checkAppointment(state, context.actorId(), request.adminLevel(), deptId);
        var existing = state.appointments().get(targetId);
        if (existing != null && existing.status() == 1) fail(DUPLICATE_RESOURCE);
        Set<Long> changed = new TreeSet<>();
        if (existing == null) {
            expectOne(
                    repository.insertAppointment(
                            targetId, request.adminLevel(), deptId, context.actorId()));
            changed.add(targetId);
        } else {
            changed.addAll(replaceList(state, targetId, Set.of(), context.actorId()));
            expectOne(repository.reactivate(targetId, request.adminLevel(), deptId, context.actorId()));
        }
        var after = finish("ADMIN_APPOINT", context, targetId, request.reason(), changed);
        return summary(after.appointments().get(targetId));
    }

    @Transactional
    public Revocation revoke(String userId, RevokeRequest request) {
        long targetId = IdUtils.parse(userId);
        if (request == null) fail(INVALID_ARGUMENT);
        reason(request.reason());
        var context = begin("IAM_ADMINS_WRITE", true, targetId);
        var actor = rules.requireActive(context.state(), context.actorId());
        var target = visibleAppointment(context.state(), actor, targetId);
        rules.checkRevocation(context.state(), context.actorId(), targetId);
        if (target.status() == 1) {
            expectOne(repository.revoke(targetId));
            finish("ADMIN_REVOKE", context, targetId, request.reason(), Set.of(targetId));
        }
        return new Revocation(Long.toString(targetId), 0, true);
    }

    public Scope myScope() {
        var context = begin("IAM_ADMINS_VIEW", false, null);
        var appointment = context.state().appointments().get(context.actorId());
        if (appointment == null || appointment.status() != 1) {
            return new Scope(
                    false,
                    null,
                    null,
                    List.of(),
                    appointment == null ? null : version(appointment.delegableRevision()));
        }
        rules.requireActive(context.state(), context.actorId());
        List<String> roots =
                appointment.adminLevel() == 1
                        ? context.state().departments().values().stream()
                                .filter(d -> d.parentId() == 0)
                                .map(Department::id)
                                .sorted()
                                .map(String::valueOf)
                                .toList()
                        : List.of(appointment.deptId().toString());
        return new Scope(
                true,
                appointment.adminLevel(),
                appointment.deptId().toString(),
                roots,
                version(appointment.delegableRevision()));
    }

    public DelegableRoles delegableRoles(String userId) {
        long targetId = IdUtils.parse(userId);
        var context = begin("IAM_GRANTS_VIEW", false, targetId);
        var actor = rules.requireActive(context.state(), context.actorId());
        var target = visibleAppointment(context.state(), actor, targetId);
        if (context.actorId() != targetId && actor.adminLevel() >= target.adminLevel())
            fail(RESOURCE_NOT_FOUND);
        return rolesView(context.state(), targetId);
    }

    @Transactional
    public DelegableRoles replaceDelegableRoles(String userId, DelegationRequest request) {
        long targetId = IdUtils.parse(userId);
        if (request == null || request.roleIds() == null) fail(INVALID_ARGUMENT);
        reason(request.reason());
        int expected = revision(request.expectedVersion());
        Set<Long> roleIds = new TreeSet<>();
        for (String roleId : request.roleIds())
            if (!roleIds.add(IdUtils.parse(roleId))) fail(INVALID_ARGUMENT);
        var context = begin("IAM_GRANTS_DELEGATE", true, targetId);
        var actor = rules.requireActive(context.state(), context.actorId());
        var target = visibleAppointment(context.state(), actor, targetId);
        rules.checkDelegationTarget(context.state(), context.actorId(), targetId);
        if (target.delegableRevision() != expected) fail(VERSION_CONFLICT);
        for (long roleId : roleIds) {
            rules.checkRole(context.state(), context.actorId(), roleId);
        }
        var changed = replaceList(context.state(), targetId, roleIds, context.actorId());
        var after = finish("ADMIN_DELEGATE", context, targetId, request.reason(), changed);
        return rolesView(after, targetId);
    }

    private Context begin(String permission, boolean write, Long targetId) {
        long actorId = security.currentUserId();
        if (actorId <= 0) fail(INVALID_TOKEN);
        var state =
                targetId == null
                        ? loader.load(write, actorId)
                        : loader.load(write, actorId, targetId);
        integrity.verify(state);
        security.require(actorId, permission);
        rules.requireUsableUser(state, actorId);
        return new Context(actorId, state);
    }

    private AdminState finish(
            String operation, Context context, long targetId, String reason, Set<Long> changed) {
        var after = loader.load(true, context.actorId(), targetId);
        integrity.sign(after, Set.copyOf(changed));
        audit.record(
                new AdminAudit.Change(
                        operation,
                        context.actorId(),
                        targetId,
                        reason,
                        context.state(),
                        after,
                        changed));
        return after;
    }

    private Set<Long> replaceList(
            AdminState before, long targetId, Set<Long> newRoles, long actorId) {
        record Removed(long adminId, long roleId) {}
        Deque<Removed> queue = new ArrayDeque<>();
        before.delegations().stream()
                .filter(d -> d.adminUserId() == targetId && !newRoles.contains(d.roleId()))
                .forEach(d -> queue.add(new Removed(targetId, d.roleId())));
        repository.deleteDelegations(targetId);
        for (long roleId : newRoles) expectOne(repository.insertDelegation(targetId, roleId, actorId));
        Set<Removed> visited = new HashSet<>();
        Set<Long> changed = new TreeSet<>(Set.of(targetId));
        while (!queue.isEmpty()) {
            var removed = queue.removeFirst();
            if (!visited.add(removed)) continue;
            for (var item : before.delegations()) {
                if (Objects.equals(item.configuredBy(), removed.adminId())
                        && item.roleId() == removed.roleId()
                        && !visited.contains(new Removed(item.adminUserId(), item.roleId()))) {
                    if (!before.appointments().containsKey(item.adminUserId()))
                        fail(AUTH_STATE_UNAVAILABLE);
                    expectOne(repository.deleteDelegation(item.adminUserId(), item.roleId()));
                    changed.add(item.adminUserId());
                    queue.addLast(new Removed(item.adminUserId(), item.roleId()));
                }
            }
        }
        for (long adminId : changed) {
            int old = before.appointments().get(adminId).delegableRevision();
            if (old < 1 || old == Integer.MAX_VALUE) fail(AUTH_STATE_UNAVAILABLE);
            if (repository.advanceRevision(adminId, old) != 1) fail(VERSION_CONFLICT);
        }
        return changed;
    }

    private Appointment visibleAppointment(AdminState state, Appointment actor, long targetId) {
        var target = state.appointments().get(targetId);
        if (target == null || !rules.contains(state, actor, target.deptId()))
            fail(RESOURCE_NOT_FOUND);
        return target;
    }

    private static DelegableRoles rolesView(AdminState state, long targetId) {
        var target = state.appointments().get(targetId);
        // 一级管理员没有上级委派清单，返回其实际可授予的普通业务/管理角色。
        var ids =
                target.adminLevel() == 1 && target.status() == 1
                        ? state.roles().values().stream()
                                .filter(
                                        role ->
                                                role.status() == 1
                                                        && (role.roleType() == 1
                                                                || role.roleType() == 2))
                                .map(Role::id)
                                .sorted()
                                .map(String::valueOf)
                                .toList()
                        : state.delegations().stream()
                                .filter(d -> d.adminUserId() == targetId)
                                .map(Delegation::roleId)
                                .sorted()
                                .map(String::valueOf)
                                .toList();
        return new DelegableRoles(
                Long.toString(targetId),
                ids,
                version(state.appointments().get(targetId).delegableRevision()));
    }

    private static Summary summary(Appointment value) {
        return new Summary(
                value.userId().toString(),
                value.adminLevel(),
                value.deptId().toString(),
                value.status());
    }

    private static int revision(String value) {
        if (value == null || !value.matches("v[1-9][0-9]{0,9}")) fail(INVALID_ARGUMENT);
        try {
            return Integer.parseInt(value.substring(1));
        } catch (NumberFormatException exception) {
            throw new BusinessException(INVALID_ARGUMENT);
        }
    }

    private static String version(int revision) {
        return "v" + revision;
    }

    private static void reason(String value) {
        if (value == null || value.isBlank() || value.length() > 512) fail(INVALID_ARGUMENT);
    }

    private static void expectOne(int affected) {
        if (affected != 1) fail(AUTH_STATE_UNAVAILABLE);
    }

    private static void fail(ErrorCode code) {
        throw new BusinessException(code);
    }

    private record Context(long actorId, AdminState state) {}
}
