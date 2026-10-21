package com.whatname.iam.controller.audit;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.service.audit.AuditQueryService;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditQueryService audit;

    public AuditController(AuditQueryService audit) {
        this.audit = audit;
    }

    @GetMapping("/operations")
    public ApiResponse<Map<String, Object>> operations(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(audit.operations(bearer, false, null, page, pageSize));
    }

    @GetMapping("/authorizations")
    public ApiResponse<Map<String, Object>> authorizations(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(audit.operations(bearer, true, userId, page, pageSize));
    }

    @GetMapping("/logins")
    public ApiResponse<Map<String, Object>> logins(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestParam(required = false) String userId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(audit.logins(bearer, userId, page, pageSize));
    }

    @GetMapping("/evidence/{eventId}")
    public ApiResponse<Map<String, Object>> evidence(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String eventId) {
        return ApiResponse.ok(audit.evidence(bearer, eventId));
    }
}
