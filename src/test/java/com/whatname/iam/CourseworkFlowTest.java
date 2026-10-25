package com.whatname.iam;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.*;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.*;
import com.whatname.iam.service.*;
import com.whatname.iam.service.auth.PasswordHasher;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    properties = {
      "spring.config.import=",
      "spring.datasource.url=jdbc:h2:mem:coursework;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
      "spring.jpa.hibernate.ddl-auto=none",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:schema-h2.sql",
      "iam.security.jwt-key-file=target/test-secrets/jwt.properties",
      "iam.security.data-signing.local-key-file=target/test-secrets/signing.properties",
      "iam.integration.hr-key=test-hr-service-key-long-enough",
      "iam.integration.oa-key=test-oa-service-key-long-enough",
      "iam.integration.apps.OA.key=test-oa-app-key-long-enough"
    })
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CourseworkFlowTest {
  @Autowired DomainRepository db;
  @Autowired AccountRepository accounts;
  @Autowired SignatureService signatures;
  @Autowired InitializationService initialization;
  @Autowired GrantService grants;
  @Autowired BatchService batches;
  @Autowired AccessService access;
  @Autowired AccountService users;
  @Autowired OrganizationService hr;
  @Autowired ResourceService resources;
  @Autowired PasswordHasher passwords;
  @Autowired JwtTokenService jwt;
  @Autowired PlatformTransactionManager manager;
  @Autowired EntityManager em;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired javax.sql.DataSource dataSource;
  TransactionTemplate tx;
  String adminToken, employeeToken, outsiderToken;
  long root, branch, other, admin, employee, outsider, businessRole, otherRole, iam, oa, menu;

  @BeforeAll
  void seed() {
    tx = new TransactionTemplate(manager);
    tx.executeWithoutResult(
        s -> {
          var d = new Department();
          d.deptCode = "ROOT";
          d.deptName = "总部";
          db.save(d);
          signatures.sign(d);
          root = d.id;
          var b = new Department();
          b.deptCode = "B";
          b.deptName = "部门一";
          b.parentId = root;
          db.save(b);
          signatures.sign(b);
          branch = b.id;
          var o = new Department();
          o.deptCode = "O";
          o.deptName = "部门二";
          o.parentId = root;
          db.save(o);
          signatures.sign(o);
          other = o.id;
          var app = new Application();
          app.appCode = "IAM";
          app.appName = "平台";
          db.save(app);
          signatures.sign(app);
          iam = app.id;
          var full = new Role();
          full.roleCode = "MANAGER";
          full.roleName = "管理员";
          db.save(full);
          Map<String, Api> apiByRoute = new HashMap<>();
          for (var spec : InitializationService.IAM) {
            var m = new Menu();
            m.appId = iam;
            m.menuCode = spec.code();
            m.menuName = spec.name();
            m.path = spec.path();
            db.save(m);
            for (String route : spec.routes()) {
              Api a = apiByRoute.get(route);
              if (a == null) {
                a = new Api();
                a.appId = iam;
                a.apiCode = "A" + apiByRoute.size();
                a.apiName = route;
                a.httpMethod = route.substring(0, route.indexOf(' '));
                a.apiPath = route.substring(route.indexOf(' ') + 1);
                a.canonicalPath =
                    com.whatname.iam.common.util.RouteTemplateUtils.canonicalTemplate(a.apiPath);
                db.save(a);
                signatures.sign(a);
                apiByRoute.put(route, a);
              }
              db.save(new MenuApi(m.id, a.id));
            }
            signatures.sign(m);
            db.save(new RolePermission(full.id, m.id));
          }
          signatures.sign(full);
          var external = new Application();
          external.appCode = "OA";
          external.appName = "OA";
          db.save(external);
          signatures.sign(external);
          oa = external.id;
          var feature = new Menu();
          feature.appId = oa;
          feature.menuCode = "LEAVE";
          feature.menuName = "请假";
          feature.path = "/demo/OA/feature1";
          db.save(feature);
          menu = feature.id;
          var endpoint = new Api();
          endpoint.appId = oa;
          endpoint.apiCode = "LEAVE";
          endpoint.apiName = "请假";
          endpoint.apiPath = "/api/v1/demo/OA/feature1";
          endpoint.canonicalPath = endpoint.apiPath;
          endpoint.httpMethod = "GET";
          db.save(endpoint);
          signatures.sign(endpoint);
          db.save(new MenuApi(menu, endpoint.id));
          signatures.sign(feature);
          var r = new Role();
          r.roleCode = "EMPLOYEE";
          r.roleName = "员工";
          db.save(r);
          businessRole = r.id;
          db.save(new RolePermission(r.id, menu));
          signatures.sign(r);
          var r2 = new Role();
          r2.roleCode = "OTHER";
          r2.roleName = "另一个角色";
          db.save(r2);
          otherRole = r2.id;
          signatures.sign(r2);
          String hash = passwords.hash("Coursework123!");
          var a = account("manager", "13800000001", branch, 2, hash);
          admin = a.id;
          db.save(new UserRole(admin, full.id));
          signatures.sign(a);
          var u = account("employee", "13800000002", branch, 1, hash);
          employee = u.id;
          signatures.sign(u);
          var x = account("outside", "13800000003", other, 1, hash);
          outsider = x.id;
          signatures.sign(x);
          adminToken = "Bearer " + jwt.issue(admin, 1);
          employeeToken = "Bearer " + jwt.issue(employee, 1);
          outsiderToken = "Bearer " + jwt.issue(outsider, 1);
        });
  }

  private Account account(String username, String phone, long department, int source, String hash) {
    var u = new Account();
    u.username = username;
    u.realName = username;
    u.phone = phone;
    u.deptId = department;
    u.source = source;
    if (source == 1) {
      u.employeeNo = "E" + username;
      u.employmentType = 1;
    }
    u.passwordHash = hash;
    db.save(u);
    return u;
  }

  @Test
  @Order(1)
  void protectedEndpointsAndSelfManagedRbac() throws Exception {
    mvc.perform(get("/api/v1/users")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/v1/users").header("Authorization", employeeToken))
        .andExpect(status().isForbidden());
    mvc.perform(get("/api/v1/users").header("Authorization", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(2));
    mvc.perform(get("/api/v1/admins").header("Authorization", adminToken))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/v1/auth/me").header("Authorization", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.user.passwordHash").doesNotExist());
  }

  @Test
  @Order(2)
  void loginAndScopedTemporaryAccount() throws Exception {
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    "{\"loginType\":\"USERNAME\",\"identifier\":\"employee\",\"password\":\"Coursework123!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.token").isString());
    mvc.perform(
            post("/api/v1/auth/login")
                .contentType("application/json")
                .content(
                    "{\"loginType\":\"PHONE\",\"identifier\":\"13800000002\",\"password\":\"wrong1234\"}"))
        .andExpect(status().isUnauthorized());
    var in =
        new HashMap<String, Object>(
            Map.of(
                "username",
                "temp",
                "realName",
                "临时人员",
                "phone",
                "13800000004",
                "deptId",
                branch,
                "password",
                "Temporary123!"));
    var temp = users.temporary(adminToken, in);
    assertEquals(2, temp.source);
    in.put("deptId", other);
    in.put("username", "bad");
    in.put("phone", "13800000005");
    assertThrows(BusinessException.class, () -> users.temporary(adminToken, in));
    grants.replace(adminToken, temp.id, List.of(businessRole));
    String token = "Bearer " + jwt.issue(temp.id, 1);
    users.status(adminToken, temp.id, Map.of("status", 2));
    tx.executeWithoutResult(s -> assertTrue(db.roles(temp.id).isEmpty()));
    assertThrows(BusinessException.class, () -> access.current(token));
  }

  @Test
  @Order(3)
  void multiRoleUnionImmediateRevokeAndOaRepeat() throws Exception {
    grants.replace(adminToken, employee, List.of(businessRole, otherRole));
    mvc.perform(get("/api/v1/demo/OA/feature1").header("Authorization", employeeToken))
        .andExpect(status().isOk());
    grants.change(adminToken, employee, otherRole, false);
    mvc.perform(get("/api/v1/demo/OA/feature1").header("Authorization", employeeToken))
        .andExpect(status().isOk());
    mvc.perform(
            post("/api/v1/integrations/oa/roles")
                .header("X-Service-Key", "bad")
                .contentType("application/json")
                .content(
                    "{\"userId\":"
                        + employee
                        + ",\"roleId\":"
                        + businessRole
                        + ",\"operation\":\"REVOKE\"}"))
        .andExpect(status().isUnauthorized());
    for (int i = 0; i < 2; i++)
      mvc.perform(
              post("/api/v1/integrations/oa/roles")
                  .header("X-Service-Key", "test-oa-service-key-long-enough")
                  .contentType("application/json")
                  .content(
                      "{\"userId\":"
                          + employee
                          + ",\"roleId\":"
                          + businessRole
                          + ",\"operation\":\"REVOKE\"}"))
          .andExpect(status().isOk());
    mvc.perform(get("/api/v1/demo/OA/feature1").header("Authorization", employeeToken))
        .andExpect(status().isForbidden());
  }

  @Test
  @Order(4)
  void batchPartialSuccessAndDepartmentScope() {
    var result =
        batches.execute(
            adminToken,
            Map.of(
                "userIds",
                List.of(employee, outsider),
                "roleId",
                businessRole,
                "operation",
                "GRANT"));
    assertEquals(1L, result.get("succeeded"));
    assertThrows(
        BusinessException.class, () -> grants.replace(adminToken, outsider, List.of(businessRole)));
    assertThrows(
        BusinessException.class,
        () -> users.scope(adminToken, employee, Map.of("managementScope", "ALL")));
  }

  @Test
  @Order(4)
  void externalCheckBindsApplicationToItsOwnCredential() throws Exception {
    grants.change(adminToken, employee, businessRole, true);
    String body = "{\"method\":\"GET\",\"route\":\"/api/v1/demo/OA/feature1\"}";
    mvc.perform(
            post("/api/v1/auth/check")
                .header("Authorization", employeeToken)
                .header("X-App-Code", "OA")
                .header("X-Service-Key", "test-oa-app-key-long-enough")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.allowed").value(true));
    mvc.perform(
            post("/api/v1/auth/check")
                .header("Authorization", employeeToken)
                .header("X-App-Code", "OA")
                .header("X-Service-Key", "test-oa-service-key-long-enough")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
    // A lower-case app name must never select the HR integration credential.
    mvc.perform(
            post("/api/v1/auth/check")
                .header("Authorization", employeeToken)
                .header("X-App-Code", "hr")
                .header("X-Service-Key", "test-hr-service-key-long-enough")
                .contentType("application/json")
                .content(body))
        .andExpect(status().isUnauthorized());
  }

  @Test
  @Order(4)
  void roleAndResourceConfigurationsUseOneRbacChain() throws Exception {
    String roleBody = "{\"roleCode\":\"NEW_ROLE\",\"roleName\":\"新角色\",\"status\":1}";
    var created =
        mvc.perform(
                post("/api/v1/roles")
                    .header("Authorization", adminToken)
                    .contentType("application/json")
                    .content(roleBody))
            .andExpect(status().isOk())
            .andReturn();
    long id =
        json.readTree(created.getResponse().getContentAsString()).path("data").path("id").asLong();
    mvc.perform(
            put("/api/v1/roles/" + id + "/menus")
                .header("Authorization", adminToken)
                .contentType("application/json")
                .content("{\"menuIds\":[\"" + menu + "\"]}"))
        .andExpect(status().isOk());
    tx.executeWithoutResult(
        s -> {
          assertEquals(List.of(menu), db.menus(id));
          signatures.verify(db.get(Role.class, id));
        });
    var foreignApi =
        tx.execute(s -> db.query(Api.class, "from Api where appId=?1", iam).getFirst().id);
    assertThrows(
        BusinessException.class,
        () -> resources.replaceApis(adminToken, menu, List.of(foreignApi)));
    var duplicate =
        Map.<String, Object>of(
            "apiCode",
            "DUP",
            "apiName",
            "重复",
            "apiPath",
            "/api/v1/demo/OA/feature1",
            "httpMethod",
            "GET");
    assertThrows(BusinessException.class, () -> resources.api(adminToken, oa, null, duplicate));
  }

  @Test
  @Order(5)
  void freezeTransferAndTermination() throws Exception {
    hr.transfer(employee, branch); // Same-department duplicate must preserve current roles.
    tx.executeWithoutResult(s -> assertTrue(db.roles(employee).contains(businessRole)));
    var adminInRoot =
        tx.execute(
            s -> {
              var a = db.lock(Account.class, admin);
              a.managementScope = "ALL";
              a.deptId = root;
              signatures.sign(a);
              return a;
            });
    hr.transfer(employee, other);
    tx.executeWithoutResult(s -> assertTrue(db.roles(employee).isEmpty()));
    grants.replace(adminToken, employee, List.of(businessRole));
    hr.transfer(employee, other); // Duplicate transfer must not erase newly granted roles.
    tx.executeWithoutResult(s -> assertTrue(db.roles(employee).contains(businessRole)));
    hr.freeze(adminToken, other, true);
    mvc.perform(get("/api/v1/demo/OA/feature1").header("Authorization", employeeToken))
        .andExpect(status().isForbidden());
    hr.freeze(adminToken, other, false);
    mvc.perform(get("/api/v1/demo/OA/feature1").header("Authorization", employeeToken))
        .andExpect(status().isOk());
    hr.terminate(employee);
    assertThrows(BusinessException.class, () -> access.current(employeeToken));
    tx.executeWithoutResult(
        s -> {
          assertEquals(3, db.get(Account.class, employee).status);
          assertTrue(db.roles(employee).isEmpty());
          assertFalse(db.roles(admin).isEmpty());
        });
  }

  @Test
  @Order(6)
  void tamperingFailsAndInvalidReplacementRollsBack() {
    grants.replace(adminToken, outsider, List.of(businessRole));
    assertThrows(
        BusinessException.class,
        () -> grants.replace(adminToken, outsider, List.of(businessRole, 999999L)));
    tx.executeWithoutResult(s -> assertEquals(List.of(businessRole), db.roles(outsider)));
    tx.executeWithoutResult(
        s ->
            em.createNativeQuery("update sys_user set phone='13900000003' where id=" + outsider)
                .executeUpdate());
    assertThrows(BusinessException.class, () -> access.current(outsiderToken));
    tx.executeWithoutResult(
        s ->
            em.createNativeQuery("update sys_user set phone='13800000003' where id=" + outsider)
                .executeUpdate());
    assertNotNull(access.current(outsiderToken));
  }

  @Test
  @Order(7)
  void selfRevocationNeedsNoIndependentAdministratorAndPersistsOtherGrants() throws Exception {
    grants.replace(adminToken, admin, List.of());
    mvc.perform(get("/api/v1/users").header("Authorization", adminToken))
        .andExpect(status().isForbidden());
    tx.executeWithoutResult(s -> assertTrue(db.roles(outsider).contains(businessRole)));
  }

  @Test
  @Order(6)
  void competingSavesRemainCompleteAndSigned() throws Exception {
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var start = new java.util.concurrent.CountDownLatch(1);
      var a =
          executor.submit(
              () -> {
                start.await();
                grants.replace(adminToken, outsider, List.of(businessRole));
                return true;
              });
      var b =
          executor.submit(
              () -> {
                start.await();
                grants.replace(adminToken, outsider, List.of(businessRole, otherRole));
                return true;
              });
      start.countDown();
      assertTrue(a.get(15, java.util.concurrent.TimeUnit.SECONDS));
      assertTrue(b.get(15, java.util.concurrent.TimeUnit.SECONDS));
    }
    tx.executeWithoutResult(
        s -> {
          var ids = db.roles(outsider);
          assertTrue(
              ids.equals(List.of(businessRole)) || ids.equals(List.of(businessRole, otherRole)));
          signatures.verify(db.get(Account.class, outsider));
        });
  }

  @Test
  @Order(8)
  @org.junit.jupiter.api.condition.EnabledIfSystemProperty(
      named = "coursework.fullImport",
      matches = "true")
  void fullSourceImportAndLocalRecovery() throws Exception {
    tx.executeWithoutResult(
        s -> {
          for (String table :
              List.of(
                  "sys_operation_log",
                  "sys_data_signature",
                  "sys_user_role",
                  "sys_role_permission",
                  "sys_menu_api",
                  "sys_api",
                  "sys_menu",
                  "sys_role",
                  "sys_user",
                  "sys_dept",
                  "sys_application")) {
            if (table.equals("sys_menu"))
              em.createNativeQuery("update sys_menu set parent_id=0").executeUpdate();
            if (table.equals("sys_dept"))
              em.createNativeQuery("update sys_dept set parent_id=0,merged_into_dept_id=null")
                  .executeUpdate();
            em.createNativeQuery("delete from " + table).executeUpdate();
          }
          em.clear();
        });
    long start = System.nanoTime();
    var result =
        initialization.initialize(
            java.nio.file.Path.of("target/prepared-data.json"),
            "superadmin",
            "初始化管理者",
            "13900000000",
            "InitialDemo123!");
    assertEquals(111, result.get("departments"));
    assertEquals(10000, result.get("employees"));
    assertEquals(18, result.get("sourceApplications"));
    assertEquals(359, result.get("sourcePermissions"));
    String databaseProduct;
    try (var c = dataSource.getConnection()) {
      databaseProduct = c.getMetaData().getDatabaseProductName();
    }
    String schemaPredicate =
        databaseProduct.equals("H2") ? "table_schema='public'" : "table_schema=database()";
    tx.executeWithoutResult(
        s -> {
          assertEquals(10001L, db.query(Long.class, "select count(u) from Account u").getFirst());
          assertEquals(21L, db.query(Long.class, "select count(a) from Application a").getFirst());
          assertEquals(
              11L,
              ((Number)
                      em.createNativeQuery(
                              "select count(*) from information_schema.tables where "
                                  + schemaPredicate
                                  + " and table_type='BASE TABLE'")
                          .getSingleResult())
                  .longValue());
        });
    var superuser = accounts.findByUsername("superadmin").orElseThrow();
    String token = "Bearer " + jwt.issue(superuser.id, superuser.sessionVersion);
    mvc.perform(get("/api/v1/users").header("Authorization", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.total").value(10001));
    mvc.perform(get("/api/v1/demo/OA/feature1").header("Authorization", token))
        .andExpect(status().isOk());
    grants.replace(token, superuser.id, List.of());
    mvc.perform(get("/api/v1/users").header("Authorization", token))
        .andExpect(status().isForbidden());
    initialization.recover("superadmin", "Recovered123!");
    assertThrows(BusinessException.class, () -> access.current(token));
    var recovered = accounts.findByUsername("superadmin").orElseThrow();
    String restored = "Bearer " + jwt.issue(recovered.id, recovered.sessionVersion);
    mvc.perform(get("/api/v1/users").header("Authorization", restored)).andExpect(status().isOk());
    assertThrows(
        IllegalStateException.class,
        () ->
            initialization.initialize(
                java.nio.file.Path.of("target/prepared-data.json"),
                "again",
                "重复",
                "13900000001",
                "InitialDemo123!"));
    var report = new LinkedHashMap<String, Object>(result);
    report.put("database", databaseProduct);
    report.put("importAndRecoverySeconds", (System.nanoTime() - start) / 1_000_000_000.0);
    json.writerWithDefaultPrettyPrinter()
        .writeValue(
            java.nio.file.Path.of(
                    "target/full-import-" + databaseProduct.toLowerCase() + "-report.json")
                .toFile(),
            report);
  }
}
