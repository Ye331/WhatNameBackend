package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.repository.admin.AdminRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

/** 为可信服务写入按序锁定事实；取得锁本身不产生管理员或服务权限。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class AdminServiceLocks {
    private final AdminStateLoader loader;
    private final AdminIntegrity integrity;

    public AdminServiceLocks(AdminRepository repository, AdminIntegrity integrity) {
        this.loader = new AdminStateLoader(repository);
        this.integrity = integrity;
    }

    public AdminState lock(long userId) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                || !Objects.equals(
                        TransactionSynchronizationManager.getCurrentTransactionIsolationLevel(),
                        java.sql.Connection.TRANSACTION_SERIALIZABLE))
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        var state = loader.load(true, userId);
        integrity.verify(state);
        return state;
    }
}
