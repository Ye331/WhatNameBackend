package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.mapper.permission.*;
import com.whatname.iam.service.audit.AuditService;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

@Service
public class PermissionService {
    private final SignatureMapper facts;
    private final PermissionMapper mapper;
    private final DataSignatureService signatures;
    private final JwtTokenService jwt;
    private final AuditService audit;
    private final String managementApplication;
    private final TransactionTemplate decisions;
    public PermissionService(SignatureMapper facts, PermissionMapper mapper, DataSignatureService signatures,
                             JwtTokenService jwt, AuditService audit,
                             @Value("${iam.permission.management-app-code:IAM}") String managementApplication,
                             PlatformTransactionManager transactions) {
        this.facts = facts; this.mapper = mapper; this.signatures = signatures;
        this.jwt = jwt; this.audit = audit; this.managementApplication = managementApplication;
        decisions = new TransactionTemplate(transactions);
        decisions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        decisions.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Map<String,Object> ownMenus(String authorization, String appCode) {
        long userId = currentUser(authorization);
        return Map.of("appCode", appCode, "menus", calculate(userId, application(appCode)).display());
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long currentUser(String authorization) {
        var principal = jwt.verifyBearer(authorization);
        var user = facts.record(USER_IDENTITY, principal.userId());
        if (user == null || number(user, "status") != 1 || number(user, "password_initialized") != 1
                || number(user, "session_version") != principal.sessionVersion() || mapper.invalidated(principal.jti()) != 0)
            throw new BusinessException(INVALID_TOKEN);
        signatures.verify(USER_IDENTITY, principal.userId());
        return principal.userId();
    }

    /** First-stage read primitive. Write callers must add C's ordered lock/recheck protocol in stage two. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void requireFunction(long userId, String permission) {
        var view = calculate(userId, application(managementApplication));
        if (view.granted().stream().map(view.menus()::get).noneMatch(menu -> permission.equals(menu.get("menu_code"))))
            throw new BusinessException(PERMISSION_DENIED);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String,Object> userMenus(long userId, String appCode) {
        return Map.of("userId", Long.toString(userId), "appCode", appCode,
                "menus", calculate(userId, application(appCode)).display());
    }

    public Map<String,Object> check(String authorization, long applicationId, String method, String route) {
        var outcome = Objects.requireNonNull(decisions.execute(status -> decide(authorization, applicationId, method, route)));
        // Denial evidence has committed before a token/integrity fault reaches the HTTP handler.
        if (outcome.fault() != null) throw outcome.fault();
        return outcome.result();
    }

    private Outcome decide(String authorization, long applicationId, String method, String route) {
        if (method == null || !Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS").contains(method))
            throw new BusinessException(INVALID_ARGUMENT);
        String canonical = RouteTemplateUtils.canonicalTemplate(RouteTemplateUtils.normalizeTemplate(route));
        String decisionId = UUID.randomUUID().toString();
        Long userId = null, apiId = null;
        var evidence = new LinkedHashMap<String,Object>();
        String reason = "NO_PERMISSION";
        boolean allowed = false;
        BusinessException fault = null;
        try {
            userId = currentUser(authorization);
            var view = calculate(userId, applicationId);
            evidence.putAll(view.evidence());
            var candidates = mapper.apis(applicationId).stream().filter(api -> method.equals(api.get("http_method"))
                    && canonical.equals(RouteTemplateUtils.canonicalTemplate(RouteTemplateUtils.normalizeTemplate((String) api.get("api_path"))))).toList();
            if (candidates.size() != 1) reason = candidates.isEmpty() ? "UNKNOWN_API" : "AMBIGUOUS_API";
            else {
                var api = candidates.getFirst(); apiId = number(api, "id");
                view.signatureEvidence().add(signatures.verify(RESOURCE_API, apiId));
                evidence.put("api", api);
                if (number(api, "status") != 1) reason = "API_DISABLED";
                else {
                    allowed = view.apiIds().contains(apiId);
                    reason = allowed ? "ALLOW" : view.reason();
                }
            }
        } catch (BusinessException error) {
            fault = error; reason = error.errorCode().name(); allowed = false;
        }
        evidence.put("method", method); evidence.put("routeTemplate", route);
        String version = CanonicalEvidence.digest(evidence);
        evidence.put("decisionId", decisionId);
        evidence.put("permissionVersion", version);
        audit.decision(userId, applicationId, apiId, allowed, reason, method, route, evidence);
        return new Outcome(Map.of("allowed", allowed, "reasonCode", reason, "decisionId", decisionId, "permissionVersion", version), fault);
    }

    private long application(String appCode) {
        if (appCode == null || !appCode.matches("[A-Za-z0-9_-]{1,64}")) throw new BusinessException(INVALID_ARGUMENT);
        Long id = mapper.applicationId(appCode);
        if (id == null) throw new BusinessException(RESOURCE_NOT_FOUND);
        return id;
    }

    private View calculate(long userId, long applicationId) {
        var signatureEvidence = new ArrayList<Map<String,Object>>();
        var evidence = new LinkedHashMap<String,Object>();
        evidence.put("signatures", signatureEvidence);
        var user = facts.record(USER_IDENTITY, userId);
        if (user == null) throw new BusinessException(RESOURCE_NOT_FOUND);
        signatureEvidence.add(signatures.verify(USER_IDENTITY, userId));
        var app = facts.record(RESOURCE_APPLICATION, applicationId);
        if (app == null) throw new BusinessException(INVALID_SERVICE);
        signatureEvidence.add(signatures.verify(RESOURCE_APPLICATION, applicationId));
        signatureEvidence.add(signatures.verify(ORGANIZATION, 0));
        var departments = new HashMap<Long,Map<String,Object>>();
        facts.organization().forEach(row -> departments.put(number(row, "id"), row));
        var ancestors = chain(departments, number(user, "dept_id"), "parent_id");
        boolean frozen = ancestors.stream().anyMatch(row -> number(row, "status") != 1 || row.get("merged_into_dept_id") != null);
        evidence.put("state", Map.of("userId", Long.toString(userId), "status", number(user, "status"),
                "sessionVersion", number(user, "session_version"), "departments", ancestors, "application", app));
        evidence.put("user_role", Map.of("roles", List.of(), "sources", List.of()));
        evidence.put("role_permission", new ArrayList<>());
        evidence.put("menu_api", new ArrayList<>());
        var menus = new LinkedHashMap<Long,Map<String,Object>>();
        mapper.menus(applicationId).forEach(menu -> menus.put(number(menu, "id"), menu));
        var granted = new LinkedHashSet<Long>();
        if (number(user, "status") != 1 || number(user, "password_initialized") != 1 || number(app, "status") != 1 || frozen)
            return new View(menus, granted, Set.of(), List.of(), evidence, signatureEvidence, frozen ? "DEPARTMENT_FROZEN" : "STATE_DISABLED");
        signatureEvidence.add(signatures.verify(USER_GRANTS, userId));
        var roles = facts.userRoles(userId);
        var sources = facts.sources(userId);
        var sourcedRoles = new TreeSet<Long>();
        for (var source : sources) {
            if (!Set.of("MANUAL", "DEPT_POSITION", "OA_APPROVAL").contains(source.get("source_type"))
                    || source.get("source_ref") == null || source.get("source_ref").toString().isBlank()
                    || ("DEPT_POSITION".equals(source.get("source_type")) && source.get("source_dept_id") == null))
                throw new BusinessException(INTEGRITY_FAILURE);
            sourcedRoles.add(number(source, "role_id"));
        }
        if (!new TreeSet<>(roles).equals(sourcedRoles)) throw new BusinessException(INTEGRITY_FAILURE);
        evidence.put("user_role", Map.of("roles", roles, "sources", sources));
        var roleEvidence = new ArrayList<Map<String,Object>>();
        var mappingEvidence = new ArrayList<Map<String,Object>>();
        var verifiedMenus = new HashSet<Long>();
        for (long roleId : roles) {
            signatureEvidence.add(signatures.verify(ROLE_MENUS, roleId));
            var role = facts.record(ROLE_MENUS, roleId);
            var menuIds = facts.roleMenus(roleId);
            roleEvidence.add(Map.of("role", role, "menus", menuIds));
            if (number(role, "status") != 1) continue;
            for (long menuId : menuIds) {
                var menu = menus.get(menuId);
                if (menu == null) menu = facts.record(RESOURCE_MENU, menuId);
                if (menu == null) throw new BusinessException(INTEGRITY_FAILURE);
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
        signatureEvidence.addAll(signatures.verifyMany(RESOURCE_MENU, verifiedMenus).values());
        signatureEvidence.addAll(signatures.verifyMany(MENU_APIS, granted).values());
        var apiIds = new HashSet<Long>();
        var mappings = new LinkedHashMap<Long,List<Long>>();
        granted.forEach(id -> mappings.put(id, new ArrayList<>()));
        if (!granted.isEmpty()) facts.menuApiMembers(new ArrayList<>(granted)).forEach(row -> {
            long apiId = number(row, "api_id"); apiIds.add(apiId);
            mappings.get(number(row, "menu_id")).add(apiId);
        });
        mappings.forEach((id, apis) -> mappingEvidence.add(Map.of("menuId", Long.toString(id), "apis", apis)));
        evidence.put("role_permission", roleEvidence); evidence.put("menu_api", mappingEvidence);
        var displayed = new HashSet<Long>();
        for (long id : granted) chain(menus, id, "parent_id").forEach(menu -> displayed.add(number(menu, "id")));
        var display = menus.values().stream().filter(menu -> displayed.contains(number(menu, "id")))
                .map(menu -> menuView(menu, granted.contains(number(menu, "id")))).toList();
        evidence.put("menus", menus.values().stream().filter(menu -> displayed.contains(number(menu, "id"))).toList());
        return new View(menus, granted, apiIds, display, evidence, signatureEvidence, "NO_PERMISSION");
    }

    private Map<String,Object> menuView(Map<String,Object> menu, boolean granted) {
        var result = new LinkedHashMap<String,Object>();
        result.put("menuId", Long.toString(number(menu, "id"))); result.put("parentId", Long.toString(number(menu, "parent_id")));
        result.put("menuCode", menu.get("menu_code")); result.put("menuName", menu.get("menu_name"));
        result.put("menuType", number(menu, "menu_type")); result.put("path", menu.get("path"));
        result.put("navigationOnly", !granted);
        return result;
    }
    private List<Map<String,Object>> chain(Map<Long,Map<String,Object>> records, long id, String parentField) {
        var result = new ArrayList<Map<String,Object>>(); var seen = new HashSet<Long>();
        if (id <= 0) throw new BusinessException(INTEGRITY_FAILURE);
        while (id != 0) {
            var row = records.get(id);
            if (row == null || !seen.add(id)) throw new BusinessException(INTEGRITY_FAILURE);
            result.add(row); id = number(row, parentField);
        }
        return result;
    }
    static long number(Map<String,Object> row, String field) {
        Object value = row.get(field);
        if (!(value instanceof Number)) throw new BusinessException(INTEGRITY_FAILURE);
        return ((Number) value).longValue();
    }
    private record View(Map<Long,Map<String,Object>> menus, Set<Long> granted, Set<Long> apiIds, List<Map<String,Object>> display,
                        Map<String,Object> evidence, List<Map<String,Object>> signatureEvidence, String reason) { }
    private record Outcome(Map<String,Object> result, BusinessException fault) { }
}
