package com.whatname.iam.service.grant.integration;

import static com.whatname.iam.common.api.ErrorCode.AUDIT_UNAVAILABLE;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.admin.integration.*;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.grant.GrantPorts;

import org.springframework.beans.factory.ObjectProvider;

import java.util.Map;

public final class DGrantAudit implements GrantPorts.Audit {
    private final ObjectProvider<AuditService> audit;

    public DGrantAudit(ObjectProvider<AuditService> audit) {
        this.audit = audit;
    }

    public void record(GrantPorts.Change c) {
        var d = audit.getIfAvailable();
        if (d == null) throw new BusinessException(AUDIT_UNAVAILABLE);
        d.change(
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
                        CExecutionContext.current().authorization(c.actorId()),
                        "authority",
                        CChangeEvidence.authority(c.authority()),
                        "before",
                        CChangeEvidence.grants(c.before()),
                        "after",
                        CChangeEvidence.grants(c.after())));
    }
}
