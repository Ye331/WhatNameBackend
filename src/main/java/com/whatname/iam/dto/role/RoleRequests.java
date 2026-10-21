package com.whatname.iam.dto.role;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public final class RoleRequests {
    private RoleRequests() {}

    public static class CreateRoleRequest {
        @NotBlank private String roleCode;
        @NotBlank private String roleName;
        @NotNull private Integer roleType;
        @NotBlank private String description;

        public String getRoleCode() {
            return roleCode;
        }

        public void setRoleCode(String roleCode) {
            this.roleCode = roleCode;
        }

        public String getRoleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
        }

        public Integer getRoleType() {
            return roleType;
        }

        public void setRoleType(Integer roleType) {
            this.roleType = roleType;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }
    }

    public static class UpdateRoleRequest {
        private String roleName;
        private Integer status;
        @NotBlank private String reason;

        @AssertTrue
        public boolean isChangePresent() {
            return roleName != null || status != null;
        }

        public String getRoleName() {
            return roleName;
        }

        public void setRoleName(String roleName) {
            this.roleName = roleName;
        }

        public Integer getStatus() {
            return status;
        }

        public void setStatus(Integer status) {
            this.status = status;
        }

        public String getReason() {
            return reason;
        }

        public void setReason(String reason) {
            this.reason = reason;
        }
    }

    public static class ReplaceRoleMenusRequest {
        @NotNull private List<Long> menuIds;
        @NotBlank private String expectedVersion;

        public List<Long> getMenuIds() {
            return menuIds;
        }

        public void setMenuIds(List<Long> menuIds) {
            this.menuIds = menuIds;
        }

        public String getExpectedVersion() {
            return expectedVersion;
        }

        public void setExpectedVersion(String expectedVersion) {
            this.expectedVersion = expectedVersion;
        }
    }
}
