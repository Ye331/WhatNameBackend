package com.whatname.iam.controller;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.security.ServiceKeys;
import com.whatname.iam.service.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/integrations")
public class IntegrationController {
  private final ServiceKeys keys;
  private final OrganizationService hr;
  private final GrantService grants;

  public IntegrationController(ServiceKeys keys, OrganizationService hr, GrantService grants) {
    this.keys = keys;
    this.hr = hr;
    this.grants = grants;
  }

  @PostMapping("/hr/departments")
  public ApiResponse<?> department(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    keys.require("hr", key);
    return ApiResponse.ok(hr.syncDepartment(in));
  }

  @PostMapping("/hr/employees")
  public ApiResponse<?> employee(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    keys.require("hr", key);
    return ApiResponse.ok(hr.syncEmployee(in));
  }

  @PostMapping("/hr/users/{id}/transfer")
  public ApiResponse<?> transfer(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    keys.require("hr", key);
    hr.transfer(id, Input.id(in, "deptId"));
    return ok();
  }

  @PostMapping("/hr/users/{id}/terminate")
  public ApiResponse<?> terminate(
      @RequestHeader(value = "X-Service-Key", required = false) String key, @PathVariable long id) {
    keys.require("hr", key);
    hr.terminate(id);
    return ok();
  }

  @PostMapping("/hr/departments/{id}/merge")
  public ApiResponse<?> merge(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @PathVariable long id,
      @RequestBody Map<String, Object> in) {
    keys.require("hr", key);
    hr.merge(id, Input.id(in, "targetDeptId"));
    return ok();
  }

  @PostMapping("/oa/roles")
  public ApiResponse<?> oa(
      @RequestHeader(value = "X-Service-Key", required = false) String key,
      @RequestBody Map<String, Object> in) {
    keys.require("oa", key);
    String operation = Input.text(in, "operation", 16);
    if (!Set.of("GRANT", "REVOKE").contains(operation)) Input.fail();
    grants.oa(Input.id(in, "userId"), Input.id(in, "roleId"), "GRANT".equals(operation));
    return ok();
  }

  private ApiResponse<?> ok() {
    return ApiResponse.ok(Map.of());
  }
}
