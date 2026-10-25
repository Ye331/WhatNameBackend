package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.DomainRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ResourceService {
  private final DomainRepository db;
  private final AccessService access;
  private final SignatureService signatures;
  private final AuditService audit;

  public ResourceService(
      DomainRepository db, AccessService access, SignatureService signatures, AuditService audit) {
    this.db = db;
    this.access = access;
    this.signatures = signatures;
    this.audit = audit;
  }

  private Account actor(String bearer, String permission) {
    var actor = access.current(bearer);
    access.function(actor, permission);
    return actor;
  }

  public List<Application> applications(String bearer) {
    actor(bearer, "IAM_RESOURCES_VIEW");
    var rows = db.all(Application.class);
    rows.forEach(signatures::verify);
    return rows;
  }

  public List<Menu> menus(String bearer, long app) {
    actor(bearer, "IAM_RESOURCES_VIEW");
    signatures.verify(db.get(Application.class, app));
    var rows = db.query(Menu.class, "from Menu where appId=?1 order by sortOrder,id", app);
    rows.forEach(signatures::verify);
    return rows;
  }

  public List<Api> apis(String bearer, long app) {
    actor(bearer, "IAM_RESOURCES_VIEW");
    signatures.verify(db.get(Application.class, app));
    var rows = db.query(Api.class, "from Api where appId=?1 order by id", app);
    rows.forEach(signatures::verify);
    return rows;
  }

  public Application application(String bearer, Long id, Map<String, Object> input) {
    var actor = actor(bearer, "IAM_RESOURCES_WRITE");
    var row = id == null ? new Application() : db.lock(Application.class, id);
    if (id != null) signatures.verify(row);
    if (id == null) row.appCode = Input.text(input, "appCode", 64);
    row.appName = Input.text(input, "appName", 128);
    row.appDesc = Input.optional(input, "appDesc", 512);
    row.status = Input.number(input, "status", row.status, 0, 1);
    if ("IAM".equals(row.appCode) && row.status != 1)
      throw new BusinessException(BUSINESS_RULE_VIOLATION);
    db.save(row);
    signatures.sign(row);
    audit.record(actor, null, "APPLICATION_SAVE", "APPLICATION", row.id, row.appCode, true);
    return row;
  }

  public Menu menu(String bearer, long app, Long id, Map<String, Object> input) {
    var actor = actor(bearer, "IAM_RESOURCES_WRITE");
    signatures.verify(db.get(Application.class, app));
    var row = id == null ? new Menu() : db.lock(Menu.class, id);
    if (id != null) {
      signatures.verify(row);
      if (row.appId != app) Input.fail();
    }
    row.appId = app;
    if (id == null) row.menuCode = Input.text(input, "menuCode", 64);
    row.menuName = Input.text(input, "menuName", 128);
    row.menuType = Input.number(input, "menuType", row.menuType, 1, 2);
    row.status = Input.number(input, "status", row.status, 0, 1);
    row.path = Input.optional(input, "path", 256);
    long parent =
        input.get("parentId") == null || "0".equals(input.get("parentId").toString())
            ? 0
            : Input.id(input, "parentId");
    var visited = new HashSet<Long>();
    for (long current = parent; current != 0; ) {
      if (Objects.equals(row.id, current) || !visited.add(current))
        throw new BusinessException(BUSINESS_RULE_VIOLATION);
      var p = db.get(Menu.class, current);
      signatures.verify(p);
      if (p.appId != app) Input.fail();
      current = p.parentId;
    }
    row.parentId = parent;
    if (id != null
        && row.menuType == 1
        && (!db.apis(id).isEmpty()
            || !db.query(RolePermission.class, "from RolePermission where menuId=?1", id)
                .isEmpty())) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    db.save(row);
    signatures.sign(row);
    audit.record(actor, null, "MENU_SAVE", "MENU", row.id, row.menuCode, true);
    return row;
  }

  public Api api(String bearer, long app, Long id, Map<String, Object> input) {
    var actor = actor(bearer, "IAM_RESOURCES_WRITE");
    signatures.verify(db.get(Application.class, app));
    var row = id == null ? new Api() : db.lock(Api.class, id);
    if (id != null) {
      signatures.verify(row);
      if (row.appId != app) Input.fail();
    }
    row.appId = app;
    if (id == null) row.apiCode = Input.text(input, "apiCode", 128);
    row.apiName = Input.text(input, "apiName", 128);
    row.apiPath = RouteTemplateUtils.normalizeTemplate(Input.text(input, "apiPath", 256));
    row.canonicalPath = RouteTemplateUtils.canonicalTemplate(row.apiPath);
    row.httpMethod = Input.text(input, "httpMethod", 16);
    if (!Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")
        .contains(row.httpMethod)) Input.fail();
    row.status = Input.number(input, "status", row.status, 0, 1);
    for (var other :
        db.query(Api.class, "from Api where appId=?1 and httpMethod=?2", app, row.httpMethod)) {
      if (!Objects.equals(other.id, row.id)
          && RouteTemplateUtils.overlapsTemplate(row.apiPath, other.apiPath))
        throw new BusinessException(DUPLICATE_RESOURCE);
    }
    db.save(row);
    signatures.sign(row);
    audit.record(actor, null, "API_SAVE", "API", row.id, row.httpMethod + " " + row.apiPath, true);
    return row;
  }

  public List<Long> menuApis(String bearer, long id) {
    actor(bearer, "IAM_RESOURCES_VIEW");
    signatures.verify(db.get(Menu.class, id));
    return db.apis(id);
  }

  public void replaceApis(String bearer, long id, List<Long> apiIds) {
    var actor = actor(bearer, "IAM_RESOURCES_WRITE");
    var menu = db.lock(Menu.class, id);
    signatures.verify(menu);
    if (menu.menuType != 2) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    for (long apiId : apiIds) {
      var api = db.get(Api.class, apiId);
      signatures.verify(api);
      if (!api.appId.equals(menu.appId)) Input.fail();
    }
    var desired = new TreeSet<>(apiIds);
    var before = db.apis(id);
    for (var row : db.query(MenuApi.class, "from MenuApi where menuId=?1", id))
      if (!desired.contains(row.apiId)) db.remove(row);
    for (long api : desired) if (!before.contains(api)) db.save(new MenuApi(id, api));
    signatures.sign(menu);
    audit.record(actor, null, "MENU_APIS_REPLACE", "MENU", id, before + " -> " + desired, true);
  }

  public List<Role> roles(String bearer) {
    // Grant operators need the role catalog without receiving role editing powers.
    var actor = access.current(bearer);
    var codes =
        access.menus(actor, access.application("IAM").id).stream().map(m -> m.menuCode).toList();
    if (!codes.contains("IAM_ROLES_VIEW") && !codes.contains("IAM_GRANTS_VIEW"))
      throw new BusinessException(PERMISSION_DENIED);
    var rows = db.all(Role.class);
    rows.forEach(signatures::verify);
    return rows;
  }

  public Role role(String bearer, Long id, Map<String, Object> input) {
    var actor = actor(bearer, "IAM_ROLES_WRITE");
    var row = id == null ? new Role() : db.lock(Role.class, id);
    if (id != null) signatures.verify(row);
    else row.roleCode = Input.text(input, "roleCode", 64);
    row.roleName = Input.text(input, "roleName", 128);
    row.roleDesc = Input.optional(input, "roleDesc", 512);
    row.status = Input.number(input, "status", row.status, 0, 1);
    db.save(row);
    signatures.sign(row);
    audit.record(actor, null, "ROLE_SAVE", "ROLE", row.id, row.roleCode, true);
    return row;
  }

  public List<Long> roleMenus(String bearer, long id) {
    actor(bearer, "IAM_ROLES_VIEW");
    signatures.verify(db.get(Role.class, id));
    return db.menus(id);
  }

  public void replaceMenus(String bearer, long id, List<Long> menuIds) {
    var actor = actor(bearer, "IAM_ROLE_PERMISSIONS_WRITE");
    var role = db.lock(Role.class, id);
    signatures.verify(role);
    for (long menuId : menuIds) {
      var menu = db.get(Menu.class, menuId);
      signatures.verify(menu);
      if (menu.menuType != 2) Input.fail();
    }
    var desired = new TreeSet<>(menuIds);
    var before = db.menus(id);
    for (var row : db.query(RolePermission.class, "from RolePermission where roleId=?1", id))
      if (!desired.contains(row.menuId)) db.remove(row);
    for (long menu : desired) if (!before.contains(menu)) db.save(new RolePermission(id, menu));
    signatures.sign(role);
    audit.record(actor, null, "ROLE_MENUS_REPLACE", "ROLE", id, before + " -> " + desired, true);
  }
}
