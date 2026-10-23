package com.whatname.iam.service.role;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
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
import com.whatname.iam.entity.resource.MenuEntity;
import com.whatname.iam.entity.role.RoleEntity;
import com.whatname.iam.repository.role.RoleRepository;
import com.whatname.iam.service.admin.ManagementAccessService;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.SignedDataType;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class RoleService {
    private final RoleRepository repository;
    private final ManagementAccessService access;
    private final DataSignatureService signatures;
    private final AuditService audit;

    public RoleService(
            RoleRepository repository,
            ManagementAccessService access,
            DataSignatureService signatures,
            AuditService audit) {
        this.repository = repository;
        this.access = access;
        this.signatures = signatures;
        this.audit = audit;
    }

    public PageResponse<RoleItem> listRoles(String bearer, PageRequest pageRequest) {
        access.requireRead(bearer, "IAM_ROLES_VIEW");
        int total = repository.countRoles();
        List<RoleItem> items =
                repository.listRoles(pageRequest.offset(), pageRequest.getPageSize()).stream()
                        .map(
                                role ->
                                        new RoleItem(
                                                role.getId(),
                                                role.getRoleCode(),
                                                role.getRoleName(),
                                                role.getRoleType(),
                                                role.getStatus()))
                        .toList();
        return new PageResponse<>(items, pageRequest.getPage(), pageRequest.getPageSize(), total);
    }

    @Transactional
    public CreateRoleResult createRole(String bearer, CreateRoleRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_ROLES_WRITE");
        int roleType = requireRoleType(request.getRoleType());
        if (roleType == 3) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        String roleCode = request.getRoleCode().trim().toUpperCase(Locale.ROOT);
        if (repository.countRoleCode(roleCode, null) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        RoleEntity entity = new RoleEntity();
        entity.setRoleCode(roleCode);
        entity.setRoleName(request.getRoleName().trim());
        entity.setRoleDesc(request.getDescription().trim());
        entity.setRoleType(roleType);
        entity.setMenuRevision(1);
        entity.setStatus(1);
        repository.insertRole(entity);
        signatures.sign(SignedDataType.ROLE_MENUS, entity.getId());
        audit.resourceChange(
                "ROLE",
                "ROLE_CREATE",
                actorId,
                entity.getId(),
                request.getDescription(),
                null,
                signatures.snapshot(SignedDataType.ROLE_MENUS, entity.getId()),
                AuditService.details(
                        "roleCode",
                        entity.getRoleCode(),
                        "roleName",
                        entity.getRoleName(),
                        "roleType",
                        entity.getRoleType(),
                        "status",
                        entity.getStatus()));
        return new CreateRoleResult(entity.getId(), entity.getRoleType(), entity.getStatus());
    }

    public RoleItem getRole(String bearer, long roleId) {
        access.requireRead(bearer, "IAM_ROLES_VIEW");
        RoleEntity role = requireRole(roleId);
        return new RoleItem(
                role.getId(),
                role.getRoleCode(),
                role.getRoleName(),
                role.getRoleType(),
                role.getStatus());
    }

    @Transactional
    public UpdateRoleResult updateRole(String bearer, long roleId, UpdateRoleRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_ROLES_WRITE");
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
        repository.updateRole(update);
        signatures.sign(SignedDataType.ROLE_MENUS, roleId);
        Map<String, Object> after = signatures.snapshot(SignedDataType.ROLE_MENUS, roleId);
        audit.resourceChange(
                "ROLE",
                "ROLE_UPDATE",
                actorId,
                roleId,
                request.getReason(),
                before,
                after,
                AuditService.details("roleName", request.getRoleName(), "status", status));
        RoleEntity latest = requireRole(roleId);
        return new UpdateRoleResult(latest.getId(), latest.getStatus());
    }

    public RoleMenusResult listRoleMenus(String bearer, long roleId) {
        access.requireRead(bearer, "IAM_ROLES_VIEW");
        RoleEntity role = requireRole(roleId);
        List<Long> menuIds = repository.listMenuIdsByRoleId(roleId);
        return new RoleMenusResult(
                roleId, menuIds, RevisionUtils.toVersion(role.getMenuRevision()));
    }

    @Transactional
    public RoleMenusResult replaceRoleMenus(
            String bearer, long roleId, ReplaceRoleMenusRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_ROLES_WRITE");
        RoleEntity role = requireRole(roleId);
        signatures.verify(SignedDataType.ROLE_MENUS, roleId);
        if (role.getRoleType() == 3) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        int expectedRevision = RevisionUtils.parseExpectedVersion(request.getExpectedVersion());
        if (role.getMenuRevision() != expectedRevision) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        List<Long> dedupMenuIds =
                com.whatname.iam.common.util.IdUtils.distinct(request.getMenuIds());
        if (!dedupMenuIds.isEmpty()) {
            List<MenuEntity> menus = repository.listMenusByIds(dedupMenuIds);
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
        repository.deleteRoleMenus(roleId);
        if (!dedupMenuIds.isEmpty()) {
            repository.insertRoleMenus(roleId, dedupMenuIds);
        }
        if (repository.bumpRoleMenuRevision(roleId, expectedRevision) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        signatures.sign(SignedDataType.ROLE_MENUS, roleId);
        Map<String, Object> after = signatures.snapshot(SignedDataType.ROLE_MENUS, roleId);
        audit.resourceChange(
                "ROLE",
                "ROLE_MENUS_REPLACE",
                actorId,
                roleId,
                request.getExpectedVersion(),
                before,
                after,
                AuditService.details(
                        "menuIds", dedupMenuIds, "expectedVersion", request.getExpectedVersion()));
        RoleEntity latest = requireRole(roleId);
        return new RoleMenusResult(
                roleId, dedupMenuIds, RevisionUtils.toVersion(latest.getMenuRevision()));
    }

    private RoleEntity requireRole(long roleId) {
        RoleEntity role = repository.findRoleById(roleId);
        if (role == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return role;
    }

    private int requireRoleType(Integer roleType) {
        if (roleType == null || (roleType != 1 && roleType != 2 && roleType != 3)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return roleType;
    }
}
