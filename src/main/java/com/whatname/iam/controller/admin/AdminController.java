package com.whatname.iam.controller.admin;

import com.whatname.iam.service.admin.AdminService;
import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.admin.AdminDtos.*;
import com.whatname.iam.dto.common.PageRequest;
import com.whatname.iam.dto.common.PageResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admins")
public class AdminController {
    private final AdminService service;
    public AdminController(AdminService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResponse<Summary>> list(@RequestParam(required = false) String deptId,
                                                  @Valid @ModelAttribute PageRequest page) {
        return ApiResponse.ok(service.list(deptId, page.getPage(), page.getPageSize()));
    }

    @PostMapping("/appointments")
    public ApiResponse<Summary> appoint(@Valid @RequestBody AppointmentRequest request) {
        return ApiResponse.ok(service.appoint(request));
    }

    @PostMapping("/{userId}/revoke")
    public ApiResponse<Revocation> revoke(@PathVariable String userId, @Valid @RequestBody RevokeRequest request) {
        return ApiResponse.ok(service.revoke(userId, request));
    }

    @GetMapping("/me/scope")
    public ApiResponse<Scope> myScope() { return ApiResponse.ok(service.myScope()); }

    @GetMapping("/{userId}/delegable-roles")
    public ApiResponse<DelegableRoles> delegableRoles(@PathVariable String userId) {
        return ApiResponse.ok(service.delegableRoles(userId));
    }

    @PutMapping("/{userId}/delegable-roles")
    public ApiResponse<DelegableRoles> replace(@PathVariable String userId,
                                              @Valid @RequestBody DelegationRequest request) {
        return ApiResponse.ok(service.replaceDelegableRoles(userId, request));
    }
}
