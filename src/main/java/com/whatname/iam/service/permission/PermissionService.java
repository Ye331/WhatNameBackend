package com.whatname.iam.service.permission;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.repository.permission.*;
import com.whatname.iam.service.audit.AuditService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
public class PermissionService {
    private final SignatureRepository facts;
    private final PermissionRepository repository;
    private final DataSignatureService signatures;
    private final JwtTokenService jwt;
    private final AuditService audit;
    private final String managementApplication;
    private final TransactionTemplate decisions;

    public PermissionService(
            SignatureRepository facts,
            PermissionRepository repository,
            DataSignatureService signatures,
            JwtTokenService jwt,
            AuditService audit,
            @Value("${iam.permission.management-app-code:IAM}") String managementApplication,
            PlatformTransactionManager transactions) {
        this.facts = facts;
        this.repository = repository;
        this.signatures = signatures;
        this.jwt = jwt;
        this.audit = audit;
        this.managementApplication = managementApplication;
        decisions = new TransactionTemplate(transactions);
        decisions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        decisions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> ownMenus(String authorization, String appCode) {
        long userId = currentUser(authorization);
        return Map.of(
                "appCode",
                appCode,
                "menus",
                calculate(userId, application(appCode)).display(),
                "management",
                ownManagement(userId));
    }

    /** 仅返回本人已验签的任命摘要，页面无需额外取得管理员列表权限。 */
    private Map<String, Object> ownManagement(long userId) {
        var appointment = facts.record(ADMIN, userId);
        if (appointment == null || appointment.isEmpty()) return Map.of("appointed", false);
        signatures.verify(ADMIN, userId);
        if (number(appointment, "status") != 1) return Map.of("appointed", false);
        return Map.of(
                "appointed",
                true,
                "adminLevel",
                Math.toIntExact(number(appointment, "admin_level")));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long currentUser(String authorization) {
        var principal = jwt.verifyBearer(authorization);
        var user = facts.record(USER_IDENTITY, principal.userId());
        if ((user == null || user.isEmpty())
                || number(user, "status") != 1
                || number(user, "password_initialized") != 1
                || number(user, "session_version") != principal.sessionVersion()
                || repository.invalidated(principal.jti()) != 0)
            throw new BusinessException(INVALID_TOKEN);
        signatures.verify(USER_IDENTITY, principal.userId());
        return principal.userId();
    }

    /** 校验功能权限；管理写入还必须通过任命、范围和锁后复核。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFunction(long userId, String permission) {
        var view = calculate(userId, application(managementApplication));
        if (view.granted().stream()
                .map(view.menus()::get)
                .noneMatch(menu -> permission.equals(menu.get("menu_code"))))
            throw new BusinessException(PERMISSION_DENIED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> userMenus(long userId, String appCode) {
        return Map.of(
                "userId",
                Long.toString(userId),
                "appCode",
                appCode,
                "menus",
                calculate(userId, application(appCode)).display());
    }

    public Map<String, Object> check(
            String authorization, long applicationId, String method, String route) {
        var outcome =
                Objects.requireNonNull(
                        decisions.execute(
                                status -> decide(authorization, applicationId, method, route)));
        // Denial evidence has committed before a token/integrity fault reaches the HTTP handler.
        if (outcome.fault() != null) throw outcome.fault();
        return outcome.result();
    }

    private Outcome decide(String authorization, long applicationId, String method, String route) {
        if (method == null
                || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
                        .contains(method)) throw new BusinessException(INVALID_ARGUMENT);
        String canonical =
                RouteTemplateUtils.canonicalTemplate(RouteTemplateUtils.normalizeTemplate(route));
        String decisionId = UUID.randomUUID().toString();
        Long userId = null, apiId = null;
        String reason = "NO_PERMISSION";
        boolean allowed = false;
        BusinessException fault = null;
        try {
            userId = currentUser(authorization);
            var view = calculate(userId, applicationId);
            var candidates =
                    repository.apis(applicationId).stream()
                            .filter(
                                    api ->
                                            method.equals(api.get("http_method"))
                                                    && canonical.equals(
                                                            RouteTemplateUtils.canonicalTemplate(
                                                                    RouteTemplateUtils
                                                                            .normalizeTemplate(
                                                                                    (String)
                                                                                            api.get(
                                                                                                    "api_path")))))
                            .toList();
            if (candidates.size() != 1)
                reason = candidates.isEmpty() ? "UNKNOWN_API" : "AMBIGUOUS_API";
            else {
                var api = candidates.getFirst();
                apiId = number(api, "id");
                signatures.verify(RESOURCE_API, apiId);
                if (number(api, "status") != 1) reason = "API_DISABLED";
                else {
                    allowed = view.apiIds().contains(apiId);
                    reason = allowed ? "ALLOW" : view.reason();
                }
            }
        } catch (BusinessException error) {
            fault = error;
            reason = error.errorCode().name();
            allowed = false;
        }
        audit.decision(userId, applicationId, apiId, allowed, reason, method, route,
                Map.of("decisionId", decisionId));
        return new Outcome(Map.of("allowed", allowed, "reasonCode", reason,
                "decisionId", decisionId), fault);
    }

    private long application(String appCode) {
        if (appCode == null || !appCode.matches("[A-Za-z0-9_-]{1,64}"))
            throw new BusinessException(INVALID_ARGUMENT);
        Long id = repository.applicationId(appCode);
        if (id == null) throw new BusinessException(RESOURCE_NOT_FOUND);
        return id;
    }

    private View calculate(long userId, long applicationId) {
        var user = facts.record(USER_IDENTITY, userId);
        if ((user == null || user.isEmpty())) throw new BusinessException(RESOURCE_NOT_FOUND);
        signatures.verify(USER_IDENTITY, userId);
        var app = facts.record(RESOURCE_APPLICATION, applicationId);
        if ((app == null || app.isEmpty())) throw new BusinessException(INVALID_SERVICE);
        signatures.verify(RESOURCE_APPLICATION, applicationId);
        signatures.verify(ORGANIZATION, 0);
        var departments = new HashMap<Long, Map<String, Object>>();
        facts.organization().forEach(row -> departments.put(number(row, "id"), row));
        var ancestors = chain(departments, number(user, "dept_id"), "parent_id");
        boolean frozen =
                ancestors.stream()
                        .anyMatch(
                                row ->
                                        number(row, "status") != 1
                                                || row.get("merged_into_dept_id") != null);
        var menus = new LinkedHashMap<Long, Map<String, Object>>();
        repository.menus(applicationId).forEach(menu -> menus.put(number(menu, "id"), menu));
        var granted = new LinkedHashSet<Long>();
        if (number(user, "status") != 1
                || number(user, "password_initialized") != 1
                || number(app, "status") != 1
                || frozen)
            return new View(
                    menus,
                    granted,
                    Set.of(),
                    List.of(),
                    frozen ? "DEPARTMENT_FROZEN" : "STATE_DISABLED");
        List<Long> roles = verifiedRoles(userId);
        Set<Long> verifiedMenus =
                collectRoleMenus(roles, applicationId, menus, granted);
        signatures.verifyMany(RESOURCE_MENU, verifiedMenus);
        signatures.verifyMany(MENU_APIS, granted);
        var apiIds = new HashSet<Long>();
        if (!granted.isEmpty())
            facts.menuApiMembers(new ArrayList<>(granted))
                    .forEach(
                            row -> {
                                long apiId = number(row, "api_id");
                                apiIds.add(apiId);
                            });
        var displayed = new HashSet<Long>();
        for (long id : granted)
            chain(menus, id, "parent_id").forEach(menu -> displayed.add(number(menu, "id")));
        var display =
                menus.values().stream()
                        .filter(menu -> displayed.contains(number(menu, "id")))
                        .map(menu -> menuView(menu, granted.contains(number(menu, "id"))))
                        .toList();
        return new View(
                menus, granted, apiIds, display, "NO_PERMISSION");
    }

    /** 多角色权限取并集；目录用于导航，只有启用的权限菜单可以授予。 */
    private Set<Long> collectRoleMenus(
            List<Long> roles,
            long applicationId,
            Map<Long, Map<String, Object>> menus,
            Set<Long> granted) {
        var verifiedMenus = new HashSet<Long>();
        for (long roleId : roles) {
            signatures.verify(ROLE_MENUS, roleId);
            var role = facts.record(ROLE_MENUS, roleId);
            var menuIds = facts.roleMenus(roleId);
            if (number(role, "status") != 1) continue;
            for (long menuId : menuIds) {
                var menu = menus.get(menuId);
                if ((menu == null || menu.isEmpty())) menu = facts.record(RESOURCE_MENU, menuId);
                if ((menu == null || menu.isEmpty())) throw new BusinessException(INTEGRITY_FAILURE);
                if (number(menu, "app_id") != applicationId) continue;
                if (number(menu, "menu_type") != 2) throw new BusinessException(INTEGRITY_FAILURE);
                var menuChain = chain(menus, menuId, "parent_id");
                for (var parent : menuChain) {
                    long id = number(parent, "id");
                    verifiedMenus.add(id);
                }
                if (menuChain.stream().anyMatch(parent -> number(parent, "status") != 1)) continue;
                granted.add(menuId);
            }
        }
        return verifiedMenus;
    }

    /** 有效用户角色必须与有效授权来源一致，避免直接改关系表绕过授权。 */
    private List<Long> verifiedRoles(long userId) {
        signatures.verify(USER_GRANTS, userId);
        var roles = facts.userRoles(userId);
        var sources = facts.sources(userId);
        var sourcedRoles = new TreeSet<Long>();
        for (var source : sources) {
            if (!Set.of("MANUAL", "DEPT_POSITION", "OA_APPROVAL")
                            .contains(source.get("source_type"))
                    || source.get("source_ref") == null
                    || source.get("source_ref").toString().isBlank()
                    || ("DEPT_POSITION".equals(source.get("source_type"))
                            && source.get("source_dept_id") == null))
                throw new BusinessException(INTEGRITY_FAILURE);
            sourcedRoles.add(number(source, "role_id"));
        }
        if (!new TreeSet<>(roles).equals(sourcedRoles))
            throw new BusinessException(INTEGRITY_FAILURE);
        return roles;
    }

    private Map<String, Object> menuView(Map<String, Object> menu, boolean granted) {
        var result = new LinkedHashMap<String, Object>();
        result.put("menuId", Long.toString(number(menu, "id")));
        result.put("parentId", Long.toString(number(menu, "parent_id")));
        result.put("menuCode", menu.get("menu_code"));
        result.put("menuName", menu.get("menu_name"));
        result.put("menuType", Math.toIntExact(number(menu, "menu_type")));
        result.put("path", menu.get("path"));
        result.put("navigationOnly", !granted);
        return result;
    }

    private List<Map<String, Object>> chain(
            Map<Long, Map<String, Object>> records, long id, String parentField) {
        var result = new ArrayList<Map<String, Object>>();
        var seen = new HashSet<Long>();
        if (id <= 0) throw new BusinessException(INTEGRITY_FAILURE);
        while (id != 0) {
            var row = records.get(id);
            if ((row == null || row.isEmpty()) || !seen.add(id)) throw new BusinessException(INTEGRITY_FAILURE);
            result.add(row);
            id = number(row, parentField);
        }
        return result;
    }

    static long number(Map<String, Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number)) throw new BusinessException(INTEGRITY_FAILURE);
        return ((Number) value).longValue();
    }

    private record View(
            Map<Long, Map<String, Object>> menus,
            Set<Long> granted,
            Set<Long> apiIds,
            List<Map<String, Object>> display,
            String reason) {}

    private record Outcome(Map<String, Object> result, BusinessException fault) {}
}
