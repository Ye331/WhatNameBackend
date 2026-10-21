package com.whatname.iam.service.auth;

import com.whatname.iam.service.permission.*;
import com.whatname.iam.service.department.DepartmentCollaboration;
import com.whatname.iam.service.hr.HrService;
import com.whatname.iam.dto.hr.HrRequests;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.event.TrustedEventContext;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static com.whatname.iam.service.permission.SignedDataType.*;
import static com.whatname.iam.service.permission.ServiceRequestSignature.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** A-side integration with the exact B/D PR snapshots: production HR/OA authentication,
 * C lifecycle/grants and D signatures/audit. Fixtures only provision signed data and keys. */
@SpringBootTest(classes = {com.whatname.iam.IamApplication.class, AStage2TrustedServiceIT.TestConfig.class},
        properties = "spring.config.import=")
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
class AStage2TrustedServiceIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class TestConfig {
        @Bean Fault persistenceFault() { return new Fault(); }
    }

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("unified_permission").withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--default-time-zone=+08:00")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/01_schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/02_add_canonical_path_and_index.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/02_resource.sql");

    // RSA for the JWT bearer, Ed25519 for data-fact signing (both A/D test keys),
    // a separate Ed25519 pair for the trusted-service request signature under test.
    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519"), SVC = keys("Ed25519");
    static KeyPair keys(String algorithm) {
        try { var g = KeyPairGenerator.getInstance(algorithm); if (algorithm.equals("RSA")) g.initialize(2048); return g.generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String b64(byte[] value) { return Base64.getEncoder().encodeToString(value); }

    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> b64(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> b64(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "e2e"); r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "e2e-jwt");
        r.add("iam.security.data-signing.key-id", () -> "e2e-sign");
        r.add("iam.security.data-signing.private-key", () -> b64(ED.getPrivate().getEncoded()));
        r.add("iam.security.data-signing.public-keys.e2e-sign", () -> b64(ED.getPublic().getEncoded()));
        r.add("iam.security.data-key", () -> b64(new byte[32]));
        r.add("iam.security.phone-hmac-key", () -> b64(Arrays.copyOf(new byte[]{1},32)));
        r.add("iam.security.sms-hmac-key", () -> b64(Arrays.copyOf(new byte[]{2},32)));
    }

    /** Injects a persistence failure after a named MyBatis update statement, to prove that a
     * later business rollback cannot resurrect an already-consumed request nonce. */
    @Intercepts({@org.apache.ibatis.plugin.Signature(type = Executor.class, method = "update", args = {MappedStatement.class, Object.class}),
            @org.apache.ibatis.plugin.Signature(type = Executor.class, method = "query", args = {MappedStatement.class, Object.class, org.apache.ibatis.session.RowBounds.class, org.apache.ibatis.session.ResultHandler.class})})
    static class Fault implements Interceptor {
        volatile String point, hit, pause;
        volatile CountDownLatch ready, release;
        public Object intercept(Invocation call) throws Throwable {
            String id = ((MappedStatement) call.getArgs()[0]).getId();
            if(pause!=null && id.endsWith(pause)) {
                pause=null;ready.countDown();
                if(!release.await(15,TimeUnit.SECONDS)) throw new IllegalStateException("fixture gate timed out");
            }
            Object result = call.proceed();
            if (point != null && id.endsWith("." + point)) { hit = point; throw new IllegalStateException("fixture persistence failure"); }
            return result;
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired MockMvc http;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSignatureService signatures;
    @Autowired JwtTokenService jwt;
    @Autowired PermissionService permissions;
    @Autowired ServiceOaCaller oaCaller;
    @Autowired Fault fault;
    @Autowired DepartmentCollaboration hrCaller;
    @Autowired HrService hr;
    @Autowired ServiceRequestAuthenticator services;
    @Autowired com.whatname.iam.service.auth.AuthService auth;
    @Autowired com.whatname.iam.common.security.SensitiveDataCodec codec;

    static final AtomicLong NEXT = new AtomicLong(1000);
    static final String OA_PATH = "/api/v1/integrations/oa/decisions";
    static final String API_PATH = "/api/v1/auth/check";
    static final String USERS_PATH = "/api/v1/users";

    long b;
    String oaClient, apiClient, hrClient;
    String publicKeyB64, fingerprint;
    TransactionTemplate tx;

    long id(int n) { return b + n; }
    String sid(int n) { return Long.toString(id(n)); }
    String event() { return "OA-E2E-" + b; }
    String approval() { return "APP-E2E-" + b; }

    @BeforeEach void seed() {
        b = NEXT.getAndAdd(1000);
        fault.point = null; fault.hit = null;fault.pause=null;
        tx = new TransactionTemplate(manager);
        oaClient = "oa-e2e-" + b;
        apiClient = "api-e2e-" + b;
        publicKeyB64 = b64(SVC.getPublic().getEncoded());
        fingerprint = ServiceRequestSignature.sha256Hex(publicKeyB64.getBytes(StandardCharsets.UTF_8));

        // Organization: root(1) with a leaf(2) and a sibling root(3).
        db.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path) VALUES(?,0,?,'Fixture',?),(?,?,?,'Fixture',?),(?,0,?,'Fixture',?)",
                id(1), "D" + id(1), "/" + id(1) + "/", id(2), id(1), "D" + id(2), "/" + id(1) + "/" + id(2) + "/", id(3), "D" + id(3), "/" + id(3) + "/");
        // Users: 10 = level-1 admin (root), 11 = level-2 admin (leaf), 20 = target (root).
        for (int u : List.of(10, 11, 20))
            db.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized) VALUES(?,?,'fixture','fixture',?,'fixture',?,3,1,1)",
                    id(u), "U" + id(u), String.format("%064x", id(u)), id(u == 11 ? 2 : 1));
        db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,1,?,1),(?,2,?,1)", id(10), id(1), id(11), id(2));
        db.update("INSERT INTO sys_role(id,role_code,role_name,role_type) VALUES(?,?,'Business',1),(?,?,'Manager',2)", id(30), "R" + b, id(31), "M" + b);
        db.update("INSERT INTO sys_application(id,app_code,app_name) VALUES(?,?,'Fixture')", id(40), "E2E" + b);
        int n = 50;
        for (String code : List.of("IAM_GRANTS_WRITE", "IAM_GRANTS_VIEW", "IAM_USERS_VIEW", "IAM_ADMINS_WRITE", "IAM_ADMINS_VIEW", "IAM_GRANTS_DELEGATE", "BUSINESS_VIEW", "IAM_DEPTS_FREEZE", "IAM_RESOURCES_VIEW", "IAM_RESOURCES_WRITE", "IAM_ROLES_VIEW", "IAM_ROLES_WRITE")) {
            db.update("INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type) VALUES(?,?,0,?,'Fixture',2)", id(n), id(40), code);
            db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)", id(n == 56 ? 30 : 31), id(n++));
        }
        db.update("INSERT INTO sys_api(id,app_id,api_code,api_name,api_path,canonical_path,http_method) VALUES(?,?,'READ','Fixture','/records/{id}','/records/{}','GET')", id(60), id(40));
        db.update("INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?)", id(56), id(60));
        for (int u : List.of(10, 11)) {
            db.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)", id(u), id(31), id(10));
            db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by) VALUES(?,?,'MANUAL','bootstrap',?)", id(u), id(31), id(10));
        }
        db.update("UPDATE sys_user SET source=1,employment_type=1,employee_no=?,real_name_ciphertext=?,phone=?,hr_version=1 WHERE id=?",
                employeeNo(),codec.encrypt("张三"),codec.encrypt(phone()),id(20));
        hrClient="hr-e2e-"+b;
        db.update("INSERT INTO sys_service_identity(id,client_id,credential_hash,public_key,key_id,allowed_operations,allowed_scope,status) VALUES(?,?,?,?,?,?,'HR_SYNC',1)",
                id(72),hrClient,fingerprint,publicKeyB64,"hr-key",jsonArray("HR_EMPLOYEE","HR_TRANSFER","HR_TERMINATION","HR_DEPARTMENT","HR_MERGE"));
        // Real service identities: OA_DECISION (app-unbound) and API_AUTH (app-bound), one shared Ed25519 key.
        db.update("INSERT INTO sys_service_identity(id,client_id,credential_hash,public_key,key_id,allowed_role_ids,allowed_dept_ids,allowed_operations,allowed_scope,status) VALUES(?,?,?,?,?,?,?,?,'OA_DECISION',1)",
                id(70), oaClient, fingerprint, publicKeyB64, "oa-key", jsonArray(sid(30)), jsonArray(sid(1)), jsonArray("GRANT", "REVOKE"));
        db.update("INSERT INTO sys_service_identity(id,app_id,client_id,credential_hash,public_key,key_id,allowed_scope,status) VALUES(?,?,?,?,?,?,'API_AUTH',1)",
                id(71), id(40), apiClient, fingerprint, publicKeyB64, "api-key");

        tx.executeWithoutResult(s -> {
            signatures.sign(ORGANIZATION, 0);
            for (long d : List.of(id(1), id(2), id(3))) signatures.sign(DEPARTMENT, d);
            for (long a : List.of(id(10), id(11))) { signatures.sign(ADMIN, a); signatures.sign(ADMIN_DELEGABLE_ROLES, a); }
            for (long r : List.of(id(30), id(31))) signatures.sign(ROLE_MENUS, r);
            for (long u : List.of(id(10), id(11), id(20))) { signatures.sign(USER_IDENTITY, u); signatures.sign(USER_GRANTS, u); }
            signatures.sign(RESOURCE_APPLICATION, id(40));
            signatures.sign(RESOURCE_API, id(60));
            for (int m = 50; m <= 61; m++) { signatures.sign(RESOURCE_MENU, id(m)); signatures.sign(MENU_APIS, id(m)); }
            signatures.sign(SERVICE_IDENTITY, id(70));
            signatures.sign(SERVICE_IDENTITY, id(71));
            signatures.sign(SERVICE_IDENTITY, id(72));
        });
        // Resolve D's RBAC0 management application to this fixture's app, per the C/D integration tests.
        ReflectionTestUtils.setField((Object) AopTestUtils.getUltimateTargetObject(permissions), "managementApplication", "E2E" + b);
    }

    @AfterEach void clear() { fault.point = null; fault.hit = null;fault.pause=null;if(fault.release!=null)fault.release.countDown(); }

    String jsonArray(String... values) { try { return json.writeValueAsString(values); } catch (Exception e) { throw new IllegalStateException(e); } }


    private String phone() { return String.format("139%08d",b); }
    private String employeeNo() { return "EMP"+id(20); }
    private Map<String,Object> employeeBody(String event,long version,String action) {
        return Map.of("sourceSystem","HR","eventId",event,"eventVersion",version,"sgNo",employeeNo(),
                "realName","张三","phone",phone(),"deptCode","D"+id(1),"employeeAction",action,
                "employmentType","FORMAL","occurredAt","2026-10-19T10:00:00+08:00");
    }
    private String employeePath() { return "/api/v1/integrations/hr/employees/"+employeeNo(); }
    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder signed(
            String client,String key,String nonce,String method,String path,byte[] body) throws Exception {
        long now=System.currentTimeMillis()/1000;
        String message=ServiceRequestSignature.message(client,key,now,nonce,method,path,ServiceRequestSignature.sha256Hex(body));
        var signer=java.security.Signature.getInstance("Ed25519");signer.initSign(SVC.getPrivate());
        signer.update(message.getBytes(StandardCharsets.UTF_8));
        return request(HttpMethod.valueOf(method),path).header(CLIENT_ID_HEADER,client).header(KEY_ID_HEADER,key)
                .header(TIMESTAMP_HEADER,Long.toString(now)).header(NONCE_HEADER,nonce)
                .header(SIGNATURE_HEADER,Base64.getEncoder().encodeToString(signer.sign())).contentType("application/json").content(body);
    }
    private JsonNode hrRequest(String method,String path,Object body,int status) throws Exception {
        byte[] bytes=json.writeValueAsBytes(body);
        var result=http.perform(signed(hrClient,"hr-key","n-"+UUID.randomUUID(),method,path,bytes))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(status)).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }
    private HrRequests.Employee employee(String event) {
        return json.convertValue(employeeBody(event,2,"UPSERT"),HrRequests.Employee.class);
    }
    private TrustedEventContext context(String event) throws Exception {
        byte[] bytes=json.writeValueAsBytes(employeeBody(event,2,"UPSERT"));
        var mock=signed(hrClient,"hr-key","n-"+UUID.randomUUID(),"PUT",employeePath(),bytes).buildRequest(new org.springframework.mock.web.MockServletContext());
        var cached=new ContentCachingRequestWrapper(mock);cached.getInputStream().readAllBytes();
        return hrCaller.authenticateHr(cached,"HR_EMPLOYEE");
    }
    private void validIdentity() {
        tx.executeWithoutResult(s->{signatures.verify(USER_IDENTITY,id(20));signatures.verify(USER_GRANTS,id(20));});
    }
    @Test void employeeUsesRealHrAuthentication() throws Exception {
        hrRequest("PUT",employeePath(),employeeBody("employee-"+b,2,"UPSERT"),200);
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(2);
        validIdentity();
    }
    @Test void constructedHrContextIsRejectedBeforeEventReceipt() {
        var event="forged-"+b;
        assertThatThrownBy(()->hr.employee(new TrustedEventContext("HR",hrClient,"invented"),employeeNo(),employee(event)))
                .isInstanceOfSatisfying(com.whatname.iam.common.exception.BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?",Integer.class,event)).isZero();
    }
    @Test void missingHrIdentitySignatureIsRejectedDuringRecheck() throws Exception {
        String event="missing-signature-"+b;var ctx=context(event);
        db.update("DELETE FROM sys_data_signature WHERE data_type='SERVICE_IDENTITY' AND data_id=?",id(72));
        assertThatThrownBy(()->hr.employee(ctx,employeeNo(),employee(event)))
                .isInstanceOfSatisfying(com.whatname.iam.common.exception.BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?",Integer.class,event)).isZero();
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(1);
    }
    @Test void hrContextIsIssuedByD() throws Exception {
        assertThat(context("issued-"+b).authenticationProof()).isNotNull();
    }
    @Test void keyRevocationAfterAuthenticationRejectsHrEvent() throws Exception {
        String event="key-"+b;var ctx=context(event);
        tx.executeWithoutResult(s->{db.update("UPDATE sys_service_identity SET key_id='replaced-key' WHERE id=?",id(72));signatures.sign(SERVICE_IDENTITY,id(72));});
        assertThatThrownBy(()->hr.employee(ctx,employeeNo(),employee(event)))
                .isInstanceOfSatisfying(com.whatname.iam.common.exception.BusinessException.class,e->assertThat(e.errorCode()).isEqualTo(com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE));
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(1);
    }

    @Test void signedDepartmentSyncUpdatesExistingDepartment() throws Exception {
        var body=Map.of("sourceSystem","HR","eventId","dept-"+b,"eventVersion",2,
                "deptCode","D"+id(2),"deptName","Updated","parentDeptCode","D"+id(1),"occurredAt","2026-10-19T10:00:00+08:00");
        hrRequest("PUT","/api/v1/integrations/hr/departments/D"+id(2),body,200);
        tx.executeWithoutResult(s->{signatures.verify(DEPARTMENT,id(2));signatures.verify(ORGANIZATION,0);});
        assertThat(db.queryForObject("SELECT dept_name FROM sys_dept WHERE id=?",String.class,id(2))).isEqualTo("Updated");
    }
    private void grantPositionAndManual() {
        db.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",id(20),id(30),id(10));
        db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id,granted_by) VALUES(?,?,'DEPT_POSITION','position',?,?),(?,?,'MANUAL','manual',NULL,?)",
                id(20),id(30),id(1),id(10),id(20),id(30),id(10));
        tx.executeWithoutResult(s->signatures.sign(USER_GRANTS,id(20)));
    }
    @Test void signedTransferRevokesOnlyOldPositionSource() throws Exception {
        grantPositionAndManual();
        hrRequest("POST","/api/v1/integrations/hr/transfers",Map.of("sourceSystem","HR","eventId","move-"+b,
                "eventVersion",2,"sgNo",employeeNo(),"fromDeptCode","D"+id(1),"toDeptCode","D"+id(3),"occurredAt","2026-10-19T10:00:00+08:00"),200);
        assertThat(db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(id(3));
        assertThat(db.queryForObject("SELECT status FROM sys_user_role_source WHERE user_id=? AND source_type='DEPT_POSITION'",Integer.class,id(20))).isZero();
        assertThat(db.queryForObject("SELECT status FROM sys_user_role_source WHERE user_id=? AND source_type='MANUAL'",Integer.class,id(20))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Integer.class,id(20))).isEqualTo(1);
        validIdentity();
    }
    private void invalidOldSession(String token) {
        assertThatThrownBy(()->auth.requireCurrentSession(jwt.verifyBearer(token)))
                .isInstanceOfSatisfying(com.whatname.iam.common.exception.BusinessException.class,e->assertThat(e.errorCode().status().value()).isEqualTo(401));
    }
    @Test void signedTerminationRevokesAllSourcesAndOldSession() throws Exception {
        grantPositionAndManual();String old="Bearer "+jwt.issue(id(20),1);
        hrRequest("POST","/api/v1/integrations/hr/terminations",Map.of("sourceSystem","HR","eventId","leave-"+b,
                "eventVersion",2,"sgNo",employeeNo(),"effectiveAt","2026-10-19T10:00:00+08:00"),200);
        assertThat(db.queryForObject("SELECT status FROM sys_user WHERE id=?",Integer.class,id(20))).isEqualTo(3);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=1",Integer.class,id(20))).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Integer.class,id(20))).isZero();
        invalidOldSession(old);validIdentity();
    }
    @Test void signedMergeMovesUsersAndPreservesRoles() throws Exception {
        grantPositionAndManual();
        hrRequest("POST","/api/v1/integrations/hr/merges",Map.of("sourceSystem","HR","eventId","merge-"+b,
                "eventVersion",2,"sourceDeptCode","D"+id(1),"targetDeptCode","D"+id(3),"occurredAt","2026-10-19T10:00:00+08:00"),200);
        assertThat(db.queryForObject("SELECT merged_into_dept_id FROM sys_dept WHERE id=?",Long.class,id(1))).isEqualTo(id(3));
        assertThat(db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(id(3));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=1",Integer.class,id(20))).isEqualTo(2);
        validIdentity();
    }
    @Test void suspendResumeInvalidatesOldSession() throws Exception {
        String old="Bearer "+jwt.issue(id(20),1);
        hrRequest("PUT",employeePath(),employeeBody("suspend-"+b,2,"SUSPEND"),200);
        invalidOldSession(old);
        hrRequest("PUT",employeePath(),employeeBody("resume-"+b,3,"RESUME"),200);
        invalidOldSession(old);
        tx.executeWithoutResult(s->auth.requireCurrentSession(jwt.verifyBearer("Bearer "+jwt.issue(id(20),2))));
        validIdentity();
    }
    @Test void freezeAndUnfreezeUseRealManagementPermissions() throws Exception {
        for(String action:List.of("freeze","unfreeze")) {
            http.perform(post("/api/v1/departments/"+id(2)+"/"+action).header("Authorization","Bearer "+jwt.issue(id(10),1))
                    .contentType("application/json").content("{\"reason\":\"integration\"}")).andExpect(status().isOk());
            tx.executeWithoutResult(s->{signatures.verify(DEPARTMENT,id(2));signatures.verify(ORGANIZATION,0);});
        }
        assertThat(db.queryForObject("SELECT status FROM sys_dept WHERE id=?",Integer.class,id(2))).isEqualTo(1);
    }
    @Test void duplicateEventWithFreshNonceReplaysOnceAndStaleVersionIsRejected() throws Exception {
        var body=employeeBody("dup-"+b,2,"UPSERT");
        hrRequest("PUT",employeePath(),body,200);hrRequest("PUT",employeePath(),body,200);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?",Integer.class,"dup-"+b)).isEqualTo(1);
        hrRequest("PUT",employeePath(),employeeBody("stale-"+b,1,"UPSERT"),422);
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(2);
        validIdentity();
    }
    @Test void bodyTamperingAndDuplicateNonceAreRejected() throws Exception {
        var body=employeeBody("nonce-"+b,2,"UPSERT");byte[] bytes=json.writeValueAsBytes(body);
        String nonce="n-"+UUID.randomUUID();var bad=new LinkedHashMap<>(body);bad.put("realName","李四");
        http.perform(signed(hrClient,"hr-key",nonce,"PUT",employeePath(),bytes).content(json.writeValueAsBytes(bad))).andExpect(status().isUnauthorized());
        http.perform(signed(hrClient,"hr-key",nonce,"PUT",employeePath(),bytes)).andExpect(status().isOk());
        http.perform(signed(hrClient,"hr-key",nonce,"PUT",employeePath(),bytes)).andExpect(status().isUnauthorized());
        validIdentity();
    }
    @Test void signedOperationRestrictionIsEnforced() throws Exception {
        tx.executeWithoutResult(s->{db.update("UPDATE sys_service_identity SET allowed_operations=? WHERE id=?",jsonArray("HR_TRANSFER"),id(72));signatures.sign(SERVICE_IDENTITY,id(72));});
        hrRequest("PUT",employeePath(),employeeBody("scope-"+b,2,"UPSERT"),401);
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(1);
    }
    @Test void oaIdentityCannotAuthenticateHrRoute() throws Exception {
        byte[] body=json.writeValueAsBytes(employeeBody("cross-"+b,2,"UPSERT"));
        http.perform(signed(oaClient,"oa-key","n-"+UUID.randomUUID(),"PUT",employeePath(),body)).andExpect(status().isUnauthorized());
    }
    @Test void hrAuditFailureRollsBackFactsButKeepsNonceConsumed() throws Exception {
        String event="audit-"+b,nonce="n-"+UUID.randomUUID();byte[] body=json.writeValueAsBytes(employeeBody(event,2,"UPSERT"));
        fault.point="AuditMapper.insert";
        try {http.perform(signed(hrClient,"hr-key",nonce,"PUT",employeePath(),body)).andExpect(status().isServiceUnavailable());}
        finally {fault.point=null;}
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_service_nonce WHERE client_id=? AND nonce=?",Integer.class,hrClient,nonce)).isEqualTo(1);
        http.perform(signed(hrClient,"hr-key",nonce,"PUT",employeePath(),body)).andExpect(status().isUnauthorized());
        assertThat(db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,event)).isEqualTo("PENDING_RECOVERY");
        validIdentity();
    }
    @Test void serviceKeyRevokedBeforeEventWriteIsRejected() throws Exception {
        fault.ready=new CountDownLatch(1);fault.release=new CountDownLatch(1);
        fault.pause="ExternalEventMapper.insert";
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(()->hrRequest("PUT",employeePath(),employeeBody("wait-"+b,2,"UPSERT"),401));
            assertThat(fault.ready.await(10,TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(s->{db.update("UPDATE sys_service_identity SET key_id='revoked-while-waiting' WHERE id=?",id(72));signatures.sign(SERVICE_IDENTITY,id(72));});
            fault.release.countDown();result.get(20,TimeUnit.SECONDS);
        } finally {fault.release.countDown();executor.shutdownNow();}
        assertThat(db.queryForObject("SELECT hr_version FROM sys_user WHERE id=?",Long.class,id(20))).isEqualTo(1);
        validIdentity();
    }
    @Test void bResourceAndRoleRoutesAcceptRealAIdentity() throws Exception {
        String bearer="Bearer "+jwt.issue(id(10),1);
        for(String path:List.of("/api/v1/applications","/api/v1/roles")) {
            http.perform(get(path).header("Authorization",bearer)).andExpect(status().isOk());
            http.perform(get(path)).andExpect(status().isUnauthorized());
        }
        var response=http.perform(post("/api/v1/applications").header("Authorization",bearer).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("appCode","NEW"+b,"appName","Fixture","description","integration"))))
                .andExpect(status().isOk()).andReturn();
        long app=json.readTree(response.getResponse().getContentAsString()).path("data").path("appId").asLong();
        assertThat(app).isPositive();tx.executeWithoutResult(s->signatures.verify(RESOURCE_APPLICATION,app));
    }
    @Test void bWriteRejectsLevelTwoCallerWithFunctionPermission() throws Exception {
        http.perform(post("/api/v1/applications").header("Authorization","Bearer "+jwt.issue(id(11),1)).contentType("application/json")
                .content(json.writeValueAsBytes(Map.of("appCode","DENIED"+b,"appName","Fixture","description","integration"))))
                .andExpect(status().isForbidden());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_application WHERE app_code=?",Integer.class,"DENIED"+b)).isZero();
    }

    @Test void bWriteMustRejectSessionInvalidatedWhileWaitingForAdministrator() throws Exception {
        fault.ready=new CountDownLatch(1);fault.release=new CountDownLatch(1);
        fault.pause="AdminMapper.selectAppointments";
        String bearer="Bearer "+jwt.issue(id(10),1);
        byte[] body=json.writeValueAsBytes(Map.of("appCode","RACE"+b,"appName","Fixture","description","integration"));
        ExecutorService executor=Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(()->http.perform(post("/api/v1/applications").header("Authorization",bearer)
                    .contentType("application/json").content(body)).andReturn());
            assertThat(fault.ready.await(10,TimeUnit.SECONDS)).isTrue();
            tx.executeWithoutResult(s->{db.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?",id(10));signatures.sign(USER_IDENTITY,id(10));});
            fault.release.countDown();
            assertThat(result.get(20,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(401);
        } finally {fault.release.countDown();executor.shutdownNow();}
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_application WHERE app_code=?",Integer.class,"RACE"+b)).isZero();
    }

    @Test void bReadMustRejectSessionInvalidatedAfterTokenIssuance() throws Exception {
        String bearer="Bearer "+jwt.issue(id(10),1);
        tx.executeWithoutResult(s->{db.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?",id(10));signatures.sign(USER_IDENTITY,id(10));});
        List<Integer> statuses=new ArrayList<>();
        for(String path:List.of("/api/v1/applications","/api/v1/roles"))
            statuses.add(http.perform(get(path).header("Authorization",bearer)).andReturn().getResponse().getStatus());
        assertThat(statuses).as("old session must not read applications or roles").containsExactly(401,401);
    }
}
