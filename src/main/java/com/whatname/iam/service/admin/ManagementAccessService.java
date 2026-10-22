package com.whatname.iam.service.admin;

import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.permission.PermissionService;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** 资源与角色管理共用的入口校验：当前会话、管理员资格、功能权限。 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class ManagementAccessService {
    private final JwtTokenService jwt;
    private final PermissionService permissions;
    private final AdminPolicyService admins;
    private final TransactionTemplate currentState;

    public ManagementAccessService(
            JwtTokenService jwt,
            PermissionService permissions,
            AdminPolicyService admins,
            PlatformTransactionManager transactions) {
        this.jwt = jwt;
        this.permissions = permissions;
        this.admins = admins;
        currentState = new TransactionTemplate(transactions);
        currentState.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        currentState.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public void requireRead(String bearer, String function) {
        long userId = permissions.currentUser(bearer);
        admins.requireAdministratorReadOnly(userId);
        permissions.requireFunction(userId, function);
    }

    public long requireLevelOneWrite(String bearer, String function) {
        long userId = jwt.verifyBearer(bearer).userId();
        // 先按统一顺序取得管理锁，再读取最新会话，防止等待期间注销的令牌继续写入。
        admins.requireLevelOne(userId);
        currentState.executeWithoutResult(
                status -> {
                    permissions.currentUser(bearer);
                    permissions.requireFunction(userId, function);
                });
        return userId;
    }
}
