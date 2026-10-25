package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.DomainRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Single RBAC0 evaluator for IAM and external applications. No administrator identity. */
@Service
@Transactional
public class AccessService {
  private final DomainRepository db;
  private final SignatureService signatures;
  private final JwtTokenService jwt;

  public AccessService(DomainRepository db, SignatureService signatures, JwtTokenService jwt) {
    this.db = db;
    this.signatures = signatures;
    this.jwt = jwt;
  }

  public Account current(String bearer) {
    var principal = jwt.verifyBearer(bearer);
    Account user;
    try {
      user = db.get(Account.class, principal.userId());
    } catch (BusinessException e) {
      throw new BusinessException(INVALID_TOKEN);
    }
    signatures.verify(user);
    if (user.status != 1 || user.sessionVersion != principal.sessionVersion())
      throw new BusinessException(INVALID_TOKEN);
    return user;
  }

  public Application application(String code) {
    var app =
        db.query(Application.class, "from Application where appCode=?1", code).stream()
            .findFirst()
            .orElseThrow(() -> new BusinessException(RESOURCE_NOT_FOUND));
    signatures.verify(app);
    if (app.status != 1) throw new BusinessException(PERMISSION_DENIED);
    return app;
  }

  public List<Department> departmentChain(long deptId) {
    var chain = new ArrayList<Department>();
    var visited = new HashSet<Long>();
    while (deptId != 0) {
      if (!visited.add(deptId)) throw new BusinessException(INTEGRITY_FAILURE);
      var dept = db.get(Department.class, deptId);
      signatures.verify(dept);
      chain.add(dept);
      deptId = dept.parentId;
    }
    return chain;
  }

  public boolean within(Account actor, long deptId) {
    signatures.verify(actor);
    var chain = departmentChain(deptId);
    return "ALL".equals(actor.managementScope)
        || chain.stream().anyMatch(d -> d.id.equals(actor.deptId));
  }

  public void scope(Account actor, Account target) {
    signatures.verify(target);
    if (!within(actor, target.deptId)) throw new BusinessException(OUT_OF_SCOPE);
  }

  public void scope(Account actor, Department target) {
    if (!within(actor, target.id)) throw new BusinessException(OUT_OF_SCOPE);
  }

  public List<Menu> menus(Account user, long appId) {
    signatures.verify(user);
    var app = db.get(Application.class, appId);
    signatures.verify(app);
    if (user.status != 1
        || app.status != 1
        || departmentChain(user.deptId).stream()
            .anyMatch(d -> d.status != 1 || d.mergedIntoDeptId != null)) return List.of();
    var ids = new TreeSet<Long>();
    for (long roleId : db.roles(user.id)) {
      var role = db.get(Role.class, roleId);
      signatures.verify(role);
      if (role.status == 1) ids.addAll(db.menus(role.id));
    }
    var result = new ArrayList<Menu>();
    for (long id : ids) {
      var menu = db.get(Menu.class, id);
      signatures.verify(menu);
      if (menu.appId != appId || menu.menuType != 2 || menu.status != 1) continue;
      var parent = menu;
      var visited = new HashSet<Long>();
      boolean active = true;
      while (parent.parentId != 0) {
        if (!visited.add(parent.id)) throw new BusinessException(INTEGRITY_FAILURE);
        parent = db.get(Menu.class, parent.parentId);
        signatures.verify(parent);
        if (!parent.appId.equals(menu.appId)) throw new BusinessException(INTEGRITY_FAILURE);
        if (parent.status != 1) active = false;
      }
      if (active) result.add(menu);
    }
    return result;
  }

  public void function(Account actor, String code) {
    if (menus(actor, application("IAM").id).stream().noneMatch(m -> m.menuCode.equals(code)))
      throw new BusinessException(PERMISSION_DENIED);
  }

  public boolean allowed(Account actor, long appId, String method, String route) {
    String canonical =
        RouteTemplateUtils.canonicalTemplate(RouteTemplateUtils.normalizeTemplate(route));
    var candidates =
        db.query(
            Api.class,
            "from Api where appId=?1 and httpMethod=?2 and canonicalPath=?3",
            appId,
            method,
            canonical);
    if (candidates.size() != 1) return false;
    var api = candidates.getFirst();
    signatures.verify(api);
    return api.status == 1
        && menus(actor, appId).stream().anyMatch(m -> db.apis(m.id).contains(api.id));
  }

  public void requireApi(Account actor, String method, String route) {
    if (!allowed(actor, application("IAM").id, method, route))
      throw new BusinessException(PERMISSION_DENIED);
  }
}
