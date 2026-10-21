package com.whatname.iam.service.grant;

import com.whatname.iam.service.admin.AdminState;

/** 适配器必须验证完整规范记录，并加入调用方的数据库事务。 */
public final class GrantPorts {
    private GrantPorts() {}

    public interface Integrity {
        // 验证完整规范用户身份（含授权修订号）、授权来源和角色关系集合。
        void verify(GrantState state);

        // 为更新后的用户身份及集合签名，包括回收最后一份来源后的空集合。
        void sign(GrantState state);
    }

    public interface Audit {
        void record(Change change);
    }

    public record Change(
            String operation,
            long actorId,
            long userId,
            long roleId,
            long sourceId,
            String reason,
            AdminState authority,
            GrantState before,
            GrantState after) {}
}
