package com.whatname.iam.service.system;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.whatname.iam.IamApplication;
import com.whatname.iam.dto.auth.AuthRequests;
import com.whatname.iam.service.auth.AuthService;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.SignedDataType;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.util.*;

/** 受控初始化在真实 MySQL 上导入组织、人员、资源和超级用户，并能直接登录、管理和兜底恢复。 */
@SpringBootTest(classes = IamApplication.class, properties = "spring.config.import=")
@Testcontainers
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class InitializationServiceTest {
    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>("mysql:8.0")
                    .withDatabaseName("unified_permission")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("db/schema.sql").toAbsolutePath()),
                            "/docker-entrypoint-initdb.d/01_schema.sql");

    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519");
    static final String PASSWORD = "Init2026Safe";

    static KeyPair keys(String algorithm) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("RSA")) generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String b64(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> b64(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> b64(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "init-test");
        r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "init-test");
        r.add("iam.security.data-signing.key-id", () -> "init-test");
        r.add("iam.security.data-signing.private-key", () -> b64(ED.getPrivate().getEncoded()));
        r.add(
                "iam.security.data-signing.public-keys.init-test",
                () -> b64(ED.getPublic().getEncoded()));
        byte[] hmac = new byte[32];
        Arrays.fill(hmac, (byte) 7);
    }

    @TempDir static Path dir;

    @Autowired InitializationService service;
    @Autowired AuthService auth;
    @Autowired DataSignatureService signatures;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactions;
    @Autowired MockMvc http;

    /** 与 scripts/prepare-data.py 输出结构相同的小样本：总公司员工、分公司员工及一对重名。 */
    static Path sample() throws Exception {
        Path file = dir.resolve("prepared.json");
        Files.writeString(
                file,
                """
{"tables": {
  "departments": {"records": [
    {"sourceRow": 2, "部门名称": "总公司", "部门经理": "", "上级部门": ""},
    {"sourceRow": 3, "部门名称": "财务部", "部门经理": "", "上级部门": "总公司"},
    {"sourceRow": 4, "部门名称": "道路工程分公司", "部门经理": "", "上级部门": "总公司"},
    {"sourceRow": 5, "部门名称": "道路工程分公司-财务部", "部门经理": "", "上级部门": "道路工程分公司"}
  ]},
  "employees": {"records": [
    {"sourceRow": 2, "姓名": "张三", "用户名": "SG000001", "分公司": "总公司", "部门": "财务部", "岗位": "会计", "电话号码": "13800000001"},
    {"sourceRow": 3, "姓名": "李四", "用户名": "SG000002", "分公司": "道路工程分公司", "部门": "财务部", "岗位": "出纳", "电话号码": "13800000002"},
    {"sourceRow": 4, "姓名": "张森", "用户名": "SG000003", "分公司": "道路工程分公司", "部门": "财务部", "岗位": "会计", "电话号码": "13800000003"}
  ]}
}}
""");
        return file;
    }

    String login() {
        return "Bearer "
                + auth.login(
                                new AuthRequests.Login(
                                        AuthRequests.LoginType.USERNAME, "superadmin", PASSWORD),
                                "127.0.0.1",
                                "init-test")
                        .accessToken();
    }

    @Test
    @Order(1)
    void importsOrganizationPeopleResourcesAndSuperUser() throws Exception {
        var result =
                service.initialize(
                        sample(),
                        new InitializationService.SuperUser(
                                "superadmin", "系统管理员", "13900000000", PASSWORD));
        assertEquals(4, result.get("departments"));
        assertEquals(3, result.get("employees"));

        // 分公司员工落在“分公司-部门”下；重名 zs 追加工号后六位。
        assertEquals(
                "道路工程分公司-财务部",
                db.queryForObject(
                        "SELECT d.dept_name FROM sys_user u JOIN sys_dept d ON d.id=u.dept_id"
                                + " WHERE u.employee_no='SG000002'",
                        String.class));
        assertEquals(
                List.of("ls", "zs", "zs000003"),
                db.queryForList(
                        "SELECT username FROM sys_user WHERE source=1 ORDER BY username",
                        String.class));
        // 员工密码未初始化，不能直接登录。
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_user WHERE source=1 AND password_initialized=1",
                        Integer.class));
        assertEquals(
                List.of("HR", "IAM", "OA"),
                db.queryForList(
                        "SELECT app_code FROM sys_application ORDER BY app_code", String.class));
    }

    @Test
    @Order(2)
    void superUserLogsInAndManagesThroughNormalApis() throws Exception {
        String bearer = login();
        http.perform(
                        get("/api/v1/auth/menus")
                                .param("appCode", "IAM")
                                .header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.menus.length()").value(14))
                .andExpect(jsonPath("$.data.management.adminLevel").value(1));
        http.perform(get("/api/v1/users").header("Authorization", bearer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4));

        // 一级管理员按正常授权入口把“一线员工基础权限”授予员工，权限立即可查。
        long userId =
                db.queryForObject(
                        "SELECT id FROM sys_user WHERE employee_no='SG000001'", Long.class);
        long roleId =
                db.queryForObject(
                        "SELECT id FROM sys_role WHERE role_code='BASIC_EMPLOYEE'", Long.class);
        http.perform(
                        post("/api/v1/grants/user-roles")
                                .header("Authorization", bearer)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
{"userId":"%d","roleId":"%d","sourceType":"MANUAL",
 "sourceRef":"INIT-TEST","reason":"演示授权","expectedVersion":"v1"}
"""
                                                .formatted(userId, roleId)))
                .andExpect(status().isOk());
        // 授权已落库且签名有效（查询接口会验签）；员工设密前权限计算仍为空，这是既定规则。
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",
                        Integer.class,
                        userId,
                        roleId));
        http.perform(get("/api/v1/users/{id}/roles", userId).header("Authorization", bearer))
                .andExpect(status().isOk());
    }

    @Test
    @Order(3)
    void secondInitializationIsRefusedAndRecoveryKeepsSuperUserUsable() throws Exception {
        assertThrows(
                IllegalStateException.class,
                () ->
                        service.initialize(
                                sample(),
                                new InitializationService.SuperUser(
                                        "other", "其他", "13900000009", PASSWORD)));
        // 模拟唯一一级任命被撤销后的兜底：撤销写入同样重新签名，恢复后再次可用。
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        status -> {
                            db.update("UPDATE sys_admin SET status=0");
                            db.queryForList("SELECT user_id FROM sys_admin", Long.class)
                                    .forEach(id -> signatures.sign(SignedDataType.ADMIN, id));
                        });
        String bearer = login();
        http.perform(get("/api/v1/users").header("Authorization", bearer))
                .andExpect(status().isForbidden());

        service.recover("superadmin");
        http.perform(get("/api/v1/users").header("Authorization", login()))
                .andExpect(status().isOk());
        assertEquals(
                1,
                db.queryForObject(
                        "SELECT COUNT(*) FROM sys_operation_log WHERE"
                                + " operation_type='SUPER_USER_RECOVER'",
                        Integer.class));
    }
}
