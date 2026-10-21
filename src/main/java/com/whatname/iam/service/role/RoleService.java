package com.whatname.iam.service.role;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.common.util.RevisionUtils;
import com.whatname.iam.dto.common.PageRequest;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.dto.role.RoleRequests.CreateRoleRequest;
import com.whatname.iam.dto.role.RoleRequests.ReplaceRoleMenusRequest;
import com.whatname.iam.dto.role.RoleRequests.UpdateRoleRequest;
import com.whatname.iam.dto.role.RoleResponses.CreateRoleResult;
import com.whatname.iam.dto.role.RoleResponses.RoleItem;
import com.whatname.iam.dto.role.RoleResponses.RoleMenusResult;
import com.whatname.iam.dto.role.RoleResponses.UpdateRoleResult;
import com.whatname.iam.entity.role.RoleEntity;
import com.whatname.iam.entity.resource.MenuEntity;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.permission.PermissionMapper;
import com.whatname.iam.mapper.role.RoleMapper;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.admin.AdminPolicyService;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.PermissionService;
import com.whatname.iam.service.permission.SignedDataType;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class RoleService {
    private final RoleMapper mapper;
    private final PermissionService permissions;
    private final AdminPolicyService adminPolicy;
    private final DataSignatureService signatures;
    private final AuditService audit;
    private final JwtTokenService jwt;
    private final UserAccountMapper userAccounts;
    private final PermissionMapper permissionMapper;
    private final TransactionTemplate freshCheck;

    public RoleService(RoleMapper mapper, PermissionService permissions, AdminPolicyService adminPolicy,
                       DataSignatureService signatures, AuditService audit, JwtTokenService jwt,
                       UserAccountMapper userAccounts, PermissionMapper permissionMapper,
                       PlatformTransactionManager tx) {
        this.mapper = mapper;
        this.permissions = permissions;
        this.adminPolicy = adminPolicy;
        this.signatures = signatures;
        this.audit = audit;
        this.jwt = jwt;
        this.userAccounts = userAccounts;
        this.permissionMapper = permissionMapper;
        freshCheck = new TransactionTemplate(tx);
        freshCheck.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        freshCheck.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public PageResponse<RoleItem> listRoles(String bearer, PageRequest pageRequest) {
        requireCurrentView(bearer);
        int total = mapper.countRoles();
        List<RoleItem> items = mapper.listRoles(pageRequest.offset(), pageRequest.getPageSize())
                .stream()
                .map(role -> new RoleItem(role.getId(), role.getRoleCode(), role.getRoleName(), role.getRoleType(), role.getStatus()))
                .toList();
        return new PageResponse<>(items, pageRequest.getPage(), pageRequest.getPageSize(), total);
    }

    @Transactional
    public CreateRoleResult createRole(String bearer, CreateRoleRequest request) {
        long actorId = requireCurrentWrite(bearer, "IAM_ROLES_WRITE");
        int roleType = requireRoleType(request.getRoleType());
        if (roleType == 3) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        String roleCode = request.getRoleCode().trim().toUpperCase(Locale.ROOT);
        if (mapper.countRoleCode(roleCode, null) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        RoleEntity entity = new RoleEntity();
        entity.setRoleCode(roleCode);
        entity.setRoleName(request.getRoleName().trim());
        entity.setRoleDesc(request.getDescription().trim());
        entity.setRoleType(roleType);
        entity.setMenuRevision(1);
        entity.setStatus(1);
        mapper.insertRole(entity);
        signatures.sign(SignedDataType.ROLE_MENUS, entity.getId());
        audit("ROLE_CREATE", actorId, entity.getId(), request.getDescription(), null,
                signatures.snapshot(SignedDataType.ROLE_MENUS, entity.getId()),
                mapOf("roleCode", entity.getRoleCode(), "roleName", entity.getRoleName(),
                        "roleType", entity.getRoleType(), "status", entity.getStatus()));
        return new CreateRoleResult(entity.getId(), entity.getRoleType(), entity.getStatus());
    }

    public RoleItem getRole(String bearer, long roleId) {
        requireCurrentView(bearer);
        RoleEntity role = requireRole(roleId);
        return new RoleItem(role.getId(), role.getRoleCode(), role.getRoleName(), role.getRoleType(), role.getStatus());
    }

    @Transactional
    public UpdateRoleResult updateRole(String bearer, long roleId, UpdateRoleRequest request) {
        long actorId = requireCurrentWrite(bearer, "IAM_ROLES_WRITE");
        RoleEntity existing = requireRole(roleId);
        signatures.verify(SignedDataType.ROLE_MENUS, roleId);
        if (existing.getRoleType() == 3) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        Integer status = request.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }

        Map<String, Object> before = signatures.snapshot(SignedDataType.ROLE_MENUS, roleId);
        RoleEntity update = new RoleEntity();
        update.setId(roleId);
        update.setStatus(status);
        if (request.getRoleName() != null) {
            update.setRoleName(request.getRoleName().trim());
        }
        mapper.updateRole(update);
        signatures.sign(SignedDataType.ROLE_MENUS, roleId);
        Map<String, Object> after = signatures.snapshot(SignedDataType.ROLE_MENUS, roleId);
        audit("ROLE_UPDATE", actorId, roleId, request.getReason(), before, after,
                mapOf("roleName", request.getRoleName(), "status", status));
        RoleEntity latest = requireRole(roleId);
        return new UpdateRoleResult(latest.getId(), latest.getStatus());
    }

    public RoleMenusResult listRoleMenus(String bearer, long roleId) {
        requireCurrentView(bearer);
        RoleEntity role = requireRole(roleId);
        List<Long> menuIds = mapper.listMenuIdsByRoleId(roleId);
        return new RoleMenusResult(roleId, menuIds, RevisionUtils.toVersion(role.getMenuRevision()));
    }

    @Transactional
    public RoleMenusResult replaceRoleMenus(String bearer, long roleId, ReplaceRoleMenusRequest request) {
        long actorId = requireCurrentWrite(bearer, "IAM_ROLES_WRITE");
        RoleEntity role = requireRole(roleId);
        signatures.verify(SignedDataType.ROLE_MENUS, roleId);
        if (role.getRoleType() == 3) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        int expectedRevision = RevisionUtils.parseExpectedVersion(request.getExpectedVersion());
        if (role.getMenuRevision() != expectedRevision) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        List<Long> dedupMenuIds = deduplicateIds(request.getMenuIds());
        if (!dedupMenuIds.isEmpty()) {
            List<MenuEntity> menus = mapper.listMenusByIds(dedupMenuIds);
            if (menus.size() != dedupMenuIds.size()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            for (MenuEntity menu : menus) {
                if (menu.getMenuType() != 2) {
                    throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                }
                signatures.verify(SignedDataType.RESOURCE_MENU, menu.getId());
            }
        }

        Map<String, Object> before = signatures.snapshot(SignedDataType.ROLE_MENUS, roleId);
        mapper.deleteRoleMenus(roleId);
        if (!dedupMenuIds.isEmpty()) {
            mapper.insertRoleMenus(roleId, dedupMenuIds);
        }
        if (mapper.bumpRoleMenuRevision(roleId, expectedRevision) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        signatures.sign(SignedDataType.ROLE_MENUS, roleId);
        Map<String, Object> after = signatures.snapshot(SignedDataType.ROLE_MENUS, roleId);
        audit("ROLE_MENUS_REPLACE", actorId, roleId, request.getExpectedVersion(), before, after,
                mapOf("menuIds", dedupMenuIds, "expectedVersion", request.getExpectedVersion()));
        RoleEntity latest = requireRole(roleId);
        return new RoleMenusResult(roleId, dedupMenuIds, RevisionUtils.toVersion(latest.getMenuRevision()));
    }

    private RoleEntity requireRole(long roleId) {
        RoleEntity role = mapper.findRoleById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return role;
    }

    private long actorId(String bearer) {
        long actorId = jwt.verifyBearer(bearer).userId();
        if (actorId <= 0) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        return actorId;
    }

    private void requireCurrentView(String bearer) {
        long actorId = actorId(bearer);
        adminPolicy.requireAdministratorReadOnly(actorId);
        permissions.requireFunction(actorId, "IAM_ROLES_VIEW");
    }

    private long requireCurrentWrite(String bearer, String permission) {
        long actorId = actorId(bearer);
        adminPolicy.requireLevelOne(actorId);
        var principal = jwt.verifyBearer(bearer);
        if (principal.userId() != actorId) {
            throw new BusinessException(ErrorCode.INVALID_TOKEN);
        }
        recheckCurrentSessionAndFunction(principal, permission, actorId);
        return actorId;
    }

    private void recheckCurrentSessionAndFunction(JwtTokenService.SessionPrincipal principal, String permission, long actorId) {
        freshCheck.executeWithoutResult(status -> {
            UserAccount user = userAccounts.findById(actorId);
            if (user == null || user.status == null || user.status != 1
                    || user.passwordInitialized == null || user.passwordInitialized != 1
                    || user.sessionVersion == null || user.sessionVersion != principal.sessionVersion()) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }
            if (permissionMapper.invalidated(principal.jti()) != 0) {
                throw new BusinessException(ErrorCode.INVALID_TOKEN);
            }
            permissions.requireFunction(actorId, permission);
        });
    }

    private int requireRoleType(Integer roleType) {
        if (roleType == null || (roleType != 1 && roleType != 2 && roleType != 3)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return roleType;
    }

    private List<Long> deduplicateIds(List<Long> source) {
        if (source == null) {
            return List.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : source) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            set.add(id);
        }
        return new ArrayList<>(set);
    }

    private void audit(String operation, long actorId, long targetId, String reason,
                       Map<String, Object> before, Map<String, Object> after, Map<String, Object> details) {
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("reason", reason);
        if (before != null) {
            evidence.put("before", before);
        }
        if (after != null) {
            evidence.put("after", after);
        }
        if (details != null && !details.isEmpty()) {
            evidence.put("details", details);
        }
        audit.change(operation, actorId, "ROLE", targetId, null, evidence);
    }

    private Map<String, Object> mapOf(Object... items) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < items.length; i += 2) {
            map.put((String) items[i], items[i + 1]);
        }
        return map;
    }
}
