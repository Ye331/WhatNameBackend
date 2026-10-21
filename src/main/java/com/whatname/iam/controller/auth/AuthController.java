package com.whatname.iam.controller.auth;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.security.PublicRequestLimiter;
import com.whatname.iam.dto.auth.AuthRequests.*;
import com.whatname.iam.service.auth.AuthService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    private final PublicRequestLimiter limiter;

    public AuthController(AuthService auth, PublicRequestLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping("/login")
    public ApiResponse<AuthService.LoginResult> login(
            @Valid @RequestBody Login input, HttpServletRequest request) {
        limiter.check("login", request.getRemoteAddr(), 60);
        return ApiResponse.ok(
                auth.login(input, request.getRemoteAddr(), request.getHeader("User-Agent")));
    }

    @PostMapping("/sms/send")
    public ApiResponse<AuthService.SmsResult> sms(
            @Valid @RequestBody SendSms input, HttpServletRequest request) {
        limiter.check("sms", request.getRemoteAddr(), 10);
        return ApiResponse.ok(auth.sendSms(input));
    }

    @PostMapping("/password/initialize")
    public ApiResponse<Map<String, Boolean>> initialize(
            @Valid @RequestBody Password input, HttpServletRequest request) {
        limiter.check("password", request.getRemoteAddr(), 30);
        auth.changePassword(input, true);
        return ApiResponse.ok(Map.of("initialized", true));
    }

    @PostMapping("/password/reset")
    public ApiResponse<Map<String, Boolean>> reset(
            @Valid @RequestBody Password input, HttpServletRequest request) {
        limiter.check("password", request.getRemoteAddr(), 30);
        auth.changePassword(input, false);
        return ApiResponse.ok(Map.of("passwordReset", true, "sessionsInvalidated", true));
    }

    @PostMapping("/logout")
    public ApiResponse<Map<String, Boolean>> logout(
            @RequestHeader(value = "Authorization", required = false) String bearer) {
        auth.logout(bearer);
        return ApiResponse.ok(Map.of("revoked", true));
    }
}
