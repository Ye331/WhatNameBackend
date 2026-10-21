package com.whatname.iam.service.grant;

import com.whatname.iam.entity.grant.GrantData.*;

import java.util.List;

/** 单个用户的完整授权来源与角色关系集合，包含空集合；此快照不是签名序列化格式。 */
public record GrantState(
        long userId, long grantRevision, List<Source> sources, List<EffectiveRole> effectiveRoles) {
    public GrantState {
        sources = List.copyOf(sources);
        effectiveRoles = List.copyOf(effectiveRoles);
    }
}
