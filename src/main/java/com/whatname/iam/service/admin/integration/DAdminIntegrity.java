package com.whatname.iam.service.admin.integration;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.admin.CResourceLockMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.permission.DataSignatureService;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

public final class DAdminIntegrity implements AdminPorts.Integrity {
    private final ObjectProvider<DataSignatureService> signatures;
    private final ObjectProvider<CResourceLockMapper> locks;

    public DAdminIntegrity(
            ObjectProvider<DataSignatureService> signatures,
            ObjectProvider<CResourceLockMapper> locks) {
        this.signatures = signatures;
        this.locks = locks;
    }

    public void verify(AdminState state) {
        var d = required();
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            var resources = locks.getIfAvailable();
            if (resources == null) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
            resources.applications();
            resources.menus();
            resources.apis();
            resources.roleMenus();
            resources.menuApis();
        }
        d.verify(ORGANIZATION, 0);
        new TreeSet<>(state.departments().keySet()).forEach(id -> d.verify(DEPARTMENT, id));
        new TreeSet<>(state.users().keySet()).forEach(id -> d.verify(USER_IDENTITY, id));
        for (long id : new TreeSet<>(state.appointments().keySet())) {
            d.verify(ADMIN, id);
            d.verify(ADMIN_DELEGABLE_ROLES, id);
        }
        new TreeSet<>(state.roles().keySet()).forEach(id -> d.verify(ROLE_MENUS, id));
    }

    public void sign(AdminState state, Set<Long> changedAdminIds) {
        var d = required();
        for (long id : new TreeSet<>(changedAdminIds)) {
            d.sign(ADMIN, id);
            d.sign(ADMIN_DELEGABLE_ROLES, id);
        }
    }

    private DataSignatureService required() {
        var d = signatures.getIfAvailable();
        if (d == null) throw new BusinessException(INTEGRITY_FAILURE);
        return d;
    }
}
