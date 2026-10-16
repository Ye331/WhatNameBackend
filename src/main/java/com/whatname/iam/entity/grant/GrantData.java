package com.whatname.iam.entity.grant;

import java.time.LocalDateTime;

public final class GrantData {
    private GrantData() {}
    public record Source(Long id, Long userId, Long roleId, String sourceType, String sourceRef,
                         Long sourceDeptId, int status, Long grantedBy, Long revokedBy,
                         LocalDateTime revokedAt, LocalDateTime createdAt) {}
    public record EffectiveRole(Long id, Long userId, Long roleId, Long grantedBy, LocalDateTime grantedAt) {}
}
