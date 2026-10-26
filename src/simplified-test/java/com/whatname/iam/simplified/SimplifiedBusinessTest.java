package com.whatname.iam.simplified;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import com.fasterxml.jackson.databind.*;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.*;
import com.whatname.iam.service.*;
import jakarta.persistence.EntityManager;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Fresh database per scenario, real services/JPA/signatures, requests through the HTTP boundary. */
@SpringBootTest(properties = {
  "spring.config.import=",
  "spring.datasource.url=jdbc:h2:mem:simplified;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
  "spring.datasource.username=sa", "spring.datasource.password=",
  "spring.datasource.driver-class-name=org.h2.Driver",
  "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
  "spring.jpa.hibernate.ddl-auto=none", "spring.sql.init.mode=always",
  "spring.sql.init.schema-locations=classpath:schema-h2.sql",
  "iam.security.jwt-key-file=target/simplified-secrets/jwt.properties",
  "iam.security.data-signing.local-key-file=target/simplified-secrets/signing.properties",
  "iam.integration.hr-key=simplified-hr-key-1234567890",
  "iam.integration.oa-key=simplified-oa-key-1234567890",
  "iam.integration.apps.OA.key=simplified-app-key-1234567890"
})
@AutoConfigureMockMvc
class SimplifiedBusinessTest {
  static final String PASSWORD = "Simplified123!";
  static final String HR = "simplified-hr-key-1234567890", OA = "simplified-oa-key-1234567890";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired InitializationService initialization;
  @Autowired DomainRepository db;
  @Autowired SignatureService signatures;
  @Autowired EntityManager em;
  @Autowired PlatformTransactionManager manager;
  @Autowired AccessService access;
  @Autowired OrganizationService organization;
  @Autowired GrantService grants;
  String admin;
  long employee, outside, branch, other, root, demoRole;

  String databaseProduct() {
    return jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<String>) c -> c.getMetaData().getDatabaseProductName());
  }

  void clearDatabase() {
    boolean mysql = databaseProduct().equals("MySQL");
    jdbc.execute(mysql ? "SET FOREIGN_KEY_CHECKS=0" : "SET REFERENTIAL_INTEGRITY FALSE");
    try {
      for (String table : List.of("sys_operation_log", "sys_data_signature", "sys_user_role",
          "sys_role_permission", "sys_menu_api", "sys_api", "sys_menu", "sys_role", "sys_user",
          "sys_dept", "sys_application")) jdbc.update("DELETE FROM " + table);
    } finally { jdbc.execute(mysql ? "SET FOREIGN_KEY_CHECKS=1" : "SET REFERENTIAL_INTEGRITY TRUE"); }
  }

  @BeforeEach
  void freshSimplifiedInstallation() throws Exception {
    clearDatabase();
    var report = initialization.initialize(Path.of("src/simplified-test/resources/seed.json"),
        "owner", "初始管理者", "13900000000", PASSWORD);
    assertEquals(4, report.get("employees"));
    assertEquals(4, report.get("departments"));
    assertEquals(2, report.get("sourceApplications"));
    assertEquals(3, report.get("sourcePermissions"));
    employee = id("sys_user", "employee_no", "E001");
    outside = id("sys_user", "employee_no", "E002");
    root = id("sys_dept", "dept_name", "总部");
    branch = id("sys_dept", "dept_name", "研发");
    other = id("sys_dept", "dept_name", "销售");
    demoRole = id("sys_role", "role_code", "DEMO_EMPLOYEE");
    admin = login("owner", PASSWORD);
  }

  long id(String table, String field, String value) {
    return jdbc.queryForObject("SELECT id FROM " + table + " WHERE " + field + "=?", Long.class, value);
  }
  JsonNode call(MockHttpServletRequestBuilder request, Object body, int status, String code) throws Exception {
    if (body != null) request.contentType("application/json").content(json.writeValueAsString(body));
    var result = mvc.perform(request).andReturn();
    String text = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(status, result.getResponse().getStatus(), text);
    var node = json.readTree(text);
    assertEquals(code, node.path("code").asText(), text);
    assertEquals(status < 400, node.path("success").asBoolean(), text);
    return node.path("data");
  }
  JsonNode ok(MockHttpServletRequestBuilder r, Object body) throws Exception {
    return call(r.header("Authorization", admin), body, 200, "OK");
  }
  String login(String name, String password) throws Exception {
    return "Bearer " + call(post("/api/v1/auth/login"), Map.of("loginType", "USERNAME",
        "identifier", name, "password", password), 200, "OK").path("token").asText();
  }
  String employeeLogin() throws Exception {
    ok(put("/api/v1/users/" + employee + "/password"), Map.of("password", PASSWORD));
    return login("zs", PASSWORD);
  }
  JsonNode temporary(String name, String phone) throws Exception {
    return ok(post("/api/v1/users/temporary"), Map.of("username", name, "realName", "临时用户",
        "phone", phone, "deptId", branch, "password", PASSWORD));
  }
  void roles(long user, List<Long> ids) throws Exception {
    ok(put("/api/v1/users/" + user + "/roles"), Map.of("roleIds", ids));
  }

  <T extends Row> void signedChange(Class<T> type, long id, java.util.function.Consumer<T> change) {
    new TransactionTemplate(manager).executeWithoutResult(s -> {
      T row = db.get(type, id);
      change.accept(row);
      signatures.sign(row);
    });
  }

  long limitedRole(String... codes) throws Exception {
    long role = ok(post("/api/v1/roles"), Map.of("roleCode", "LIMITED", "roleName", "单项权限角色")).path("id").asLong();
    var menus = new ArrayList<Long>();
    for (String code : codes) menus.add(id("sys_menu", "menu_code", code));
    ok(put("/api/v1/roles/" + role + "/menus"), Map.of("menuIds", menus));
    roles(employee, List.of(role));
    return role;
  }

  @ParameterizedTest(name="{0}: departments={1}, users={2}, roles={3}")
  @CsvSource({"IAM_USERS_VIEW,true,true,false", "IAM_GRANTS_VIEW,true,true,true",
      "IAM_DEPTS_VIEW,true,false,false", "IAM_ROLES_VIEW,false,false,true", "NONE,false,false,false"})
  void leastPrivilegeCatalogMatrix(String permission, boolean departments, boolean users, boolean roles) throws Exception {
    String token = employeeLogin();
    if (!permission.equals("NONE")) limitedRole(permission);
    String[] paths = {"/departments", "/users", "/roles"};
    boolean[] allowed = {departments, users, roles};
    for (int i=0; i<paths.length; i++) {
      var response = call(get("/api/v1" + paths[i]).header("Authorization", token), null,
          allowed[i] ? 200 : 403, allowed[i] ? "OK" : "PERMISSION_DENIED");
      if (allowed[i]) assertFalse(response.isEmpty());
    }
    call(post("/api/v1/roles").header("Authorization", token), Map.of("roleCode", "DENIED", "roleName", "越权写入"), 403, "PERMISSION_DENIED");
    assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM sys_role WHERE role_code='DENIED'", Integer.class));
  }

  @ParameterizedTest @ValueSource(strings={"APPLICATION", "ROLE", "MENU", "API", "PARENT"})
  void disablingEachRbacLinkImmediatelyDeniesBusinessAndRestoringItRecoversAccess(String link) throws Exception {
    String token = employeeLogin();
    roles(employee, List.of(demoRole));
    long app = id("sys_application", "app_code", "OA");
    long menu = id("sys_menu", "menu_code", "OA_DEMO_1");
    long api = jdbc.queryForObject("SELECT api_id FROM sys_menu_api WHERE menu_id=?", Long.class, menu);
    long parent = ok(post("/api/v1/applications/" + app + "/menus"), Map.of("menuCode", "PARENT", "menuName", "父目录", "menuType", 1)).path("id").asLong();
    signedChange(Menu.class, menu, row -> row.parentId = parent);
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 200, "OK");
    for (int status : List.of(0, 1)) {
      switch (link) {
        case "APPLICATION" -> signedChange(Application.class, app, row -> row.status=status);
        case "ROLE" -> signedChange(Role.class, demoRole, row -> row.status=status);
        case "MENU" -> signedChange(Menu.class, menu, row -> row.status=status);
        case "API" -> signedChange(Api.class, api, row -> row.status=status);
        case "PARENT" -> signedChange(Menu.class, parent, row -> row.status=status);
      }
      call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, status==1 ? 200 : 403, status==1 ? "OK" : "PERMISSION_DENIED");
    }
    var display = call(get("/api/v1/auth/menus?appCode=OA").header("Authorization", token), null, 200, "OK").path("menus");
    assertTrue(java.util.stream.StreamSupport.stream(display.spliterator(), false).anyMatch(m -> m.path("id").asLong()==parent));
  }

  @ParameterizedTest @ValueSource(strings={"MENU_CYCLE", "CROSS_APP_PARENT", "DEPARTMENT_CYCLE", "MISSING_SIGNATURE"})
  void corruptPermissionTopologyFailsClosed(String corruption) throws Exception {
    String token = employeeLogin();
    roles(employee, List.of(demoRole));
    long menu = id("sys_menu", "menu_code", "OA_DEMO_1");
    switch (corruption) {
      case "MENU_CYCLE" -> signedChange(Menu.class, menu, row -> row.parentId=menu);
      case "CROSS_APP_PARENT" -> {
        long foreign = id("sys_menu", "menu_code", "HR_DEMO_1");
        signedChange(Menu.class, menu, row -> row.parentId=foreign);
      }
      case "DEPARTMENT_CYCLE" -> signedChange(Department.class, branch, row -> row.parentId=branch);
      case "MISSING_SIGNATURE" -> jdbc.update("DELETE FROM sys_data_signature WHERE data_type='Account' AND data_id=?", employee);
    }
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 503, "INTEGRITY_FAILURE");
  }

  @Test void multipleRolesFormAUnionAndReplacingOneAssociationPreservesTheOther() throws Exception {
    String token = employeeLogin();
    long view = limitedRole("IAM_USERS_VIEW");
    roles(employee, List.of(view, demoRole));
    call(get("/api/v1/users").header("Authorization", token), null, 200, "OK");
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 200, "OK");
    roles(employee, List.of(view, demoRole));
    assertEquals(2, ok(get("/api/v1/users/" + employee + "/roles"), null).size());
    roles(employee, List.of(view));
    call(get("/api/v1/users").header("Authorization", token), null, 200, "OK");
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 403, "PERMISSION_DENIED");
  }

  @Test void scopedAuditIncludesOnlyOwnDepartmentTreeAndChecksEveryPageBoundary() throws Exception {
    String token = employeeLogin();
    limitedRole("IAM_AUDIT_VIEW", "IAM_USERS_VIEW");
    temporary("audited", "13700000001");
    var logs = call(get("/api/v1/audit/operations").header("Authorization", token), null, 200, "OK");
    assertTrue(logs.path("total").asInt()>0);
    Set<Long> visible = Set.of(branch, id("sys_dept", "dept_name", "研发子组"));
    for (var row : logs.path("items")) assertTrue(visible.contains(row.path("deptId").asLong()));
    for (String query : List.of("pageSize=0", "pageSize=101"))
      call(get("/api/v1/audit/operations?" + query).header("Authorization", token), null, 400, "INVALID_ARGUMENT");
    var users = call(get("/api/v1/users?deptId=" + branch).header("Authorization", token), null, 200, "OK");
    for (var row : users.path("items")) assertEquals(branch, row.path("deptId").asLong());
  }

  @Test void temporaryPatchPreservesOmittedFieldsAndDuplicateWritesRollback() throws Exception {
    long user = temporary("partial", "13700000001").path("id").asLong();
    var named = ok(patch("/api/v1/users/" + user), Map.of("realName", "只改姓名"));
    assertEquals("13700000001", named.path("phone").asText());
    var phoned = ok(patch("/api/v1/users/" + user), Map.of("phone", "13700000002"));
    assertEquals("只改姓名", phoned.path("realName").asText());
    call(patch("/api/v1/users/" + user).header("Authorization", admin), Map.of("realName", "应当回滚", "phone", "13800000001"), 409, "DUPLICATE_RESOURCE");
    assertEquals("只改姓名", ok(get("/api/v1/users/" + user), null).path("realName").asText());
    call(patch("/api/v1/users/" + employee + "/status").header("Authorization", admin), Map.of("status", 2), 422, "BUSINESS_RULE_VIOLATION");
    call(put("/api/v1/users/" + user + "/scope").header("Authorization", admin), Map.of("managementScope", "SELF"), 400, "INVALID_ARGUMENT");
    ok(put("/api/v1/users/" + user + "/scope"), Map.of("managementScope", "DEPT_TREE"));
    ok(patch("/api/v1/users/" + user + "/status"), Map.of("status", 2));
    call(post("/api/v1/auth/login"), Map.of("identifier", "partial", "loginType", "USERNAME", "password", PASSWORD), 401, "INVALID_CREDENTIALS");
  }

  @Test void hrRepeatedTransferAndSyncHaveDifferentRoleEffectsAndSuspensionEndsSession() throws Exception {
    String token = employeeLogin();
    roles(employee, List.of(demoRole));
    call(post("/api/v1/integrations/hr/users/" + employee + "/transfer").header("X-Service-Key", HR), Map.of("deptId", branch), 200, "OK");
    assertEquals(1, ok(get("/api/v1/users/" + employee + "/roles"), null).size());
    Map<String,Object> input = new HashMap<>(Map.of("employeeNo", "E001", "realName", "张三", "phone", "13800000001", "deptId", other));
    call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), input, 200, "OK");
    assertEquals(0, ok(get("/api/v1/users/" + employee + "/roles"), null).size());
    input.put("status", 2);
    call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), input, 200, "OK");
    call(get("/api/v1/auth/me").header("Authorization", token), null, 401, "INVALID_TOKEN");
  }

  @Test void hrRejectsInvalidTargetsCyclesAndMergedDepartmentsWithoutChangingAccounts() throws Exception {
    long temporary = temporary("notemployee", "13700000001").path("id").asLong();
    for (String operation : List.of("transfer", "terminate"))
      call(post("/api/v1/integrations/hr/users/" + temporary + "/" + operation).header("X-Service-Key", HR), Map.of("deptId", other), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/integrations/hr/departments").header("X-Service-Key", HR), Map.of("deptCode", "D001", "deptName", "循环根", "parentId", branch), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/integrations/hr/departments/" + branch + "/merge").header("X-Service-Key", HR), Map.of("targetDeptId", other), 200, "OK");
    call(post("/api/v1/integrations/hr/users/" + employee + "/transfer").header("X-Service-Key", HR), Map.of("deptId", branch), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/integrations/hr/departments").header("X-Service-Key", HR), Map.of("deptCode", "D002", "deptName", "已合并"), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), Map.of("employeeNo", "E001", "realName", "张三", "phone", "13800000001", "deptId", branch), 422, "BUSINESS_RULE_VIOLATION");
    assertEquals(other, ok(get("/api/v1/users/" + employee), null).path("deptId").asLong());
    call(post("/api/v1/integrations/hr/users/" + employee + "/terminate").header("X-Service-Key", HR), Map.of(), 200, "OK");
    call(post("/api/v1/integrations/hr/users/" + employee + "/transfer").header("X-Service-Key", HR), Map.of("deptId", other), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test void hrUsernameCollisionsUsePhoneSuffixThenCounterAndRootDepartmentSyncAcceptsZero() throws Exception {
    for (String phone : List.of("13700000001", "13600000001")) {
      var created = call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), Map.of("employeeNo", phone, "realName", "张三", "phone", phone, "deptId", branch, "password", PASSWORD), 200, "OK");
      assertEquals(phone.startsWith("137") ? "zs0001" : "zs00012", created.path("username").asText());
    }
    for (Object parent : List.of("0", 0))
      assertEquals(0, call(post("/api/v1/integrations/hr/departments").header("X-Service-Key", HR), Map.of("deptCode", "ROOT_SYNC", "deptName", "同步根", "parentId", parent), 200, "OK").path("parentId").asInt());
  }

  @Test void invalidResourceEditsRollbackAndRepeatedAssociationSavesAreIdempotent() throws Exception {
    long app = id("sys_application", "app_code", "OA");
    long foreignApp = id("sys_application", "app_code", "HR");
    long menu = id("sys_menu", "menu_code", "OA_DEMO_1");
    long foreignMenu = id("sys_menu", "menu_code", "HR_DEMO_1");
    long api = jdbc.queryForObject("SELECT api_id FROM sys_menu_api WHERE menu_id=?", Long.class, menu);
    call(patch("/api/v1/applications/" + foreignApp + "/menus/" + menu).header("Authorization", admin), Map.of("menuName", "跨应用"), 400, "INVALID_ARGUMENT");
    call(patch("/api/v1/applications/" + app + "/menus/" + menu).header("Authorization", admin), Map.of("menuName", "跨应用父菜单", "parentId", foreignMenu), 400, "INVALID_ARGUMENT");
    call(patch("/api/v1/applications/" + foreignApp + "/apis/" + api).header("Authorization", admin), Map.of("apiName", "跨应用", "apiPath", "/bad", "httpMethod", "GET"), 400, "INVALID_ARGUMENT");
    call(post("/api/v1/applications/" + app + "/apis").header("Authorization", admin), Map.of("apiCode", "BAD", "apiName", "非法方法", "apiPath", "/bad", "httpMethod", "TRACE"), 400, "INVALID_ARGUMENT");
    long dir = ok(post("/api/v1/applications/" + app + "/menus"), Map.of("menuCode", "D", "menuName", "目录", "menuType", 1, "parentId", "0")).path("id").asLong();
    call(put("/api/v1/menus/" + dir + "/apis").header("Authorization", admin), Map.of("apiIds", List.of(api)), 422, "BUSINESS_RULE_VIOLATION");
    call(put("/api/v1/roles/" + demoRole + "/menus").header("Authorization", admin), Map.of("menuIds", List.of(dir)), 400, "INVALID_ARGUMENT");
    for (int i=0; i<2; i++) {
      ok(put("/api/v1/menus/" + menu + "/apis"), Map.of("apiIds", List.of(api)));
      ok(put("/api/v1/roles/" + demoRole + "/menus"), Map.of("menuIds", List.of(menu)));
    }
    assertEquals(1, ok(get("/api/v1/menus/" + menu + "/apis"), null).size());
    assertEquals(1, ok(get("/api/v1/roles/" + demoRole + "/menus"), null).size());
    assertEquals(app, ok(get("/api/v1/applications/" + app + "/menus"), null).get(0).path("appId").asLong());
  }

  @Test void batchAbortsOnAuthenticationOrIntegrityFailureAndDoesNotGrantLaterTargets() throws Exception {
    Map<String,Object> body = Map.of("userIds", List.of(employee, outside), "roleId", demoRole, "operation", "GRANT");
    call(post("/api/v1/grants/batch").header("Authorization", "Bearer broken"), body, 401, "INVALID_TOKEN");
    jdbc.update("UPDATE sys_user SET real_name='损坏' WHERE id=?", employee);
    call(post("/api/v1/grants/batch").header("Authorization", admin), body, 503, "INTEGRITY_FAILURE");
    assertEquals(0, ok(get("/api/v1/users/" + outside + "/roles"), null).size());
  }

  @ParameterizedTest @ValueSource(strings={"EMPTY_DEPARTMENTS", "UNKNOWN_PARENT", "UNKNOWN_EMPLOYEE_DEPARTMENT"})
  void invalidInitializationSourceRollsBackTheEntireImport(String invalid, @org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
    var fixture = json.readTree(Path.of("src/simplified-test/resources/seed.json").toFile());
    var tables = fixture.path("tables");
    switch (invalid) {
      case "EMPTY_DEPARTMENTS" -> ((com.fasterxml.jackson.databind.node.ArrayNode) tables.path("departments").path("records")).removeAll();
      case "UNKNOWN_PARENT" -> ((com.fasterxml.jackson.databind.node.ObjectNode) tables.path("departments").path("records").get(1)).put("上级部门", "不存在");
      case "UNKNOWN_EMPLOYEE_DEPARTMENT" -> ((com.fasterxml.jackson.databind.node.ObjectNode) tables.path("employees").path("records").get(0)).put("部门", "不存在");
    }
    Path source = dir.resolve("invalid.json"); json.writeValue(source.toFile(), fixture);
    clearDatabase();
    assertThrows(IllegalStateException.class, () -> initialization.initialize(source, "owner", "管理者", "13900000000", PASSWORD));
    for (String table : List.of("sys_dept", "sys_user", "sys_application", "sys_data_signature", "sys_operation_log"))
      assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class), "Partial import leaked into " + table);
  }

  @Test void recoveryRestoresMissingRolePermissionsButRefusesDisabledIam() throws Exception {
    long role = id("sys_role", "role_code", "INITIAL_ADMIN");
    ok(put("/api/v1/roles/" + role + "/menus"), Map.of("menuIds", List.of()));
    initialization.recover("owner", "Recovered123!");
    admin = login("owner", "Recovered123!");
    assertEquals(14, ok(get("/api/v1/auth/me"), null).path("menus").size());
    long iam = id("sys_application", "app_code", "IAM");
    signedChange(Application.class, iam, row -> row.status=0);
    assertThrows(IllegalStateException.class, () -> initialization.recover("owner", "Other123!"));
    assertEquals(0, jdbc.queryForObject("SELECT status FROM sys_application WHERE id=?", Integer.class, iam));
  }

  @Test void concurrentRoleReplacementNeverProducesMixedOrUnsignedState() throws Exception {
    long extra = ok(post("/api/v1/roles"), Map.of("roleCode", "EXTRA", "roleName", "附加角色")).path("id").asLong();
    var ready = new java.util.concurrent.CountDownLatch(2);
    var start = new java.util.concurrent.CountDownLatch(1);
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
      var tasks = new ArrayList<java.util.concurrent.Future<?>>();
      for (List<Long> desired : List.of(List.of(demoRole), List.of(demoRole, extra))) {
        tasks.add(executor.submit(() -> {
          ready.countDown();
          if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
          grants.replace(admin, employee, desired);
          return null;
        }));
      }
      assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
      start.countDown();
      for (var task : tasks) task.get(20, java.util.concurrent.TimeUnit.SECONDS);
    }
    var actual = ok(get("/api/v1/users/" + employee + "/roles"), null);
    Set<Long> ids = new HashSet<>(); actual.forEach(n -> ids.add(n.asLong()));
    assertTrue(ids.equals(Set.of(demoRole)) || ids.equals(Set.of(demoRole, extra)));
    assertEquals(ids.size(), actual.size());
    new TransactionTemplate(manager).executeWithoutResult(s -> signatures.verify(db.get(Account.class, employee)));
  }

  @Test void httpErrorsAndRequestIdsFollowThePublicContract() throws Exception {
    var safe = mvc.perform(get("/api/v1/system/ping").header("X-Request-Id", "test.request-42")).andReturn().getResponse();
    assertEquals("test.request-42", safe.getHeader("X-Request-Id"));
    assertEquals("test.request-42", json.readTree(safe.getContentAsString()).path("requestId").asText());
    var unsafe = mvc.perform(get("/api/v1/system/ping").header("X-Request-Id", "not safe!")).andReturn().getResponse();
    assertNotEquals("not safe!", unsafe.getHeader("X-Request-Id"));
    assertDoesNotThrow(() -> UUID.fromString(unsafe.getHeader("X-Request-Id")));
    assertNull(org.slf4j.MDC.get("requestId"));
    call(get("/api/v1/missing"), null, 404, "RESOURCE_NOT_FOUND");
    call(post("/api/v1/system/ping"), null, 405, "METHOD_NOT_ALLOWED");
    call(post("/api/v1/auth/login").contentType("text/plain").content("invalid"), null, 415, "UNSUPPORTED_MEDIA_TYPE");
    call(post("/api/v1/auth/login").contentType("application/json").content("{"), null, 400, "INVALID_ARGUMENT");
    call(get("/api/v1/users?deptId=not-a-number").header("Authorization", admin), null, 400, "INVALID_ARGUMENT");
    for (String code : List.of("", "../OA"))
      call(post("/api/v1/auth/check").header("X-App-Code", code).header("Authorization", admin), Map.of("method", "GET", "route", "/anything"), 401, "INVALID_SERVICE");
    call(post("/api/v1/auth/check").header("Authorization", admin), Map.of(), 401, "INVALID_SERVICE");
  }

  @Test void initializationProducesElevenTablesSignedResourcesAndNoImplicitEmployeeGrants() throws Exception {
    String schema = databaseProduct().equals("MySQL") ? "database()" : "'public'";
    assertEquals(11, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema=" + schema + " AND table_type='BASE TABLE'", Integer.class));
    assertEquals(5, jdbc.queryForObject("SELECT count(*) FROM sys_application", Integer.class));
    assertEquals(4, jdbc.queryForObject("SELECT count(DISTINCT username) FROM sys_user WHERE source=1", Integer.class));
    assertEquals(0, ok(get("/api/v1/users/" + employee + "/roles"), null).size());
    assertThrows(IllegalStateException.class, () -> initialization.initialize(Path.of("missing.json"), "x", "x", "13900000009", PASSWORD));
    new TransactionTemplate(manager).executeWithoutResult(s -> {
      for (Class<? extends Row> type : List.of(Account.class, Department.class, Application.class, Menu.class, Api.class, Role.class))
        db.all(type).forEach(signatures::verify);
    });
  }

  @Test void authenticationRejectsBadCredentialsAndNeverSerializesPasswordOrSessionVersion() throws Exception {
    for (String name : List.of("owner", "missing"))
      call(post("/api/v1/auth/login"), Map.of("loginType", "USERNAME", "identifier", name, "password", "wrong"), 401, "INVALID_CREDENTIALS");
    var phoneLogin = call(post("/api/v1/auth/login"), Map.of("loginType", "PHONE", "identifier", "+8613900000000", "password", PASSWORD), 200, "OK");
    assertFalse(phoneLogin.path("user").has("passwordHash"));
    assertFalse(phoneLogin.path("user").has("sessionVersion"));
    call(get("/api/v1/auth/me"), null, 401, "INVALID_TOKEN");
    call(get("/api/v1/auth/me").header("Authorization", "Bearer broken"), null, 401, "INVALID_TOKEN");
    call(post("/api/v1/auth/login"), Map.of("loginType", "SMS", "identifier", "owner"), 400, "INVALID_ARGUMENT");
    assertTrue(jdbc.queryForObject("SELECT count(*) FROM sys_operation_log WHERE operation_type='LOGIN' AND success=false", Integer.class) >= 2);
  }

  @Test void passwordChangeAndLogoutInvalidatePreviouslyIssuedTokens() throws Exception {
    call(put("/api/v1/auth/password").header("Authorization", admin), Map.of("oldPassword", "wrong", "password", "Changed123!"), 401, "INVALID_CREDENTIALS");
    ok(put("/api/v1/auth/password"), Map.of("oldPassword", PASSWORD, "password", "Changed123!"));
    call(get("/api/v1/auth/me").header("Authorization", admin), null, 401, "INVALID_TOKEN");
    admin = login("owner", "Changed123!");
    ok(post("/api/v1/auth/logout"), Map.of());
    call(get("/api/v1/auth/me").header("Authorization", admin), null, 401, "INVALID_TOKEN");
  }

  @Test void temporaryAccountLifecycleClearsRolesAndInvalidatesSessions() throws Exception {
    long user = temporary("visitor", "13700000001").path("id").asLong();
    roles(user, List.of(demoRole));
    String token = login("visitor", PASSWORD);
    var edited = ok(patch("/api/v1/users/" + user), Map.of("realName", "新姓名", "phone", "13700000002"));
    assertEquals("新姓名", edited.path("realName").asText());
    ok(patch("/api/v1/users/" + user + "/status"), Map.of("status", 2));
    assertEquals(0, ok(get("/api/v1/users/" + user + "/roles"), null).size());
    call(get("/api/v1/auth/me").header("Authorization", token), null, 401, "INVALID_TOKEN");
    ok(patch("/api/v1/users/" + user + "/status"), Map.of("status", 1));
    assertFalse(login("visitor", PASSWORD).isBlank());
    call(patch("/api/v1/users/" + employee).header("Authorization", admin), Map.of("realName", "不允许"), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/users/temporary").header("Authorization", admin), Map.of("username", "weak", "realName", "弱密码", "phone", "13700000003", "deptId", branch, "password", "12345678"), 400, "INVALID_ARGUMENT");
  }

  @Test void userFiltersPaginationAndDetailsRespectDepartmentTree() throws Exception {
    String token = employeeLogin();
    long role = id("sys_role", "role_code", "INITIAL_ADMIN");
    roles(employee, List.of(role));
    var page = call(get("/api/v1/users?keyword=张&source=1&status=1&pageSize=1").header("Authorization", token), null, 200, "OK");
    assertEquals(3, page.path("total").asInt());
    assertEquals(1, page.path("items").size());
    call(get("/api/v1/users/" + outside).header("Authorization", token), null, 403, "OUT_OF_SCOPE");
    call(get("/api/v1/users?deptId=" + other).header("Authorization", token), null, 403, "OUT_OF_SCOPE");
    call(put("/api/v1/users/" + employee + "/scope").header("Authorization", token), Map.of("managementScope", "ALL"), 403, "OUT_OF_SCOPE");
    for (String query : List.of("page=0", "pageSize=0", "pageSize=101"))
      call(get("/api/v1/users?" + query).header("Authorization", admin), null, 400, "INVALID_ARGUMENT");
    var depts = call(get("/api/v1/departments").header("Authorization", token), null, 200, "OK");
    assertEquals(2, depts.size());
    ok(put("/api/v1/users/" + employee + "/scope"), Map.of("managementScope", "ALL"));
    assertEquals(outside, call(get("/api/v1/users/" + outside).header("Authorization", token), null, 200, "OK").path("id").asLong());
  }

  @Test void rbacHasNoAdministratorBypassAndRevocationIsImmediate() throws Exception {
    String token = employeeLogin();
    call(get("/api/v1/users").header("Authorization", token), null, 403, "PERMISSION_DENIED");
    roles(employee, List.of(demoRole));
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 200, "OK");
    call(get("/api/v1/users").header("Authorization", token), null, 403, "PERMISSION_DENIED");
    roles(employee, List.of());
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 403, "PERMISSION_DENIED");
    long owner = id("sys_user", "username", "owner");
    roles(owner, List.of());
    call(get("/api/v1/users").header("Authorization", admin), null, 403, "PERMISSION_DENIED");
    assertTrue(call(get("/api/v1/auth/me").header("Authorization", admin), null, 200, "OK").path("menus").isEmpty());
    initialization.recover("owner", "Recovered123!");
    call(get("/api/v1/auth/me").header("Authorization", admin), null, 401, "INVALID_TOKEN");
    admin = login("owner", "Recovered123!");
    assertTrue(ok(get("/api/v1/users"), null).path("total").asInt() > 0);
    assertThrows(IllegalStateException.class, () -> initialization.recover("zs", PASSWORD));
  }

  @Test void invalidRoleReplacementRollsBackAndDisabledRoleCannotBeGranted() throws Exception {
    roles(employee, List.of(demoRole));
    call(put("/api/v1/users/" + employee + "/roles").header("Authorization", admin), Map.of("roleIds", List.of(demoRole, Long.MAX_VALUE)), 404, "RESOURCE_NOT_FOUND");
    assertEquals(demoRole, ok(get("/api/v1/users/" + employee + "/roles"), null).get(0).asLong());
    ok(patch("/api/v1/roles/" + demoRole), Map.of("roleName", "停用演示", "status", 0));
    call(put("/api/v1/users/" + outside + "/roles").header("Authorization", admin), Map.of("roleIds", List.of(demoRole)), 422, "BUSINESS_RULE_VIOLATION");
    roles(employee, List.of());
  }

  @Test void batchReportsPartialSuccessAndCommitsOnlyAllowedTargets() throws Exception {
    String token = employeeLogin();
    roles(employee, List.of(id("sys_role", "role_code", "INITIAL_ADMIN")));
    long child = id("sys_user", "employee_no", "E003");
    var result = call(post("/api/v1/grants/batch").header("Authorization", token), Map.of("userIds", List.of(child, outside), "roleId", demoRole, "operation", "GRANT"), 200, "OK");
    assertEquals(2, result.path("total").asInt());
    assertEquals(1, result.path("succeeded").asInt());
    assertEquals(demoRole, ok(get("/api/v1/users/" + child + "/roles"), null).get(0).asLong());
    assertEquals(0, ok(get("/api/v1/users/" + outside + "/roles"), null).size());
    var revoked = ok(post("/api/v1/grants/batch"), Map.of("userIds", List.of(child), "roleId", demoRole, "operation", "REVOKE"));
    assertEquals(1, revoked.path("succeeded").asInt());
    call(post("/api/v1/grants/batch").header("Authorization", admin), Map.of("userIds", List.of(), "roleId", demoRole, "operation", "GRANT"), 400, "INVALID_ARGUMENT");
  }

  @Test void resourceConfigurationRoundTripsAndProtectsCrossApplicationBoundaries() throws Exception {
    long app = ok(post("/api/v1/applications"), Map.of("appCode", "TEST", "appName", "新应用")).path("id").asLong();
    assertEquals("修改应用", ok(patch("/api/v1/applications/" + app), Map.of("appName", "修改应用", "appDesc", "说明")).path("appName").asText());
    long directory = ok(post("/api/v1/applications/" + app + "/menus"), Map.of("menuCode", "DIR", "menuName", "目录", "menuType", 1)).path("id").asLong();
    long menu = ok(post("/api/v1/applications/" + app + "/menus"), Map.of("menuCode", "LEAF", "menuName", "功能", "parentId", directory)).path("id").asLong();
    long api = ok(post("/api/v1/applications/" + app + "/apis"), Map.of("apiCode", "READ", "apiName", "读取", "httpMethod", "GET", "apiPath", "/business/{id}")).path("id").asLong();
    ok(patch("/api/v1/applications/" + app + "/apis/" + api), Map.of("apiName", "读取新版", "httpMethod", "GET", "apiPath", "/business/{item}"));
    ok(put("/api/v1/menus/" + menu + "/apis"), Map.of("apiIds", List.of(api, api)));
    assertEquals(1, ok(get("/api/v1/menus/" + menu + "/apis"), null).size());
    long role = ok(post("/api/v1/roles"), Map.of("roleCode", "TEST_ROLE", "roleName", "测试角色")).path("id").asLong();
    ok(put("/api/v1/roles/" + role + "/menus"), Map.of("menuIds", List.of(menu)));
    assertEquals(menu, ok(get("/api/v1/roles/" + role + "/menus"), null).get(0).asLong());
    assertEquals(2, ok(get("/api/v1/applications/" + app + "/menus"), null).size());
    assertEquals(1, ok(get("/api/v1/applications/" + app + "/apis"), null).size());
    assertTrue(ok(get("/api/v1/applications"), null).size() >= 6);
    assertTrue(ok(get("/api/v1/roles"), null).size() >= 3);
    call(patch("/api/v1/applications/" + app + "/menus/" + directory).header("Authorization", admin), Map.of("menuName", "循环", "menuType", 1, "parentId", menu), 422, "BUSINESS_RULE_VIOLATION");
    call(patch("/api/v1/applications/" + app + "/menus/" + menu).header("Authorization", admin), Map.of("menuName", "改目录", "menuType", 1), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/applications/" + app + "/apis").header("Authorization", admin), Map.of("apiCode", "DUP", "apiName", "冲突", "httpMethod", "GET", "apiPath", "/business/fixed"), 409, "DUPLICATE_RESOURCE");
    long foreign = jdbc.queryForObject("SELECT min(id) FROM sys_api WHERE app_id<>?", Long.class, app);
    call(put("/api/v1/menus/" + menu + "/apis").header("Authorization", admin), Map.of("apiIds", List.of(foreign)), 400, "INVALID_ARGUMENT");
    assertEquals(api, ok(get("/api/v1/menus/" + menu + "/apis"), null).get(0).asLong());
    ok(put("/api/v1/roles/" + role + "/menus"), Map.of("menuIds", List.of()));
    ok(put("/api/v1/menus/" + menu + "/apis"), Map.of("apiIds", List.of()));
    assertEquals(0, ok(get("/api/v1/menus/" + menu + "/apis"), null).size());
    long iam = id("sys_application", "app_code", "IAM");
    call(patch("/api/v1/applications/" + iam).header("Authorization", admin), Map.of("appName", "IAM", "status", 0), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test void freezeBlocksBusinessAndGrantsUntilUnfrozen() throws Exception {
    String token = employeeLogin();
    roles(employee, List.of(demoRole));
    ok(post("/api/v1/departments/" + branch + "/freeze"), Map.of());
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 403, "PERMISSION_DENIED");
    call(put("/api/v1/users/" + employee + "/roles").header("Authorization", admin), Map.of("roleIds", List.of(demoRole)), 422, "BUSINESS_RULE_VIOLATION");
    call(post("/api/v1/users/temporary").header("Authorization", admin), Map.of("username", "blocked", "realName", "冻结用户", "phone", "13700000001", "deptId", branch, "password", PASSWORD), 422, "BUSINESS_RULE_VIOLATION");
    ok(post("/api/v1/departments/" + branch + "/unfreeze"), Map.of());
    call(get("/api/v1/demo/OA/feature1").header("Authorization", token), null, 200, "OK");
  }

  @Test void hrSyncTransferAndTerminationFollowSimplifiedRules() throws Exception {
    var created = call(post("/api/v1/integrations/hr/departments").header("X-Service-Key", HR), Map.of("deptCode", "SYNC", "deptName", "同步部门", "parentId", root), 200, "OK");
    long dept = created.path("id").asLong();
    call(post("/api/v1/integrations/hr/departments").header("X-Service-Key", HR), Map.of("deptCode", "SYNC", "deptName", "改名部门", "parentId", root), 200, "OK");
    var input = new HashMap<String, Object>(Map.of("employeeNo", "HR001", "realName", "王五", "phone", "13700000004", "deptId", dept, "password", PASSWORD));
    long user = call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), input, 200, "OK").path("id").asLong();
    roles(user, List.of(demoRole));
    input.put("realName", "王五新"); input.remove("password");
    call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), input, 200, "OK");
    assertEquals(1, ok(get("/api/v1/users/" + user + "/roles"), null).size());
    call(post("/api/v1/integrations/hr/users/" + user + "/transfer").header("X-Service-Key", HR), Map.of("deptId", branch), 200, "OK");
    assertEquals(0, ok(get("/api/v1/users/" + user + "/roles"), null).size());
    roles(user, List.of(demoRole));
    String token = login("ww", PASSWORD);
    for (int i = 0; i < 2; i++) call(post("/api/v1/integrations/hr/users/" + user + "/terminate").header("X-Service-Key", HR), Map.of(), 200, "OK");
    assertEquals(3, ok(get("/api/v1/users/" + user), null).path("status").asInt());
    assertEquals(0, ok(get("/api/v1/users/" + user + "/roles"), null).size());
    call(get("/api/v1/auth/me").header("Authorization", token), null, 401, "INVALID_TOKEN");
    call(post("/api/v1/integrations/hr/employees").header("X-Service-Key", HR), input, 422, "BUSINESS_RULE_VIOLATION");
    call(put("/api/v1/users/" + user + "/password").header("Authorization", admin), Map.of("password", PASSWORD), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test void departmentMergeMovesPeopleAndChildrenPreservesRolesAndIsIdempotent() throws Exception {
    roles(employee, List.of(demoRole));
    for (int i=0; i<2; i++) call(post("/api/v1/integrations/hr/departments/" + branch + "/merge").header("X-Service-Key", HR), Map.of("targetDeptId", other), 200, "OK");
    assertEquals(other, ok(get("/api/v1/users/" + employee), null).path("deptId").asLong());
    assertEquals(1, ok(get("/api/v1/users/" + employee + "/roles"), null).size());
    assertEquals(other, jdbc.queryForObject("SELECT parent_id FROM sys_dept WHERE dept_name='研发子组'", Long.class));
    call(post("/api/v1/integrations/hr/departments/" + other + "/merge").header("X-Service-Key", HR), Map.of("targetDeptId", other), 400, "INVALID_ARGUMENT");
    call(post("/api/v1/integrations/hr/departments/" + root + "/merge").header("X-Service-Key", HR), Map.of("targetDeptId", other), 422, "BUSINESS_RULE_VIOLATION");
  }

  @Test void oaIsIdempotentAndApplicationCredentialCannotBeReplacedWithIntegrationKey() throws Exception {
    String token = employeeLogin();
    for (String operation : List.of("GRANT", "GRANT", "REVOKE", "REVOKE")) {
      call(post("/api/v1/integrations/oa/roles").header("X-Service-Key", OA), Map.of("userId", employee, "roleId", demoRole, "operation", operation), 200, "OK");
      assertEquals(operation.equals("GRANT") ? 1 : 0, ok(get("/api/v1/users/" + employee + "/roles"), null).size());
    }
    for (String key : List.of(HR, OA, "wrong")) call(post("/api/v1/auth/check").header("Authorization", token).header("X-App-Code", "OA").header("X-Service-Key", key), Map.of("method", "GET", "route", "/api/v1/demo/OA/feature1"), 401, "INVALID_SERVICE");
    roles(employee, List.of(demoRole));
    assertTrue(call(post("/api/v1/auth/check").header("Authorization", token).header("X-App-Code", "OA").header("X-Service-Key", "simplified-app-key-1234567890"), Map.of("method", "GET", "route", "/api/v1/demo/OA/feature1"), 200, "OK").path("allowed").asBoolean());
    call(post("/api/v1/integrations/hr/departments"), Map.of("deptCode", "BAD", "deptName", "错误"), 401, "INVALID_SERVICE");
    call(post("/api/v1/integrations/oa/roles").header("X-Service-Key", OA), Map.of("operation", "OTHER"), 400, "INVALID_ARGUMENT");
  }

  @Test void tamperingFailsClosedAndAuditCanBeFiltered() throws Exception {
    ok(get("/api/v1/auth/menus?appCode=OA"), null);
    var logs = ok(get("/api/v1/audit/operations?operation=LOGIN&pageSize=1"), null);
    assertTrue(logs.path("total").asInt() >= 1);
    assertEquals("LOGIN", logs.path("items").get(0).path("operationType").asText());
    call(get("/api/v1/audit/operations?page=0").header("Authorization", admin), null, 400, "INVALID_ARGUMENT");
    jdbc.update("UPDATE sys_user SET real_name='数据库篡改' WHERE id=?", employee);
    call(get("/api/v1/users/" + employee).header("Authorization", admin), null, 503, "INTEGRITY_FAILURE");
    call(get("/api/v1/system/ping"), null, 200, "OK");
    assertTrue(call(get("/api/v1/system/database"), null, 200, "OK").path("connected").asBoolean());
  }

  @Test
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="coursework.fullImport", matches="true")
  void fullSourceImportIsCheckedSeparatelyFromTheSmallBusinessFixture() throws Exception {
    clearDatabase();
    var report = initialization.initialize(Path.of("target/prepared-data.json"), "fullowner", "全量管理者", "13900000000", PASSWORD);
    assertEquals(111, report.get("departments"));
    assertEquals(10000, report.get("employees"));
    assertEquals(18, report.get("sourceApplications"));
    assertEquals(359, report.get("sourcePermissions"));
    assertEquals(10001, jdbc.queryForObject("SELECT count(*) FROM sys_user", Integer.class));
    assertEquals(21, jdbc.queryForObject("SELECT count(*) FROM sys_application", Integer.class));
    admin = login("fullowner", PASSWORD);
    assertEquals(10001, ok(get("/api/v1/users?pageSize=1"), null).path("total").asInt());
    initialization.recover("fullowner", "Recovered123!");
    call(get("/api/v1/auth/me").header("Authorization", admin), null, 401, "INVALID_TOKEN");
    admin = login("fullowner", "Recovered123!");
    assertEquals(14, ok(get("/api/v1/auth/me"), null).path("menus").size());
  }
}
