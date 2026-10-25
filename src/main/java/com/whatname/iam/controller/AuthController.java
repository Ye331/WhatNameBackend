package com.whatname.iam.controller;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.ServiceKeys;
import com.whatname.iam.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AuthController {
  private final AccountService accounts;
  private final QueryService query;
  private final ServiceKeys keys;

  public AuthController(AccountService accounts, QueryService query, ServiceKeys keys) {
    this.accounts = accounts;
    this.query = query;
    this.keys = keys;
  }

  @PostMapping("/auth/login")
  public ApiResponse<?> login(@RequestBody Map<String, Object> input) {
    var result = accounts.login(input);
    if (!Boolean.TRUE.equals(result.get("authenticated")))
      throw new BusinessException(INVALID_CREDENTIALS);
    return ApiResponse.ok(result);
  }

  @GetMapping("/auth/me")
  public ApiResponse<?> me(
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    return ApiResponse.ok(accounts.me(bearer));
  }

  @GetMapping("/auth/menus")
  public ApiResponse<?> menus(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestParam(defaultValue = "IAM") String appCode) {
    return ApiResponse.ok(query.menus(bearer, appCode));
  }

  @PostMapping("/auth/logout")
  public ApiResponse<?> logout(
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    accounts.logout(bearer);
    return ApiResponse.ok(Map.of());
  }

  @PutMapping("/auth/password")
  public ApiResponse<?> password(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestBody Map<String, Object> input) {
    accounts.ownPassword(bearer, input);
    return ApiResponse.ok(Map.of());
  }

  @PostMapping("/auth/check")
  public ApiResponse<?> check(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestHeader(value = "X-App-Code", required = false) String app,
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @RequestBody Map<String, Object> input) {
    if (app == null || !app.matches("[A-Za-z0-9_-]{1,64}"))
      throw new BusinessException(INVALID_SERVICE);
    keys.requireApplication(app, key);
    return ApiResponse.ok(
        query.check(bearer, app, Input.text(input, "method", 16), Input.text(input, "route", 256)));
  }

  @GetMapping("/demo/{appCode}/{feature}")
  public ApiResponse<?> demo(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @PathVariable String appCode,
      @PathVariable String feature) {
    var result = query.check(bearer, appCode, "GET", "/api/v1/demo/" + appCode + "/" + feature);
    if (!Boolean.TRUE.equals(result.get("allowed"))) throw new BusinessException(PERMISSION_DENIED);
    return ApiResponse.ok(Map.of("appCode", appCode, "feature", feature, "message", "业务接口鉴权通过"));
  }

  @GetMapping("/system/ping")
  public ApiResponse<?> ping() {
    return ApiResponse.ok(Map.of("status", "UP"));
  }

  @GetMapping("/system/database")
  public ApiResponse<?> database() {
    return ApiResponse.ok(query.database());
  }
}
