package com.whatname.iam.controller.grant;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.grant.GrantService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GrantController {
    private final GrantService service;
    public GrantController(GrantService service) { this.service = service; }
    @GetMapping("/users/{userId}/roles")
    public ApiResponse<Roles> roles(@PathVariable String userId) { return ApiResponse.ok(service.roles(userId)); }
    @GetMapping("/users/{userId}/roles/{roleId}/sources")
    public ApiResponse<Sources> sources(@PathVariable String userId, @PathVariable String roleId) {
        return ApiResponse.ok(service.sources(userId, roleId));
    }
    @PostMapping("/grants/user-roles")
    public ApiResponse<Granted> grant(@RequestBody GrantRequest request) { return ApiResponse.ok(service.grant(request)); }
    @PostMapping("/grants/user-roles/revoke")
    public ApiResponse<Revoked> revoke(@RequestBody RevokeRequest request) { return ApiResponse.ok(service.revoke(request)); }
}
