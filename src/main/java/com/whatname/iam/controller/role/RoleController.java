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
    public ApiResponse<PageResponse<RoleItem>> listRoles(@Valid PageRequest pageRequest) {
        return ApiResponse.ok(service.listRoles(pageRequest));
    }

    @PostMapping
    public ApiResponse<CreateRoleResult> createRole(@Valid @RequestBody CreateRoleRequest request) {
        return ApiResponse.ok(service.createRole(request));
    }

    @GetMapping("/{roleId}")
    public ApiResponse<RoleItem> getRole(@PathVariable long roleId) {
        return ApiResponse.ok(service.getRole(roleId));
    }

    @PatchMapping("/{roleId}")
    public ApiResponse<UpdateRoleResult> updateRole(@PathVariable long roleId,
                                                    @Valid @RequestBody UpdateRoleRequest request) {
        return ApiResponse.ok(service.updateRole(roleId, request));
    }

    @GetMapping("/{roleId}/menus")
    public ApiResponse<RoleMenusResult> listRoleMenus(@PathVariable long roleId) {
        return ApiResponse.ok(service.listRoleMenus(roleId));
    }

    @PutMapping("/{roleId}/menus")
    public ApiResponse<RoleMenusResult> replaceRoleMenus(@PathVariable long roleId,
                                                         @Valid @RequestBody ReplaceRoleMenusRequest request) {
        return ApiResponse.ok(service.replaceRoleMenus(roleId, request));
    }
}
