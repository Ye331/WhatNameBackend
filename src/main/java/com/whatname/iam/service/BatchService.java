package com.whatname.iam.service;

import com.whatname.iam.common.exception.BusinessException;
import java.util.*;
import org.springframework.stereotype.Service;

/** No outer transaction: each call through GrantService's proxy commits independently. */
@Service
public class BatchService {
  private final GrantService grants;

  public BatchService(GrantService grants) {
    this.grants = grants;
  }

  public Map<String, Object> execute(String bearer, Map<String, Object> input) {
    var users = Input.ids(input, "userIds", 100);
    if (users.isEmpty()) Input.fail();
    long role = Input.id(input, "roleId");
    String operation = Input.text(input, "operation", 16);
    if (!Set.of("GRANT", "REVOKE").contains(operation)) Input.fail();
    var results = new ArrayList<Map<String, Object>>();
    for (long user : users) {
      try {
        grants.change(bearer, user, role, "GRANT".equals(operation));
        results.add(Map.of("userId", user, "success", true));
      } catch (BusinessException e) {
        if (e.errorCode().status().is5xxServerError() || e.errorCode().status().value() == 401)
          throw e;
        results.add(Map.of("userId", user, "success", false, "message", e.getMessage()));
      }
    }
    return Map.of(
        "items",
        results,
        "succeeded",
        results.stream().filter(r -> Boolean.TRUE.equals(r.get("success"))).count(),
        "total",
        results.size());
  }
}
