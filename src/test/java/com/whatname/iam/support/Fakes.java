package com.whatname.iam.support;

import com.whatname.iam.service.admin.AdminAudit;
import com.whatname.iam.service.admin.RequestSecurity;
import com.whatname.iam.service.grant.GrantAudit;

import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** 服务单元测试用的简单替身：用 lambda 指定当前操作者、功能检查和审计去向。 */
public final class Fakes {
    private Fakes() {}

    public interface Require {
        void require(long actorId, String permission);
    }

    public static RequestSecurity security(LongSupplier actor, Require require) {
        return new RequestSecurity(null, null, null) {
            @Override
            public long currentUserId() {
                return actor.getAsLong();
            }

            @Override
            public void require(long actorId, String permission) {
                require.require(actorId, permission);
            }
        };
    }

    public static AdminAudit adminAudit(Consumer<AdminAudit.Change> sink) {
        return new AdminAudit(null) {
            @Override
            public void record(AdminAudit.Change change) {
                sink.accept(change);
            }
        };
    }

    public static GrantAudit grantAudit(Consumer<GrantAudit.Change> sink) {
        return new GrantAudit(null) {
            @Override
            public void record(GrantAudit.Change change) {
                sink.accept(change);
            }
        };
    }
}
