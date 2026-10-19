package com.whatname.iam.service.admin.integration;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.permission.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.context.request.*;
import java.util.*;
import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

/** A 认证声明 + D 当前会话/功能。身份阶段不读取用户，避免早于 M5 取得用户锁或 RR 快照。 */
public final class CRequestSecurity {
    private final ObjectProvider<JwtTokenService> jwt;
    private final ObjectProvider<PermissionService> permissions;
    private final ObjectProvider<DataSignatureService> signatures;
    public CRequestSecurity(ObjectProvider<JwtTokenService> jwt, ObjectProvider<PermissionService> permissions,
                            ObjectProvider<DataSignatureService> signatures) {
        this.jwt=jwt; this.permissions=permissions; this.signatures=signatures;
    }
    public long currentUserId() {
        String authorization = authorization();
        if (authorization == null) throw new BusinessException(INVALID_TOKEN);
        var verifier = jwt.getIfAvailable();
        if (verifier == null) throw new BusinessException(INVALID_TOKEN);
        // C HTTP 服务使用自己的事务；包括外层只读事务也不能借入旧 RR 快照。
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                if (!TransactionAspectSupport.currentTransactionStatus().isNewTransaction())
                    throw new BusinessException(AUTH_STATE_UNAVAILABLE);
            } catch (org.springframework.transaction.NoTransactionException unavailable) {
                throw new BusinessException(AUTH_STATE_UNAVAILABLE);
            }
        }
        return verifier.verifyBearer(authorization).userId();
    }
    public void require(long actorId, String permission) {
        var d = permissions.getIfAvailable();
        var signed = signatures.getIfAvailable();
        if (d == null || signed == null) throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        var context = CExecutionContext.current();
        Map<String,Object> origin;
        if (context.batchActor() != null) {
            if (context.batchActor() != actorId) throw new BusinessException(INVALID_TOKEN);
            origin = context.origin();
        } else {
            var a = jwt.getIfAvailable();
            if (a == null) throw new BusinessException(INVALID_TOKEN);
            // 等待锁后重新检查有效期、当前状态、sv 和注销记录，不能只复用 actorId。
            String authorization = authorization();
            var principal = a.verifyBearer(authorization);
            if (principal.userId() != actorId || d.currentUser(authorization) != actorId) throw new BusinessException(INVALID_TOKEN);
            origin = Map.of("kind", "HTTP_SESSION", "userId", actorId,
                    "sessionVersion", principal.sessionVersion(), "expiresAt", principal.expiresAt().toString(),
                    "jtiDigest", CanonicalEvidence.digest(principal.jti()));
        }
        d.requireFunction(actorId, permission);
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("permission", permission); evidence.put("origin", origin);
        evidence.put("identitySignature", signed.verify(USER_IDENTITY, actorId));
        evidence.put("grantsSignature", signed.verify(USER_GRANTS, actorId));
        var grantSnapshot = signed.snapshot(USER_GRANTS, actorId);
        evidence.put("grants", grantSnapshot);
        // 记录变更前实际功能依据；自回收之后不能用当前授权反推历史资格。
        var grants = (Map<?,?>) grantSnapshot.get("facts");
        var roles = (List<?>) grants.get("roles");
        var roleEvidence = new ArrayList<Map<String,Object>>();
        var menus = new TreeSet<Long>();
        for (Object role : roles) {
            long id = ((Number)role).longValue();
            var snapshot = signed.snapshot(ROLE_MENUS,id);
            roleEvidence.add(Map.of("facts", snapshot, "signature", signed.verify(ROLE_MENUS,id)));
            ((List<?>)((Map<?,?>)snapshot.get("facts")).get("menus")).forEach(menu -> menus.add(((Number)menu).longValue()));
        }
        evidence.put("roles", roleEvidence);
        // 保留当时菜单编码/状态及祖先、应用事实；这些是观察值，授权结论仍只由 D 决定。
        var resources = new ArrayList<Map<String,Object>>();
        var seen = new HashSet<Long>();
        var applications = new TreeSet<Long>();
        for (long menu : menus) {
            long id = menu;
            while (id != 0 && seen.add(id)) {
                var snapshot = signed.snapshot(RESOURCE_MENU,id);
                resources.add(snapshot);
                var facts = (Map<?,?>)snapshot.get("facts");
                applications.add(((Number)facts.get("app_id")).longValue());
                id = ((Number)facts.get("parent_id")).longValue();
            }
        }
        for(long id:applications) resources.add(signed.snapshot(RESOURCE_APPLICATION,id));
        evidence.put("resources",resources);
        context.authorized(actorId, evidence);
    }
    private static String authorization() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getHeader("Authorization") : null;
    }
}
