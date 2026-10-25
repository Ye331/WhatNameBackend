package com.whatname.iam.service;

import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.DomainRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class QueryService {
  private final DomainRepository db;
  private final AccessService access;
  private final AccountService accounts;
  private final AuditService audit;

  public QueryService(
      DomainRepository db, AccessService access, AccountService accounts, AuditService audit) {
    this.db = db;
    this.access = access;
    this.accounts = accounts;
    this.audit = audit;
  }

  public Map<String, Object> menus(String bearer, String appCode) {
    var actor = access.current(bearer);
    var app = access.application(appCode);
    var granted = access.menus(actor, app.id);
    var display = new LinkedHashMap<Long, Menu>();
    for (var menu : granted) {
      display.put(menu.id, menu);
      while (menu.parentId != 0) {
        menu = db.get(Menu.class, menu.parentId);
        display.put(menu.id, menu);
      }
    }
    return Map.of("appCode", appCode, "menus", display.values(), "user", actor);
  }

  public Map<String, Object> check(String bearer, String appCode, String method, String route) {
    var actor = access.current(bearer);
    var app = access.application(appCode);
    boolean allowed = access.allowed(actor, app.id, method, route);
    audit.record(
        actor,
        actor,
        "AUTH_CHECK",
        "APPLICATION",
        app.id,
        method + " " + route + " " + (allowed ? "ALLOW" : "DENY"),
        allowed);
    return Map.of("allowed", allowed, "reasonCode", allowed ? "ALLOW" : "NO_PERMISSION");
  }

  public Map<String, Object> logs(String bearer, String operation, int page, int pageSize) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_AUDIT_VIEW");
    if (page < 1 || pageSize < 1 || pageSize > 100) Input.fail();
    String where = "from OperationLog l where (?1 is null or l.operationType=?1)";
    Object[] args;
    if ("ALL".equals(actor.managementScope)) args = new Object[] {operation};
    else {
      where += " and l.deptId in ?2";
      args = new Object[] {operation, accounts.visibleDepartments(actor)};
    }
    return Map.of(
        "items",
        db.page(
            OperationLog.class,
            where + " order by l.id desc",
            (page - 1) * pageSize,
            pageSize,
            args),
        "total",
        db.query(Long.class, "select count(l) " + where, args).getFirst(),
        "page",
        page,
        "pageSize",
        pageSize);
  }

  public Map<String, Object> database() {
    return Map.of(
        "connected", db.query(Long.class, "select count(u) from Account u").getFirst() >= 0);
  }
}
