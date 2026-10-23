package com.whatname.iam.repository.grant;

import com.whatname.iam.entity.grant.GrantData.*;
import java.util.List;

public interface GrantRepositoryOperations {
    int insertOaSource(long userId, long roleId, String approvalId);

    int insertOaEffectiveRole(long userId, long roleId);

    int revokeOaSource(long userId, long roleId, long sourceId);

    Long selectRevision(long userId, boolean lock);

    List<Source> selectSources(long userId, boolean lock);

    List<EffectiveRole> selectEffectiveRoles(long userId, boolean lock);

    Source selectSourceByKey(long userId, long roleId, String type, String ref);

    int insertSource(long userId, long roleId, String type, String ref, Long deptId, long actorId);

    int insertEffectiveRole(long userId, long roleId, long actorId);

    int revokeSource(long userId, long roleId, long sourceId, long actorId);

    int revokeHrSource(long userId, long roleId, long sourceId);

    int deleteEffectiveRole(long userId, long roleId);

    int advanceRevision(long userId, long expected);

}
