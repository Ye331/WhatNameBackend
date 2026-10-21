package com.whatname.iam.service.admin.integration;

import static com.whatname.iam.common.api.ErrorCode.AUDIT_UNAVAILABLE;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.admin.AdminPorts;
import com.whatname.iam.service.audit.AuditService;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

public final class DAdminAudit implements AdminPorts.Audit {
    private final ObjectProvider<AuditService> audit;

    public DAdminAudit(ObjectProvider<AuditService> audit) {
        this.audit = audit;
    }

    public void record(AdminPorts.Change c) {
        var d = audit.getIfAvailable();
        if (d == null) throw new BusinessException(AUDIT_UNAVAILABLE);
        d.change(
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
                        CExecutionContext.current().authorization(c.actorId()),
                        "changedAdminIds",
                        c.changedAdminIds().stream().sorted().toList(),
                        "before",
                        CChangeEvidence.authority(c.before()),
                        "after",
                        CChangeEvidence.authority(c.after())));
    }
}
