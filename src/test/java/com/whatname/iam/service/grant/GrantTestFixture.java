package com.whatname.iam.service.grant;

import com.whatname.iam.entity.grant.GrantData.*;
import com.whatname.iam.mapper.grant.GrantMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.support.Fakes;

import java.time.LocalDateTime;
import java.util.*;

/** 内存数据库替身；SQL、锁与回滚由 GrantIT 单独验证。 */
public class GrantTestFixture implements GrantMapper {
    public int insertOaSource(long userId, long roleId, String approvalId) {
        throw new UnsupportedOperationException("OA uses MySQL integration tests");
    }

    public int insertOaEffectiveRole(long userId, long roleId) {
        throw new UnsupportedOperationException("OA uses MySQL integration tests");
    }

    public int revokeOaSource(long userId, long roleId, long sourceId) {
        throw new UnsupportedOperationException("OA uses MySQL integration tests");
    }

    public int revokeHrSource(long userId, long roleId, long sourceId) {
        throw new UnsupportedOperationException("HR uses the real MySQL integration fixture");
    }

    public final AdminTestFixture admins = new AdminTestFixture();
    public final Map<Long, Long> revisions = new HashMap<>();
    public final List<Source> sources = new ArrayList<>();
    public final List<EffectiveRole> effective = new ArrayList<>();
    public final List<GrantAudit.Change> events = new ArrayList<>();
    public final List<GrantState> signed = new ArrayList<>();
    public GrantIntegrity integrity =
            new GrantIntegrity(null) {
                public void verify(GrantState state) {}

                public void sign(GrantState state) {
                    signed.add(state);
                }
            };
    private long sequence = 900;

    public GrantTestFixture() {
        admins.users.keySet().forEach(id -> revisions.put(id, 1L));
    }

    public GrantService service() {
        return new GrantService(
                new GrantOperations(
                        this,
                        new AdminPolicyService(admins, new AdminRules(), admins.integrity),
                        Fakes.security(
                                () -> admins.actorId,
                                (actor, permission) -> admins.functions.require(actor, permission)),
                        new GrantIntegrity(null) {
                            public void verify(GrantState state) {
                                integrity.verify(state);
                            }

                            public void sign(GrantState state) {
                                integrity.sign(state);
                            }
                        },
                        Fakes.grantAudit(events::add)),
                Fakes.security(() -> admins.actorId, (actor, permission) -> {}));
    }

    public Long selectRevision(long userId, boolean lock) {
        return revisions.get(userId);
    }

    public List<Source> selectSources(long userId, boolean lock) {
        return sources.stream().filter(s -> s.userId() == userId).toList();
    }

    public List<EffectiveRole> selectEffectiveRoles(long userId, boolean lock) {
        return effective.stream().filter(r -> r.userId() == userId).toList();
    }

    public Source selectSourceByKey(long userId, long roleId, String type, String ref) {
        return sources.stream()
                .filter(
                        s ->
                                s.userId() == userId
                                        && s.roleId() == roleId
                                        && s.sourceType().equals(type)
                                        && s.sourceRef().equalsIgnoreCase(ref))
                .findFirst()
                .orElse(null);
    }

    public int insertSource(long user, long role, String type, String ref, Long dept, long actor) {
        sources.add(
                new Source(
                        ++sequence,
                        user,
                        role,
                        type,
                        ref,
                        dept,
                        1,
                        actor,
                        null,
                        null,
                        LocalDateTime.now()));
        return 1;
    }

    public int insertEffectiveRole(long user, long role, long actor) {
        effective.add(new EffectiveRole(++sequence, user, role, actor, LocalDateTime.now()));
        return 1;
    }

    public int revokeSource(long user, long role, long source, long actor) {
        for (int i = 0; i < sources.size(); i++) {
            var s = sources.get(i);
            if (s.id() == source && s.userId() == user && s.roleId() == role && s.status() == 1) {
                sources.set(
                        i,
                        new Source(
                                s.id(),
                                user,
                                role,
                                s.sourceType(),
                                s.sourceRef(),
                                s.sourceDeptId(),
                                0,
                                s.grantedBy(),
                                actor,
                                LocalDateTime.now(),
                                s.createdAt()));
                return 1;
            }
        }
        return 0;
    }

    public int deleteEffectiveRole(long user, long role) {
        return effective.removeIf(r -> r.userId() == user && r.roleId() == role) ? 1 : 0;
    }

    public int advanceRevision(long user, long expected) {
        if (!Objects.equals(revisions.get(user), expected) || expected == Long.MAX_VALUE) return 0;
        revisions.put(user, expected + 1);
        return 1;
    }
}
