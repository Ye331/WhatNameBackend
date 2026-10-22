package com.whatname.iam.controller.role;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.common.PageRequest;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.dto.role.RoleRequests.CreateRoleRequest;
import com.whatname.iam.dto.role.RoleRequests.ReplaceRoleMenusRequest;
import com.whatname.iam.dto.role.RoleRequests.UpdateRoleRequest;
import com.whatname.iam.dto.role.RoleResponses.CreateRoleResult;
import com.whatname.iam.dto.role.RoleResponses.RoleItem;
import com.whatname.iam.dto.role.RoleResponses.RoleMenusResult;
import com.whatname.iam.dto.role.RoleResponses.UpdateRoleResult;
import com.whatname.iam.service.role.RoleService;

import jakarta.validation.Valid;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {
    private final RoleService service;

    public RoleController(RoleService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PageResponse<RoleItem>> listRoles(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid PageRequest pageRequest) {
        return ApiResponse.ok(service.listRoles(bearer, pageRequest));
    }

    @PostMapping
    public ApiResponse<CreateRoleResult> createRole(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(service.createRole(bearer, request));
    }

    @GetMapping("/{roleId}")
    public ApiResponse<RoleItem> getRole(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable long roleId) {
        return ApiResponse.ok(service.getRole(bearer, roleId));
    }

    @PatchMapping("/{roleId}")
    public ApiResponse<UpdateRoleResult> updateRole(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable long roleId,
            @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(service.updateRole(bearer, roleId, request));
    }

    @GetMapping("/{roleId}/menus")
    public ApiResponse<RoleMenusResult> listRoleMenus(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable long roleId) {
        return ApiResponse.ok(service.listRoleMenus(bearer, roleId));
    }

    @PutMapping("/{roleId}/menus")
    public ApiResponse<RoleMenusResult> replaceRoleMenus(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable long roleId,
            @Valid @RequestBody ReplaceRoleMenusRequest request) {
        return ApiResponse.ok(service.replaceRoleMenus(bearer, roleId, request));
    }
}
