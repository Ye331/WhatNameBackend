package com.whatname.iam.service.grant.integration;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.grant.*;
import com.whatname.iam.service.permission.DataSignatureService;

import org.springframework.beans.factory.ObjectProvider;

public final class DGrantIntegrity implements GrantPorts.Integrity {
    private final ObjectProvider<DataSignatureService> signatures;

    public DGrantIntegrity(ObjectProvider<DataSignatureService> signatures) {
        this.signatures = signatures;
    }

    public void verify(GrantState state) {
        var d = required();
        d.verify(USER_IDENTITY, state.userId());
        d.verify(USER_GRANTS, state.userId());
    }

    public void sign(GrantState state) {
        var d = required();
        d.sign(USER_IDENTITY, state.userId());
        d.sign(USER_GRANTS, state.userId());
    }

    private DataSignatureService required() {
        var d = signatures.getIfAvailable();
        if (d == null) throw new BusinessException(INTEGRITY_FAILURE);
        return d;
    }
}
