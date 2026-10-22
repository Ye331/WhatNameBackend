package com.whatname.iam.service.admin;

import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.mapper.admin.ResourceLockMapper;
import com.whatname.iam.service.permission.DataSignatureService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

/** 管理员模块的验签与签名，加入调用方事务。 */
@Service
public class AdminIntegrity {
    private final DataSignatureService signatures;
    private final ResourceLockMapper locks;

    public AdminIntegrity(DataSignatureService signatures, ResourceLockMapper locks) {
        this.signatures = signatures;
        this.locks = locks;
    }

    /** 根据这些 ID 读取完整规范记录并验签，不能直接对精简字段投影验签。 */
    public void verify(AdminState state) {
        if (!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            // 写入时锁住功能资源及映射，保证功能权限判断到提交前不被改变。
            locks.applications();
            locks.menus();
            locks.apis();
            locks.roleMenus();
            locks.menuApis();
        }
        signatures.verify(ORGANIZATION, 0);
        new TreeSet<>(state.departments().keySet())
                .forEach(id -> signatures.verify(DEPARTMENT, id));
        new TreeSet<>(state.users().keySet()).forEach(id -> signatures.verify(USER_IDENTITY, id));
        for (long id : new TreeSet<>(state.appointments().keySet())) {
            signatures.verify(ADMIN, id);
            signatures.verify(ADMIN_DELEGABLE_ROLES, id);
        }
        new TreeSet<>(state.roles().keySet()).forEach(id -> signatures.verify(ROLE_MENUS, id));
    }

    public void sign(AdminState state, Set<Long> changedAdminIds) {
        for (long id : new TreeSet<>(changedAdminIds)) {
            signatures.sign(ADMIN, id);
            signatures.sign(ADMIN_DELEGABLE_ROLES, id);
        }
    }
}
