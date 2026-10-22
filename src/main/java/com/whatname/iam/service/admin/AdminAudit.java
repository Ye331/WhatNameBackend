package com.whatname.iam.service.admin;

import com.whatname.iam.service.audit.AuditService;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

/** 管理员任命、撤销和委派清单变更的审计。 */
@Service
public class AdminAudit {
    private final AuditService audit;

    public AdminAudit(AuditService audit) {
        this.audit = audit;
    }

    public record Change(
            String operation,
            long actorId,
            long targetId,
            String reason,
            AdminState before,
            AdminState after,
            Set<Long> changedAdminIds) {
        public Change {
            changedAdminIds = Set.copyOf(changedAdminIds);
        }
    }

    public void record(Change c) {
        audit.change(
                c.operation(),
                c.actorId(),
                "USER",
                c.targetId(),
                null,
                Map.of(
                        "schema",
                        "C_CHANGE_V1",
                        "reason",
                        c.reason(),
                        "authorization",
                        AuthorizationContext.current().authorization(c.actorId()),
                        "changedAdminIds",
                        c.changedAdminIds().stream().sorted().toList(),
                        "before",
                        ChangeEvidence.authority(c.before()),
                        "after",
                        ChangeEvidence.authority(c.after())));
    }
}
