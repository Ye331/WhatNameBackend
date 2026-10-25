package com.whatname.iam.service;

import com.fasterxml.jackson.databind.*;
import com.whatname.iam.common.security.PhoneNumbers;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.*;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.service.hr.HrLoginName;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Explicit empty-database import. Never executed during a normal application startup. */
@Service
public class InitializationService {
  private final DomainRepository db;
  private final AccountRepository accounts;
  private final SignatureService signatures;
  private final PasswordHasher passwords;
  private final ObjectMapper json;
  private final AuditService audit;

  public InitializationService(
      DomainRepository db,
      AccountRepository accounts,
      SignatureService signatures,
      PasswordHasher passwords,
      ObjectMapper json,
      AuditService audit) {
    this.db = db;
    this.accounts = accounts;
    this.signatures = signatures;
    this.passwords = passwords;
    this.json = json;
    this.audit = audit;
  }

  public record MenuSeed(String code, String name, String path, List<String> routes) {}

  private static MenuSeed m(String code, String name, String path, String... routes) {
    return new MenuSeed(code, name, path, List.of(routes));
  }

  public static final List<MenuSeed> IAM =
      List.of(
          m(
              "IAM_USERS_VIEW",
              "用户账号",
              "/users",
              "GET /api/v1/users",
              "GET /api/v1/users/{id}",
              "GET /api/v1/departments"),
          m(
              "IAM_USERS_WRITE",
              "临时账号维护",
              null,
              "POST /api/v1/users/temporary",
              "PATCH /api/v1/users/{id}",
              "PATCH /api/v1/users/{id}/status"),
          m("IAM_SCOPE_WRITE", "管理范围配置", null, "PUT /api/v1/users/{id}/scope"),
          m("IAM_PASSWORD_RESET", "账号密码重置", null, "PUT /api/v1/users/{id}/password"),
          m(
              "IAM_ROLES_VIEW",
              "角色管理",
              "/roles",
              "GET /api/v1/roles",
              "GET /api/v1/roles/{id}/menus"),
          m("IAM_ROLES_WRITE", "角色维护", null, "POST /api/v1/roles", "PATCH /api/v1/roles/{id}"),
          m("IAM_ROLE_PERMISSIONS_WRITE", "角色权限配置", null, "PUT /api/v1/roles/{id}/menus"),
          m(
              "IAM_RESOURCES_VIEW",
              "应用资源",
              "/resources",
              "GET /api/v1/applications",
              "GET /api/v1/applications/{app}/menus",
              "GET /api/v1/applications/{app}/apis",
              "GET /api/v1/menus/{id}/apis"),
          m(
              "IAM_RESOURCES_WRITE",
              "资源维护",
              null,
              "POST /api/v1/applications",
              "PATCH /api/v1/applications/{id}",
              "POST /api/v1/applications/{app}/menus",
              "PATCH /api/v1/applications/{app}/menus/{id}",
              "POST /api/v1/applications/{app}/apis",
              "PATCH /api/v1/applications/{app}/apis/{id}",
              "PUT /api/v1/menus/{id}/apis"),
          m(
              "IAM_GRANTS_VIEW",
              "权限分配",
              "/grants",
              "GET /api/v1/users/{id}/roles",
              "GET /api/v1/roles",
              "GET /api/v1/users",
              "GET /api/v1/departments"),
          m(
              "IAM_GRANTS_WRITE",
              "授权与回收",
              null,
              "PUT /api/v1/users/{id}/roles",
              "POST /api/v1/grants/batch"),
          m("IAM_DEPTS_VIEW", "组织管理", "/departments", "GET /api/v1/departments"),
          m(
              "IAM_DEPTS_FREEZE",
              "部门冻结",
              null,
              "POST /api/v1/departments/{id}/freeze",
              "POST /api/v1/departments/{id}/unfreeze"),
          m("IAM_AUDIT_VIEW", "操作日志", "/audit", "GET /api/v1/audit/operations"));

  @Transactional
  public Map<String, Object> initialize(
      Path file, String username, String name, String phone, String password) throws Exception {
    // Check every table, not just users: refuse partially initialized databases too.
    for (Class<? extends Row> table :
        List.of(
            Account.class,
            Department.class,
            Application.class,
            Menu.class,
            Api.class,
            Role.class,
            UserRole.class,
            RolePermission.class,
            MenuApi.class,
            DataSignature.class,
            OperationLog.class)) {
      if (!db.all(table).isEmpty()) throw new IllegalStateException("初始化只允许在新的空库执行");
    }
    Input.password(password);
    phone = PhoneNumbers.normalize(phone);
    JsonNode tables = json.readTree(file.toFile()).path("tables");
    var departments = new HashMap<String, Long>();
    int number = 1;
    for (var row : tables.path("departments").path("records")) {
      var d = new Department();
      String parent = row.path("上级部门").asText();
      if (!parent.isEmpty() && !departments.containsKey(parent))
        throw new IllegalStateException("部门父节点缺失");
      d.parentId = parent.isEmpty() ? 0 : departments.get(parent);
      d.deptCode = String.format("D%03d", number++);
      d.deptName = row.path("部门名称").asText();
      db.save(d);
      signatures.sign(d);
      departments.put(d.deptName, d.id);
    }
    if (departments.isEmpty()) throw new IllegalStateException("部门数据为空");
    // Imported employees receive an unknown password; reset selected accounts in the console.
    String unusable = passwords.hash(UUID.randomUUID().toString());
    var usernames = new HashSet<String>();
    usernames.add(username);
    int employeeCount = 0;
    for (var row : tables.path("employees").path("records")) {
      var u = new Account();
      u.realName = row.path("姓名").asText().strip();
      u.phone = PhoneNumbers.normalize(row.path("电话号码").asText());
      u.employeeNo = row.path("用户名").asText();
      String branch = row.path("分公司").asText(), dept = row.path("部门").asText();
      u.deptId = departments.getOrDefault(branch + "-" + dept, departments.get(dept));
      if (u.deptId == null) throw new IllegalStateException("员工部门未匹配");
      String base = HrLoginName.initials(u.realName), candidate = base;
      int suffix = 2;
      if (usernames.contains(candidate)) candidate = base + u.phone.substring(7);
      while (usernames.contains(candidate)) candidate = base + u.phone.substring(7) + suffix++;
      usernames.add(candidate);
      u.username = candidate;
      u.passwordHash = unusable;
      u.source = 1;
      u.employmentType = 1;
      db.save(u);
      signatures.sign(u);
      employeeCount++;
      if (employeeCount % 250 == 0) {
        db.flush();
        db.clear();
      }
    }
    long iam = application("IAM", "统一权限管理平台").id;
    for (var seed : IAM) seed(iam, seed);
    // Two executable demo applications, each with four menu/API pairs.
    for (String appCode : List.of("OA", "HR")) {
      var app = application(appCode, "OA".equals(appCode) ? "协同办公演示" : "人力资源演示");
      String[] names =
          "OA".equals(appCode)
              ? new String[] {"流程审批", "请假申请", "会议预约", "公文管理"}
              : new String[] {"考勤打卡", "休假余额", "加班管理", "员工台账"};
      for (int i = 0; i < names.length; i++)
        seed(
            app.id,
            m(
                appCode + "_DEMO_" + (i + 1),
                names[i],
                "/demo/" + appCode + "/feature" + (i + 1),
                "GET /api/v1/demo/" + appCode + "/feature" + (i + 1)));
    }
    // Import all 18 applications and all 359 source permission rows as reference resources.
    // The source matrix's operation text is preserved as reference, not inferred as live grants.
    var apps = new LinkedHashMap<String, Application>();
    var modules = new HashMap<String, Long>();
    int feature = 0;
    for (var row : tables.path("permissions").path("records")) {
      String appName = row.path("软件系统").asText(), module = row.path("功能模块").asText();
      var app = apps.get(appName);
      if (app == null) {
        app = application("SRC_" + (apps.size() + 1), appName);
        apps.put(appName, app);
      }
      String key = app.appCode + "/" + module;
      Long parent = modules.get(key);
      if (parent == null) {
        var directory = new Menu();
        directory.appId = app.id;
        directory.menuType = 1;
        directory.menuCode = "MODULE_" + (modules.size() + 1);
        directory.menuName = module;
        db.save(directory);
        signatures.sign(directory);
        parent = directory.id;
        modules.put(key, parent);
      }
      feature++;
      var menu =
          seed(
              app.id,
              m(
                  "FEATURE_" + feature,
                  row.path("功能点").asText(),
                  "/reference/" + feature,
                  "GET /reference/" + app.appCode + "/feature" + feature));
      menu.parentId = parent;
      signatures.sign(menu);
    }
    var admin = role("INITIAL_ADMIN", "平台初始管理角色");
    for (var menu : db.query(Menu.class, "from Menu where appId=?1 and menuType=2", iam))
      db.save(new RolePermission(admin.id, menu.id));
    signatures.sign(admin);
    var demo = role("DEMO_EMPLOYEE", "演示业务角色");
    for (var menu :
        db.query(
            Menu.class,
            "from Menu m where m.appId in (select a.id from Application a where a.appCode in"
                + " ('OA','HR')) and m.menuType=2")) db.save(new RolePermission(demo.id, menu.id));
    signatures.sign(demo);
    var superuser = new Account();
    superuser.username = username;
    superuser.realName = name;
    superuser.phone = phone;
    superuser.passwordHash = passwords.hash(password);
    superuser.deptId = departments.values().stream().min(Long::compare).orElseThrow();
    superuser.source = 3;
    superuser.managementScope = "ALL";
    db.save(superuser);
    db.save(new UserRole(superuser.id, admin.id));
    db.save(new UserRole(superuser.id, demo.id));
    signatures.sign(superuser);
    audit.record(superuser, superuser, "INITIALIZE", "USER", superuser.id, "全量初始化完成", true);
    db.flush();
    return Map.of(
        "departments",
        departments.size(),
        "employees",
        employeeCount,
        "sourceApplications",
        apps.size(),
        "sourcePermissions",
        feature,
        "applications",
        apps.size() + 3,
        "managementMenus",
        IAM.size(),
        "superUsername",
        username);
  }

  @Transactional
  public void recover(String username, String password) {
    Input.password(password);
    var found = accounts.findByUsername(username).orElseThrow();
    if (found.source != 3) throw new IllegalStateException("只允许恢复受控初始化账号");
    var user = db.lock(Account.class, found.id);
    signatures.verify(user);
    var role = db.query(Role.class, "from Role where roleCode='INITIAL_ADMIN'").getFirst();
    role = db.lock(Role.class, role.id);
    signatures.verify(role);
    var app = db.query(Application.class, "from Application where appCode='IAM'").getFirst();
    signatures.verify(app);
    if (app.status != 1) throw new IllegalStateException("IAM 应用状态异常，先核对数据");
    var before = db.menus(role.id);
    for (var menu : db.query(Menu.class, "from Menu where appId=?1 and menuType=2", app.id)) {
      signatures.verify(menu);
      for (long id : db.apis(menu.id)) signatures.verify(db.get(Api.class, id));
      if (!before.contains(menu.id)) db.save(new RolePermission(role.id, menu.id));
    }
    role.status = 1;
    signatures.sign(role);
    if (!db.roles(user.id).contains(role.id)) db.save(new UserRole(user.id, role.id));
    user.status = 1;
    user.managementScope = "ALL";
    user.passwordHash = passwords.hash(password);
    user.sessionVersion++;
    signatures.sign(user);
    audit.record(user, user, "RECOVER", "USER", user.id, "本地恢复初始管理角色", true);
  }

  private Application application(String code, String name) {
    var app = new Application();
    app.appCode = code;
    app.appName = name;
    db.save(app);
    signatures.sign(app);
    return app;
  }

  private Role role(String code, String name) {
    var role = new Role();
    role.roleCode = code;
    role.roleName = name;
    db.save(role);
    return role;
  }

  private Menu seed(long app, MenuSeed seed) {
    var menu = new Menu();
    menu.appId = app;
    menu.menuCode = seed.code;
    menu.menuName = seed.name;
    menu.path = seed.path;
    db.save(menu);
    for (String route : seed.routes) {
      int split = route.indexOf(' ');
      String method = route.substring(0, split), path = route.substring(split + 1);
      String canonical =
          RouteTemplateUtils.canonicalTemplate(RouteTemplateUtils.normalizeTemplate(path));
      var matches =
          db.query(
              Api.class,
              "from Api where appId=?1 and httpMethod=?2 and canonicalPath=?3",
              app,
              method,
              canonical);
      Api api;
      if (matches.isEmpty()) {
        api = new Api();
        api.appId = app;
        api.httpMethod = method;
        api.apiPath = path;
        api.canonicalPath = canonical;
        api.apiName = seed.name + " " + method;
        api.apiCode = "API_" + UUID.randomUUID().toString().replace("-", "");
        db.save(api);
        signatures.sign(api);
      } else api = matches.getFirst();
      db.save(new MenuApi(menu.id, api.id));
    }
    signatures.sign(menu);
    return menu;
  }
}
