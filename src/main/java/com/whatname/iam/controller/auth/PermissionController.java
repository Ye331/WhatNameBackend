package com.whatname.iam.controller.auth;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.permission.*;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class PermissionController {
    private final PermissionService permissions;
    private final UserPermissionService users;
    private final TrustedApiCaller callers;
    private final ObjectMapper json;

    public PermissionController(
            PermissionService permissions,
            UserPermissionService users,
            TrustedApiCaller callers,
            ObjectMapper json) {
        this.permissions = permissions;
        this.users = users;
        this.callers = callers;
        this.json = json;
    }

    @GetMapping("/auth/menus")
    public ApiResponse<Map<String, Object>> menus(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestParam String appCode) {
        return ApiResponse.ok(permissions.ownMenus(bearer, appCode));
    }

    @PostMapping("/auth/check")
    public ApiResponse<Map<String, Object>> check(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            HttpServletRequest request,
            @RequestBody byte[] body) {
        long appId = callers.authenticatedApplication(request, body);
        Check input;
        try {
            input = json.readValue(body, Check.class);
        } catch (Exception failure) {
            throw new BusinessException(INVALID_ARGUMENT);
        }
        if (body.length > 4096
                || input == null
                || input.routeTemplate() == null
                || input.routeTemplate().length() > 256)
            throw new BusinessException(INVALID_ARGUMENT);
        return ApiResponse.ok(
                permissions.check(bearer, appId, input.httpMethod(), input.routeTemplate()));
    }

    @GetMapping("/users/{userId}/permissions")
    public ApiResponse<Map<String, Object>> user(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String userId,
            @RequestParam String appCode) {
        return ApiResponse.ok(users.menus(bearer, userId, appCode));
    }

    public record Check(String httpMethod, String routeTemplate) {}
}
