package com.whatname.iam.dto.role;

import java.util.List;

public final class RoleResponses {
    private RoleResponses() {
    }

    public record RoleItem(Long roleId, String roleCode, String roleName, Integer roleType, Integer status) {
    }

    public record CreateRoleResult(Long roleId, Integer roleType, Integer status) {
    }

    public record UpdateRoleResult(Long roleId, Integer status) {
    }

    public record RoleMenusResult(Long roleId, List<Long> menuIds, String permissionRevision) {
    }
}
