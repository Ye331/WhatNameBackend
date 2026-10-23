package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.permission.*;

import org.springframework.stereotype.Service;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.*;

import java.util.*;

/** 管理请求的身份与功能检查：先只验证令牌得到操作者（不读库、不取锁）， 取得管理锁后再由 require 复核当前会话和功能权限，并记录授权依据供审计使用。 */
@Service
public class RequestSecurity {
    private final JwtTokenService jwt;
    private final PermissionService permissions;
    private final DataSignatureService signatures;

    public RequestSecurity(
            JwtTokenService jwt, PermissionService permissions, DataSignatureService signatures) {
        this.jwt = jwt;
        this.permissions = permissions;
        this.signatures = signatures;
    }

    public long currentUserId() {
        String authorization = authorization();
        if (authorization == null) throw new BusinessException(INVALID_TOKEN);
        // 管理服务使用自己的事务；包括外层只读事务也不能借入旧 RR 快照。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                if (!TransactionAspectSupport.currentTransactionStatus().isNewTransaction())
                    throw new BusinessException(AUTH_STATE_UNAVAILABLE);
            } catch (org.springframework.transaction.NoTransactionException unavailable) {
                throw new BusinessException(AUTH_STATE_UNAVAILABLE);
            }
        }
        return jwt.verifyBearer(authorization).userId();
    }

    public void require(long actorId, String permission) {
        var context = AuthorizationContext.current();
        // Every synchronous item retains the HTTP caller; recheck it after acquiring locks.
        String authorization = authorization();
        var principal = jwt.verifyBearer(authorization);
        if (principal.userId() != actorId || permissions.currentUser(authorization) != actorId)
            throw new BusinessException(INVALID_TOKEN);
        var origin = Map.<String, Object>of("kind", "HTTP_SESSION", "userId", actorId,
                "sessionVersion", principal.sessionVersion(), "expiresAt", principal.expiresAt().toString(),
                "jtiDigest", CanonicalEvidence.digest(principal.jti()));
        permissions.requireFunction(actorId, permission);
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("permission", permission);
        evidence.put("origin", origin);
        evidence.put("identitySignature", signatures.verify(USER_IDENTITY, actorId));
        evidence.put("grantsSignature", signatures.verify(USER_GRANTS, actorId));
        var grantSnapshot = signatures.snapshot(USER_GRANTS, actorId);
        evidence.put("grants", grantSnapshot);
        // 记录变更前实际功能依据；自回收之后不能用当前授权反推历史资格。
        var grants = (Map<?, ?>) grantSnapshot.get("facts");
        var roles = (List<?>) grants.get("roles");
        var roleEvidence = new ArrayList<Map<String, Object>>();
        var menus = new TreeSet<Long>();
        for (Object role : roles) {
            long id = ((Number) role).longValue();
            var snapshot = signatures.snapshot(ROLE_MENUS, id);
            roleEvidence.add(
                    Map.of("facts", snapshot, "signature", signatures.verify(ROLE_MENUS, id)));
            ((List<?>) ((Map<?, ?>) snapshot.get("facts")).get("menus"))
                    .forEach(menu -> menus.add(((Number) menu).longValue()));
        }
        evidence.put("roles", roleEvidence);
        // 保留当时菜单编码/状态及祖先、应用事实；这些是观察值，授权结论仍只由 PermissionService 决定。
        var resources = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<Long>();
        var applications = new TreeSet<Long>();
        for (long menu : menus) {
            long id = menu;
            while (id != 0 && seen.add(id)) {
                var snapshot = signatures.snapshot(RESOURCE_MENU, id);
                resources.add(snapshot);
                var facts = (Map<?, ?>) snapshot.get("facts");
                applications.add(((Number) facts.get("app_id")).longValue());
                id = ((Number) facts.get("parent_id")).longValue();
            }
        }
        for (long id : applications) resources.add(signatures.snapshot(RESOURCE_APPLICATION, id));
        evidence.put("resources", resources);
        context.authorized(actorId, evidence);
    }

    private static String authorization() {
        return RequestContextHolder.getRequestAttributes()
                        instanceof ServletRequestAttributes request
                ? request.getRequest().getHeader("Authorization")
                : null;
    }
}
