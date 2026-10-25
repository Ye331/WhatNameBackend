package com.whatname.iam.controller;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

/** Thin HTTP adapter. Business rules live in Services, database access in Repositories. */
@RestController
@RequestMapping("/api/v1")
public class ManagementController {
  private final AccountService users;
  private final ResourceService resources;
  private final GrantService grants;
  private final BatchService batches;
  private final OrganizationService organization;
  private final QueryService query;

  public ManagementController(
      AccountService users,
      ResourceService resources,
      GrantService grants,
      BatchService batches,
      OrganizationService organization,
      QueryService query) {
    this.users = users;
    this.resources = resources;
    this.grants = grants;
    this.batches = batches;
    this.organization = organization;
    this.query = query;
  }

  @GetMapping("/users")
  public ApiResponse<?> users(
      @RequestHeader("Authorization") String b,
      @RequestParam(required = false) String keyword,
      @RequestParam(required = false) Long deptId,
      @RequestParam(required = false) Integer status,
      @RequestParam(required = false) Integer source,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.ok(users.users(b, keyword, deptId, status, source, page, pageSize));
  }

  @GetMapping("/users/{id}")
  public ApiResponse<?> user(@RequestHeader("Authorization") String b, @PathVariable long id) {
    return ApiResponse.ok(users.detail(b, id));
  }

  @PostMapping("/users/temporary")
  public ApiResponse<?> createUser(
      @RequestHeader("Authorization") String b, @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(users.temporary(b, in));
  }

  @PatchMapping("/users/{id}")
  public ApiResponse<?> editUser(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(users.updateTemporary(b, id, in));
  }

  @PatchMapping("/users/{id}/status")
  public ApiResponse<?> status(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    users.status(b, id, in);
    return ok();
  }

  @PutMapping("/users/{id}/scope")
  public ApiResponse<?> scope(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    users.scope(b, id, in);
    return ok();
  }

  @PutMapping("/users/{id}/password")
  public ApiResponse<?> password(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    users.resetPassword(b, id, in);
    return ok();
  }

  @GetMapping("/users/{id}/roles")
  public ApiResponse<?> userRoles(@RequestHeader("Authorization") String b, @PathVariable long id) {
    return ApiResponse.ok(grants.roles(b, id));
  }

  @PutMapping("/users/{id}/roles")
  public ApiResponse<?> userRoles(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    grants.replace(b, id, Input.ids(in, "roleIds", 500));
    return ok();
  }

  @PostMapping("/grants/batch")
  public ApiResponse<?> batch(
      @RequestHeader("Authorization") String b, @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(batches.execute(b, in));
  }

  @GetMapping("/roles")
  public ApiResponse<?> roles(@RequestHeader("Authorization") String b) {
    return ApiResponse.ok(resources.roles(b));
  }

  @PostMapping("/roles")
  public ApiResponse<?> createRole(
      @RequestHeader("Authorization") String b, @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.role(b, null, in));
  }

  @PatchMapping("/roles/{id}")
  public ApiResponse<?> editRole(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.role(b, id, in));
  }

  @GetMapping("/roles/{id}/menus")
  public ApiResponse<?> roleMenus(@RequestHeader("Authorization") String b, @PathVariable long id) {
    return ApiResponse.ok(resources.roleMenus(b, id));
  }

  @PutMapping("/roles/{id}/menus")
  public ApiResponse<?> replaceMenus(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    resources.replaceMenus(b, id, Input.ids(in, "menuIds", 10000));
    return ok();
  }

  @GetMapping("/applications")
  public ApiResponse<?> apps(@RequestHeader("Authorization") String b) {
    return ApiResponse.ok(resources.applications(b));
  }

  @PostMapping("/applications")
  public ApiResponse<?> createApp(
      @RequestHeader("Authorization") String b, @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.application(b, null, in));
  }

  @PatchMapping("/applications/{id}")
  public ApiResponse<?> editApp(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.application(b, id, in));
  }

  @GetMapping("/applications/{app}/menus")
  public ApiResponse<?> menus(@RequestHeader("Authorization") String b, @PathVariable long app) {
    return ApiResponse.ok(resources.menus(b, app));
  }

  @PostMapping("/applications/{app}/menus")
  public ApiResponse<?> createMenu(
      @RequestHeader("Authorization") String b,
      @PathVariable long app,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.menu(b, app, null, in));
  }

  @PatchMapping("/applications/{app}/menus/{id}")
  public ApiResponse<?> editMenu(
      @RequestHeader("Authorization") String b,
      @PathVariable long app,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.menu(b, app, id, in));
  }

  @GetMapping("/applications/{app}/apis")
  public ApiResponse<?> apis(@RequestHeader("Authorization") String b, @PathVariable long app) {
    return ApiResponse.ok(resources.apis(b, app));
  }

  @PostMapping("/applications/{app}/apis")
  public ApiResponse<?> createApi(
      @RequestHeader("Authorization") String b,
      @PathVariable long app,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.api(b, app, null, in));
  }

  @PatchMapping("/applications/{app}/apis/{id}")
  public ApiResponse<?> editApi(
      @RequestHeader("Authorization") String b,
      @PathVariable long app,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    return ApiResponse.ok(resources.api(b, app, id, in));
  }

  @GetMapping("/menus/{id}/apis")
  public ApiResponse<?> menuApis(@RequestHeader("Authorization") String b, @PathVariable long id) {
    return ApiResponse.ok(resources.menuApis(b, id));
  }

  @PutMapping("/menus/{id}/apis")
  public ApiResponse<?> replaceApis(
      @RequestHeader("Authorization") String b,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    resources.replaceApis(b, id, Input.ids(in, "apiIds", 10000));
    return ok();
  }

  @GetMapping("/departments")
  public ApiResponse<?> departments(@RequestHeader("Authorization") String b) {
    return ApiResponse.ok(organization.departments(b));
  }

  @PostMapping("/departments/{id}/freeze")
  public ApiResponse<?> freeze(@RequestHeader("Authorization") String b, @PathVariable long id) {
    organization.freeze(b, id, true);
    return ok();
  }

  @PostMapping("/departments/{id}/unfreeze")
  public ApiResponse<?> unfreeze(@RequestHeader("Authorization") String b, @PathVariable long id) {
    organization.freeze(b, id, false);
    return ok();
  }

  @GetMapping("/audit/operations")
  public ApiResponse<?> logs(
      @RequestHeader("Authorization") String b,
      @RequestParam(required = false) String operation,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int pageSize) {
    return ApiResponse.ok(query.logs(b, operation, page, pageSize));
  }

  private ApiResponse<?> ok() {
    return ApiResponse.ok(Map.of());
  }
}
