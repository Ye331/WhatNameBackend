package com.whatname.iam.controller.grant;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.grant.GrantService;
import com.whatname.iam.service.grant.GrantHistoryService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class GrantController {
    private final GrantService service;
    private final GrantHistoryService history;
    public GrantController(GrantService service, GrantHistoryService history) { this.service = service; this.history = history; }
    @GetMapping("/grants/history")
    public ApiResponse<HistoryPage> history(@RequestParam(required=false) String userId,
            @RequestParam(required=false) String roleId, @RequestParam(required=false) String from,
            @RequestParam(required=false) String to, @RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int pageSize) {
        return ApiResponse.ok(history.history(userId,roleId,from,to,page,pageSize));
    }
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
