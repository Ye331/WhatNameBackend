package com.whatname.iam.service.grant;

import com.whatname.iam.service.admin.AdminState;
import com.whatname.iam.service.admin.AuthorizationContext;
import com.whatname.iam.service.admin.ChangeEvidence;
import com.whatname.iam.service.audit.AuditService;

import org.springframework.stereotype.Service;

import java.util.Map;

/** 管理员授予、回收用户角色的审计。 */
@Service
public class GrantAudit {
    private final AuditService audit;

    public GrantAudit(AuditService audit) {
        this.audit = audit;
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

    public void record(Change c) {
        audit.change(
                c.operation(),
                c.actorId(),
                "USER",
                c.userId(),
                null,
                Map.of(
                        "schema",
                        "C_CHANGE_V1",
                        "reason",
                        c.reason(),
                        "roleId",
                        c.roleId(),
                        "sourceId",
                        c.sourceId(),
                        "authorization",
                        AuthorizationContext.current().authorization(c.actorId()),
                        "authority",
                        ChangeEvidence.authority(c.authority()),
                        "before",
                        ChangeEvidence.grants(c.before()),
                        "after",
                        ChangeEvidence.grants(c.after())));
    }
}
