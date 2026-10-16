package com.whatname.iam.service.grant;

import com.whatname.iam.entity.grant.GrantData.*;
import java.util.List;

/** A complete user's source/relationship membership, including empty sets; not a signature wire format. */
public record GrantState(long userId, long grantRevision, List<Source> sources, List<EffectiveRole> effectiveRoles) {
    public GrantState {
        sources = List.copyOf(sources);
        effectiveRoles = List.copyOf(effectiveRoles);
    }
}
