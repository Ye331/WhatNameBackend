package com.whatname.iam.service.grant;

import com.whatname.iam.common.config.*;
import com.whatname.iam.common.exception.*;
import com.whatname.iam.common.security.*;
import com.whatname.iam.controller.admin.AdminController;
import com.whatname.iam.controller.grant.GrantController;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.*;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.*;
import static org.awaitility.Awaitility.await;
import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实生产 C 接点、A JWT、D 和隔离 MySQL。SQL 拦截器仅注入持久化故障。 */
@SpringBootTest(classes={com.whatname.iam.IamApplication.class,CStage2IntegrationIT.Application.class}, properties="spring.config.import=")
@AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
class CStage2IntegrationIT {
    @TestConfiguration
    static class Application {
        @Bean Failure failure() { return new Failure(); }
    }
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("unified_permission").withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--default-time-zone=+08:00")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/01_schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schema.sql")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db/02_add_canonical_path_and_index.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/02_resource.sql");
    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519");
    static KeyPair keys(String algorithm) {
        try { var generator=KeyPairGenerator.getInstance(algorithm); if (algorithm.equals("RSA")) generator.initialize(2048); return generator.generateKeyPair(); }
        catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }
    static String encoded(byte[] value) { return Base64.getEncoder().encodeToString(value); }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl); r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> encoded(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> encoded(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "c-stage2"); r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "test-jwt");
        r.add("iam.security.data-signing.key-id", () -> "test-sign");
        r.add("iam.security.data-signing.private-key", () -> encoded(ED.getPrivate().getEncoded()));
        r.add("iam.security.data-signing.public-keys.test-sign", () -> encoded(ED.getPublic().getEncoded()));
    }
    @Intercepts(@org.apache.ibatis.plugin.Signature(type=Executor.class, method="update", args={MappedStatement.class,Object.class}))
    static class Failure implements Interceptor {
        volatile String point;
        volatile String hit;
        public Object intercept(Invocation call) throws Throwable {
            String id=((MappedStatement)call.getArgs()[0]).getId();
            Object result=call.proceed();
            if (("AUDIT".equals(point) && id.endsWith("AuditMapper.insert"))
                    || ("SIGN".equals(point) && id.endsWith("SignatureMapper.save")
                    && "USER_GRANTS".equals(((Map<?,?>)call.getArgs()[1]).get("type")))
                    || ("ADMIN_SIGN".equals(point) && id.endsWith("SignatureMapper.save")
                    && "ADMIN_DELEGABLE_ROLES".equals(((Map<?,?>)call.getArgs()[1]).get("type")))) {
                hit=point;
                throw new IllegalStateException("test persistence failure");
            }
            return result;
        }
    }
    @Autowired JdbcTemplate db;
    @Autowired GrantService grants;
    @Autowired AdminService admins;
    @Autowired AdminOrganizationLifecycle organization;
    @Autowired com.whatname.iam.service.department.DepartmentCollaboration departmentAdapter;
    @Autowired com.whatname.iam.mapper.department.OrganizationWriteMapper organizationMapper;
    @Autowired com.whatname.iam.mapper.event.ExternalEventMapper eventMapper;
    @Autowired AuditService auditService;
    @Autowired DataSignatureService signatures;
    @Autowired PermissionService permissions;
    @Autowired JwtTokenService jwt;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired Failure failure;
    @Autowired BatchWorker worker;
    @Autowired BatchService batchService;
    @Autowired GrantOperations operations;
    @Autowired com.whatname.iam.mapper.grant.BatchMapper batches;
    static final AtomicLong NEXT=new AtomicLong(1000);
    long b;
    String bearer;
    TransactionTemplate tx;
    TransactionTemplate orgTx;
    long id(int offset) { return b+offset; }
    String sid(int offset) { return Long.toString(id(offset)); }

    @BeforeEach void seed() {
        b=NEXT.getAndAdd(1000); failure.point=null; failure.hit=null; tx=new TransactionTemplate(manager);
        orgTx=new TransactionTemplate(manager);
        orgTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        db.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path) VALUES(?,0,?,'Fixture','/')",id(1),"D"+b);
        db.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path) VALUES(?,?,?,'Fixture','/')",id(2),id(1),"L"+b);
        for (int u:List.of(10,11,20)) db.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized) VALUES(?,?, 'fixture','fixture',?,'fixture',?,3,1,1)",
                id(u),"U"+id(u),String.format("%064x",id(u)),id(u==11?2:1));
        db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,1,?,1),(?,2,?,1)",id(10),id(1),id(11),id(2));
        db.update("INSERT INTO sys_role(id,role_code,role_name,role_type) VALUES(?,?,'Business',1),(?,?,'Manager',2)",id(30),"R"+b,id(31),"M"+b);
        db.update("INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by) VALUES(?,?,?)",id(11),id(30),id(10));
        db.update("INSERT INTO sys_application(id,app_code,app_name) VALUES(?,?,'Fixture')",id(40),"C"+b);
        int n=50;
        for (String code:List.of("IAM_GRANTS_WRITE","IAM_GRANTS_VIEW","IAM_USERS_VIEW","IAM_ADMINS_WRITE","IAM_ADMINS_VIEW","IAM_GRANTS_DELEGATE","BUSINESS_VIEW")) {
            db.update("INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type) VALUES(?,?,0,?,'Fixture',2)",id(n),id(40),code);
            db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)",id(n==56?30:31),id(n++));
        }
        db.update("INSERT INTO sys_api(id,app_id,api_code,api_name,api_path,canonical_path,http_method) VALUES(?,?,'READ','Fixture','/records/{id}','/records/{}','GET')",id(60),id(40));
        db.update("INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?)",id(56),id(60));
        for (int u:List.of(10,11)) {
            db.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",id(u),id(31),id(10));
            db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by) VALUES(?,?,'MANUAL','bootstrap',?)",id(u),id(31),id(10));
        }
        tx.executeWithoutResult(s -> {
            signatures.sign(ORGANIZATION,0);
            for(long d:db.queryForList("SELECT id FROM sys_dept",Long.class)) signatures.sign(DEPARTMENT,d);
            for(long a:db.queryForList("SELECT user_id FROM sys_admin",Long.class)) { signatures.sign(ADMIN,a); signatures.sign(ADMIN_DELEGABLE_ROLES,a); }
            for(long r:db.queryForList("SELECT id FROM sys_role",Long.class)) signatures.sign(ROLE_MENUS,r);
            for(long u:db.queryForList("SELECT id FROM sys_user",Long.class)) signatures.sign(USER_IDENTITY,u);
            for(int u:List.of(10,11,20)) signatures.sign(USER_GRANTS,id(u));
            signatures.sign(RESOURCE_APPLICATION,id(40)); signatures.sign(RESOURCE_API,id(60));
            for(int m=50;m<=56;m++) { signatures.sign(RESOURCE_MENU,id(m)); signatures.sign(MENU_APIS,id(m)); }
        });
        org.springframework.test.util.ReflectionTestUtils.setField((Object)org.springframework.test.util.AopTestUtils.getUltimateTargetObject(permissions),"managementApplication","C"+b);
        bearer="Bearer "+jwt.issue(id(10),1);
        bind(bearer);
    }
    static void bind(String authorization) {
        var request=new MockHttpServletRequest(); if(authorization!=null) request.addHeader("Authorization",authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }
    @AfterEach void cleanup() { failure.point=null; RequestContextHolder.resetRequestAttributes(); }
    GrantRequest request() { return new GrantRequest(sid(20),sid(30),"MANUAL","C2-WORK",null,"fixture reason","v1"); }
    long count(String table) { return db.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE user_id=?",Long.class,id(20)); }
    long revision() { return db.queryForObject("SELECT grant_revision FROM sys_user WHERE id=?",Long.class,id(20)); }
    long audits() { return db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=? AND operation_type IN ('USER_ROLE_GRANT','USER_ROLE_REVOKE')",Long.class,id(20)); }
    void unchanged() { assertThat(count("sys_user_role_source")).isZero(); assertThat(count("sys_user_role")).isZero(); assertThat(revision()).isEqualTo(1); assertThat(audits()).isZero(); }
    boolean allowed() { return (boolean)permissions.check("Bearer "+jwt.issue(id(20),1),id(40),"GET","/records/{id}").get("allowed"); }

    @Test void realHttpGrantAndLastRevokeProduceVerifiableEmptySetAndDDecisions() throws Exception {
        var response=http.perform(post("/api/v1/grants/user-roles").header("Authorization",bearer)
                .contentType("application/json").content(json.writeValueAsString(request())))
                .andExpect(status().isOk()).andReturn();
        String source=json.readTree(response.getResponse().getContentAsString()).at("/data/grantSourceId").asText();
        assertThat(permissions.ownMenus("Bearer "+jwt.issue(id(20),1),"C"+b).get("menus").toString()).contains("BUSINESS_VIEW");
        assertThat(allowed()).isTrue();
        var result=grants.revoke(new RevokeRequest(sid(20),sid(30),source,"finished","v2"));
        assertThat(result.roleEffective()).isFalse(); assertThat(allowed()).isFalse();
        assertThat((List<?>)permissions.ownMenus("Bearer "+jwt.issue(id(20),1),"C"+b).get("menus")).isEmpty();
        assertThat(revision()).isEqualTo(3); assertThat(count("sys_user_role")).isZero(); assertThat(audits()).isEqualTo(2);
        tx.executeWithoutResult(s -> { signatures.verify(USER_IDENTITY,id(20)); signatures.verify(USER_GRANTS,id(20)); });
        String evidence=db.queryForObject("SELECT evidence FROM sys_operation_log WHERE target_id=? AND operation_type='USER_ROLE_GRANT'",String.class,id(20));
        var retained=json.readTree(evidence);
        assertThat(retained.at("/reason").asText()).isEqualTo("fixture reason");
        assertThat(retained.at("/authorization/permission").asText()).isEqualTo("IAM_GRANTS_WRITE");
        assertThat(retained.at("/authorization/resources").toString()).contains("IAM_GRANTS_WRITE");
        assertThat(evidence).contains("C2-WORK","HTTP_SESSION").doesNotContain(bearer,jwt.verifyBearer(bearer).jti(),"fixture-password");
    }
    @ParameterizedTest @ValueSource(strings={"missing","malformed","expired","old","logout","function","scope","signature"})
    void deniedInputsNeverWrite(String kind) {
        switch(kind) {
            case "missing" -> bind(null);
            case "malformed" -> bind("Bearer bad");
            case "expired" -> bind("Bearer "+new JwtTokenService(encoded(RSA.getPrivate().getEncoded()),encoded(RSA.getPublic().getEncoded()),
                    "c-stage2","iam","test-jwt",Clock.fixed(Instant.now().minusSeconds(7200),ZoneOffset.UTC)).issue(id(10),1));
            case "old" -> bind("Bearer "+jwt.issue(id(10),2));
            case "logout" -> db.update("INSERT INTO sys_session_invalidation(user_id,jti,expires_at,reason) VALUES(?,?,DATE_ADD(NOW(),INTERVAL 1 HOUR),'LOGOUT')",id(10),jwt.verifyBearer(bearer).jti());
            case "function" -> { db.update("UPDATE sys_menu SET status=0 WHERE id=?",id(50)); tx.executeWithoutResult(s -> signatures.sign(RESOURCE_MENU,id(50))); }
            case "scope" -> bind("Bearer "+jwt.issue(id(11),1));
            case "signature" -> db.update("UPDATE sys_user SET grant_revision=2 WHERE id=?",id(10));
        }
        var expected=kind.equals("function")?PERMISSION_DENIED:kind.equals("scope")?RESOURCE_NOT_FOUND:kind.equals("signature")?INTEGRITY_FAILURE:INVALID_TOKEN;
        assertThatThrownBy(() -> grants.grant(request())).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(expected));
        unchanged();
    }
    @ParameterizedTest @ValueSource(strings={"SIGN","AUDIT"})
    void secondSignatureOrInsertedAuditFailureRollsBackEverything(String point) {
        var before=db.queryForList("SELECT data_type,signature FROM sys_data_signature WHERE data_id=? ORDER BY data_type",id(20));
        failure.point=point;
        assertThatThrownBy(() -> grants.grant(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(point.equals("SIGN")?AUTH_STATE_UNAVAILABLE:AUDIT_UNAVAILABLE));
        unchanged();
        assertThat(db.queryForList("SELECT data_type,signature FROM sys_data_signature WHERE data_id=? ORDER BY data_type",id(20))).isEqualTo(before);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void existingRepeatableReadTransactionCannotSupplyStaleAuthority(boolean readOnly) {
        tx.setReadOnly(readOnly);
        tx.executeWithoutResult(s -> {
            db.queryForObject("SELECT COUNT(*) FROM sys_menu",Long.class);
            assertThatThrownBy(() -> grants.grant(request())).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
            s.setRollbackOnly();
        });
        unchanged();
    }

    @Test void batchSubmissionCannotJoinAnExistingTransaction() {
        tx.executeWithoutResult(s -> {
            var input=new com.whatname.iam.dto.grant.BatchDtos.Request("GRANT",sid(30),
                    List.of(new com.whatname.iam.dto.grant.BatchDtos.Target(sid(20),"MANUAL","work",null,null)),"batch",false);
            assertThatThrownBy(() -> batchService.submit("old-tx",input)).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
            s.setRollbackOnly();
        });
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_grant_batch WHERE actor_user_id=?",Long.class,id(10))).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"USER_GRANTS","ADMIN","ADMIN_DELEGABLE_ROLES","ROLE_MENUS","ORGANIZATION"})
    void missingSignatureIsNeverRepairedByAWrite(String type) {
        long target=switch(type) { case "USER_GRANTS" -> id(20); case "ROLE_MENUS" -> id(30); case "ORGANIZATION" -> 0; default -> id(10); };
        db.update("DELETE FROM sys_data_signature WHERE data_type=? AND data_id=?",type,target);
        assertThatThrownBy(() -> grants.grant(request())).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(INTEGRITY_FAILURE));
        unchanged();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_type=? AND data_id=?",Long.class,type,target)).isZero();
    }

    @Test void selfRevokeRetainsPreChangeFunctionEvidenceAndDeniesNextWrite() throws Exception {
        // 初始化夹具把功能菜单放在普通角色中，避免依赖尚未确定的管理角色等级政策。
        db.update("UPDATE sys_role SET role_type=1 WHERE id=?",id(31));
        tx.executeWithoutResult(s -> signatures.sign(ROLE_MENUS,id(31)));
        long source=db.queryForObject("SELECT id FROM sys_user_role_source WHERE user_id=?",Long.class,id(10));
        grants.revoke(new RevokeRequest(sid(10),sid(31),Long.toString(source),"self revoke","v1"));
        assertThatThrownBy(() -> grants.grant(request())).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(PERMISSION_DENIED));
        var retained=json.readTree(db.queryForObject("SELECT evidence FROM sys_operation_log WHERE target_id=? AND operation_type='USER_ROLE_REVOKE'",String.class,id(10)));
        assertThat(retained.at("/authorization/grants/facts/roles/0").asText()).isEqualTo(sid(31));
        assertThat(retained.at("/after/roles").size()).isZero();
        Map<String,Object> evidence=json.convertValue(retained,new com.fasterxml.jackson.core.type.TypeReference<>() {});
        @SuppressWarnings("unchecked") var seal=(Map<String,Object>)evidence.remove("seal");
        tx.executeWithoutResult(s -> assertThat(signatures.verifySeal(evidence,seal)).isTrue());
    }

    @Test void administrativeAuditFailureRollsBackAppointmentAndBothSignatures() {
        failure.point="AUDIT";
        assertThatThrownBy(() -> admins.appoint(new com.whatname.iam.dto.admin.AdminDtos.AppointmentRequest(sid(20),2,sid(1),"appoint")))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUDIT_UNAVAILABLE));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_admin WHERE user_id=?",Long.class,id(20))).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_id=? AND data_type IN ('ADMIN','ADMIN_DELEGABLE_ROLES')",Long.class,id(20))).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?",Long.class,id(20))).isZero();
    }

    @Test void persistedBatchRunsWithoutRequestAndRecordsItsItem() {
        long batch=seedBatch();
        RequestContextHolder.resetRequestAttributes();
        worker.run(batch);
        assertThat(batches.items(batch).getFirst().processingStatus()).isEqualTo("APPLIED");
        assertThat(revision()).isEqualTo(2);
        String evidence=db.queryForObject("SELECT evidence FROM sys_operation_log WHERE target_id=? AND operation_type='USER_ROLE_GRANT'",String.class,id(20));
        assertThat(evidence).contains("PERSISTED_BATCH","batchId","itemId");
        tx.executeWithoutResult(s -> {
            assertThatThrownBy(() -> operations.grant(id(10),request())).isInstanceOfSatisfying(BusinessException.class,
                    e -> assertThat(e.errorCode()).isEqualTo(INVALID_TOKEN));
            s.setRollbackOnly();
        });
        assertThat(audits()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings={"account","function","appointment","AUDIT"})
    void persistedBatchRechecksCurrentAuthorityAndRollsBackItem(String kind) {
        long batch=seedBatch();
        switch(kind) {
            case "account" -> { db.update("UPDATE sys_user SET status=2 WHERE id=?",id(10)); tx.executeWithoutResult(s -> signatures.sign(USER_IDENTITY,id(10))); }
            case "function" -> { db.update("UPDATE sys_menu SET status=0 WHERE id=?",id(50)); tx.executeWithoutResult(s -> signatures.sign(RESOURCE_MENU,id(50))); }
            case "appointment" -> { db.update("UPDATE sys_admin SET status=0 WHERE user_id=?",id(10)); tx.executeWithoutResult(s -> signatures.sign(ADMIN,id(10))); }
            case "AUDIT" -> failure.point="AUDIT";
        }
        RequestContextHolder.resetRequestAttributes(); worker.run(batch);
        var item=batches.items(batch).getFirst();
        assertThat(item.processingStatus()).isEqualTo("FAILED");
        assertThat(item.resultCode()).isEqualTo(kind.equals("AUDIT")?"AUDIT_UNAVAILABLE":"PERMISSION_DENIED");
        unchanged();
    }
    long seedBatch() {
        return tx.execute(s -> {
            batches.insertBatch(id(10),"C2-BATCH","0".repeat(64),"GRANT",id(30),"batch fixture",1);
            var batch=batches.byKey(id(10),"C2-BATCH");
            batches.insertItems(List.of(new com.whatname.iam.entity.grant.BatchData.Item(null,batch.id(),id(20),
                    "MANUAL","batch-work",null,null,"PENDING",null,null,0)));
            return batch.id();
        });
    }

    @Test void appointmentAndRecursiveDelegationSignEveryChangedAdmin() {
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?",id(2),id(20));
        tx.executeWithoutResult(s -> signatures.sign(USER_IDENTITY,id(20)));
        admins.appoint(new com.whatname.iam.dto.admin.AdminDtos.AppointmentRequest(sid(20),3,sid(2),"appoint"));
        bind("Bearer "+jwt.issue(id(11),1));
        admins.replaceDelegableRoles(sid(20),new com.whatname.iam.dto.admin.AdminDtos.DelegationRequest(List.of(sid(30)),"v1","delegate"));
        bind(bearer);
        admins.replaceDelegableRoles(sid(11),new com.whatname.iam.dto.admin.AdminDtos.DelegationRequest(List.of(),"v1","shrink"));
        assertThat(admins.delegableRoles(sid(20)).roleIds()).isEmpty();
        tx.executeWithoutResult(s -> {
            for(int u:List.of(11,20)) { signatures.verify(ADMIN,id(u)); signatures.verify(ADMIN_DELEGABLE_ROLES,id(u)); }
        });
        String evidence=db.queryForObject("SELECT evidence FROM sys_operation_log WHERE target_id=? AND operation_type='ADMIN_DELEGATE'",String.class,id(11));
        assertThat(evidence).contains(sid(11),sid(20),"shrink");
    }

    // These are internal organization transactions, not successful authenticated HR requests.
    void prepareOrganization() {
        departmentAdapter.prepareOrganizationChange(new com.whatname.iam.service.event.TrustedEventContext("HR","fixture-client","fixture-request"),"org-"+b);
    }
    void addChild() {
        db.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path) VALUES(?,?,?,'Child','/')",id(3),id(2),"NEW"+b);
    }
    void signOrganization() {
        signatures.sign(ORGANIZATION,0);
        for (long d:db.queryForList("SELECT id FROM sys_dept",Long.class)) signatures.sign(DEPARTMENT,d);
        for (long u:db.queryForList("SELECT id FROM sys_user",Long.class)) signatures.sign(USER_IDENTITY,u);
    }
    @Test void organizationExpansionRestrictsAppointmentPreservesGrantsAndRequiresExplicitReappointment() {
        var sources=db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",id(11));
        orgTx.executeWithoutResult(s -> {
            prepareOrganization(); addChild();
            departmentAdapter.organizationChanged(Set.of(id(3)),List.of()); signOrganization();
        });
        assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isZero();
        assertThat(db.queryForObject("SELECT dept_id FROM sys_admin WHERE user_id=?",Long.class,id(11))).isEqualTo(id(2));
        assertThat(db.queryForObject("SELECT delegable_revision FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isEqualTo(2);
        assertThat(db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",id(11))).isEqualTo(sources);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_admin_delegable_role WHERE admin_user_id=?",Long.class,id(11))).isZero();
        tx.executeWithoutResult(s -> { signatures.verify(ADMIN,id(11)); signatures.verify(ADMIN_DELEGABLE_ROLES,id(11)); });
        String evidence=db.queryForObject("SELECT evidence FROM sys_operation_log WHERE target_id=? AND operation_type='ADMIN_ORG_REVIEW'",String.class,id(11));
        assertThat(evidence).contains("org-"+b,"C_ORGANIZATION_REVIEW_V1","fixture-client");
        bind("Bearer "+jwt.issue(id(11),1));
        assertThat(admins.myScope().appointed()).isFalse();
        assertThatThrownBy(() -> grants.grant(request())).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.errorCode()).isEqualTo(PERMISSION_DENIED));
        bind(bearer);
        admins.appoint(new com.whatname.iam.dto.admin.AdminDtos.AppointmentRequest(sid(11),2,sid(2),"reviewed expanded scope"));
        assertThat(admins.delegableRoles(sid(11)).roleIds()).isEmpty();
        assertThat(db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",id(11))).isEqualTo(sources);
    }
    @ParameterizedTest @ValueSource(strings={"AUDIT","ADMIN_SIGN","final-signature","missing-prepare"})
    void organizationFailureRollsBackTreeAppointmentListsAndEvidence(String fault) {
        assertThatThrownBy(() -> orgTx.executeWithoutResult(s -> {
            if(!fault.equals("missing-prepare")) prepareOrganization();
            addChild();
            if(Set.of("AUDIT","ADMIN_SIGN").contains(fault)) failure.point=fault;
            organization.organizationChanged(Set.of(id(3)),List.of());
            if(!fault.equals("final-signature")) signOrganization();
        })).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(
                fault.equals("AUDIT") ? AUDIT_UNAVAILABLE : fault.equals("final-signature") ? INTEGRITY_FAILURE : AUTH_STATE_UNAVAILABLE));
        if(Set.of("AUDIT","ADMIN_SIGN").contains(fault)) assertThat(failure.hit).isEqualTo(fault);
        failure.point=null;
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_dept WHERE id=?",Long.class,id(3))).isZero();
        assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT delegable_revision FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_admin_delegable_role WHERE admin_user_id=?",Long.class,id(11))).isEqualTo(1);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",Long.class,"org-"+b)).isZero();
        tx.executeWithoutResult(s -> { signatures.verify(ORGANIZATION,0); signatures.verify(ADMIN,id(11)); signatures.verify(ADMIN_DELEGABLE_ROLES,id(11)); });
    }

    @Test void organizationMovedAdministratorClearsRecursiveDelegationButRetainsIndependentRole() {
        db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,3,?,1)",id(20),id(2));
        db.update("INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by) VALUES(?,?,?),(?,?,?)",
                id(20),id(30),id(11),id(20),id(31),id(10));
        tx.executeWithoutResult(s -> { signatures.sign(ADMIN,id(20)); signatures.sign(ADMIN_DELEGABLE_ROLES,id(20)); });
        var sources=db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id IN (?,?)",id(11),id(20));
        orgTx.executeWithoutResult(s -> {
            prepareOrganization();
            db.update("UPDATE sys_user SET dept_id=? WHERE id=?",id(1),id(11));
            organization.organizationChanged(Set.of(id(1),id(2)),List.of(Map.of("id",id(11),"deptId",id(2))));
            signOrganization();
        });
        assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isZero();
        assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(20))).isEqualTo(1);
        assertThat(db.queryForList("SELECT role_id FROM sys_admin_delegable_role WHERE admin_user_id=?",Long.class,id(20))).containsExactly(id(31));
        for(int u:List.of(11,20)) assertThat(db.queryForObject("SELECT delegable_revision FROM sys_admin WHERE user_id=?",Integer.class,id(u))).isEqualTo(2);
        assertThat(db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id IN (?,?)",id(11),id(20))).isEqualTo(sources);
        tx.executeWithoutResult(s -> {
            for(int u:List.of(11,20)) { signatures.verify(ADMIN,id(u)); signatures.verify(ADMIN_DELEGABLE_ROLES,id(u)); }
        });
    }

    @ParameterizedTest @ValueSource(strings={"missing-callback","duplicate-callback","undeclared-user","duplicate-prepare","changed-after-review"})
    void organizationProtocolRejectsIncompleteOrMismatchedCalls(String fault) {
        assertThatThrownBy(() -> orgTx.executeWithoutResult(s -> {
            prepareOrganization();
            if(fault.equals("duplicate-prepare")) prepareOrganization();
            if(fault.equals("missing-callback")) return;
            if(fault.equals("undeclared-user")) db.update("UPDATE sys_user SET dept_id=? WHERE id=?",id(1),id(11));
            organization.organizationChanged(Set.of(id(2)),List.of());
            if(fault.equals("duplicate-callback")) organization.organizationChanged(Set.of(id(2)),List.of());
            if(fault.equals("changed-after-review")) addChild();
            signOrganization();
        })).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
        assertThat(db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,id(11))).isEqualTo(id(2));
        // The rejected transaction must not leak its preparation into a subsequent transaction.
        orgTx.executeWithoutResult(s -> { prepareOrganization(); organization.organizationChanged(Set.of(id(2)),List.of()); });
    }

    @Test void organizationPreparationIsSuspendedWithTransactionAndRequiresSerializableBoundary() {
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> prepareOrganization()))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
        var inner=new TransactionTemplate(manager);
        inner.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        inner.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        orgTx.executeWithoutResult(s -> {
            prepareOrganization();
            assertThatThrownBy(() -> inner.executeWithoutResult(n -> organization.organizationChanged(Set.of(id(2)),List.of())))
                    .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
            organization.organizationChanged(Set.of(id(2)),List.of());
        });
    }

    @Test void organizationMissingSignatureFailsBeforeMutationAndIsNotRepaired() {
        db.update("DELETE FROM sys_data_signature WHERE data_type='ADMIN' AND data_id=?",id(11));
        assertThatThrownBy(() -> orgTx.executeWithoutResult(s -> { prepareOrganization(); addChild(); }))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(INTEGRITY_FAILURE));
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_dept WHERE id=?",Long.class,id(3))).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_type='ADMIN' AND data_id=?",Long.class,id(11))).isZero();
    }

    /** Fixture ONLY supplies unavailable A/D service authentication and A's final signing adapter.
     * A event state machine, department service, production C adapter and D storage are real. */
    com.whatname.iam.service.department.DepartmentService organizationServiceFixture(boolean failFinalAudit) {
        var port=new com.whatname.iam.service.department.DepartmentCollaboration() {
            public Manager authenticateManager(jakarta.servlet.http.HttpServletRequest r,String p) { throw new UnsupportedOperationException(); }
            public com.whatname.iam.service.event.TrustedEventContext authenticateHr(jakarta.servlet.http.HttpServletRequest r,String t) { throw new UnsupportedOperationException(); }
            public void verifyTrustedEvent(com.whatname.iam.service.event.TrustedEventContext c,String t) {
                if(!"fixture-client".equals(c.clientId())) throw new BusinessException(INVALID_SERVICE);
            }
            public void authorizeRecovery(com.whatname.iam.service.event.TrustedEventContext c,String t) { throw new BusinessException(INVALID_SERVICE); }
            public Set<Long> scope(Manager a,String p,List<com.whatname.iam.entity.department.Department> d) { throw new UnsupportedOperationException(); }
            public void verifyOrganization(List<com.whatname.iam.entity.department.Department> d) {
                signatures.verify(ORGANIZATION,0);
                d.forEach(v -> signatures.verify(DEPARTMENT,v.getId()));
            }
            public void verifyUsers(List<Map<String,Object>> users) {
                users.forEach(u -> signatures.verify(USER_IDENTITY,((Number)u.get("id")).longValue()));
            }
            public void prepareOrganizationChange(com.whatname.iam.service.event.TrustedEventContext a,String event) {
                departmentAdapter.prepareOrganizationChange(a,event);
            }
            public void organizationChanged(Set<Long> ids,List<Map<String,Object>> users) { departmentAdapter.organizationChanged(ids,users); }
            public void signAndAudit(String op,Object a,Map<String,Object> evidence) {
                signOrganization();
                auditService.change(op,null,"DEPT",id(2),"org-"+b,Map.of("fixtureOrganizationAdapter",true));
                if(failFinalAudit) throw new BusinessException(AUDIT_UNAVAILABLE);
            }
        };
        byte[] key=new byte[32], hmac=new byte[32]; new SecureRandom().nextBytes(key); new SecureRandom().nextBytes(hmac);
        var events=new com.whatname.iam.service.event.ExternalEventService(eventMapper,
                new SensitiveDataCodec(encoded(key),encoded(hmac)),port,json,manager);
        return new com.whatname.iam.service.department.DepartmentService(organizationMapper,port,events);
    }
    void canonicalFixturePaths() {
        var rows=db.queryForList("SELECT id,parent_id FROM sys_dept");
        var parents=new HashMap<Long,Long>();
        rows.forEach(r -> parents.put(((Number)r.get("id")).longValue(),((Number)r.get("parent_id")).longValue()));
        for(long d:parents.keySet()) {
            var chain=new ArrayList<String>();
            for(long cur=d;cur!=0;cur=parents.get(cur)) chain.addFirst(Long.toString(cur));
            db.update("UPDATE sys_dept SET dept_path=? WHERE id=?","/"+String.join("/",chain)+"/",d);
        }
        tx.executeWithoutResult(s -> signOrganization());
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void organizationAMergeAppliesReviewAndEventAtomicallyOrRollsEverythingBack(boolean failFinalAudit) {
        canonicalFixturePaths();
        var service=organizationServiceFixture(failFinalAudit);
        var actor=new com.whatname.iam.service.event.TrustedEventContext("HR","fixture-client","fixture-request");
        var command=new com.whatname.iam.dto.department.DepartmentRequests.Merge("HR","org-"+b,1L,"L"+b,"D"+b,OffsetDateTime.now());
        var sources=db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",id(11));
        if(failFinalAudit) {
            assertThatThrownBy(() -> service.merge(actor,command)).isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUDIT_UNAVAILABLE));
            assertThat(db.queryForObject("SELECT merged_into_dept_id FROM sys_dept WHERE id=?",Long.class,id(2))).isNull();
            assertThat(db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,id(11))).isEqualTo(id(2));
            assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isEqualTo(1);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",Long.class,"org-"+b)).isZero();
            assertThat(eventMapper.lock("HR","org-"+b).get("status")).isEqualTo("PENDING_RECOVERY");
        } else {
            assertThat(service.merge(actor,command).get("processingStatus")).isEqualTo("APPLIED");
            assertThat(db.queryForObject("SELECT merged_into_dept_id FROM sys_dept WHERE id=?",Long.class,id(2))).isEqualTo(id(1));
            assertThat(db.queryForObject("SELECT dept_id FROM sys_admin WHERE user_id=?",Long.class,id(11))).isEqualTo(id(2));
            assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isZero();
            assertThat(service.merge(actor,command).get("processingStatus")).isEqualTo("APPLIED");
            assertThat(db.queryForObject("SELECT delegable_revision FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isEqualTo(2);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=? AND operation_type='ADMIN_ORG_REVIEW'",Long.class,"org-"+b)).isEqualTo(1);
        }
        assertThat(db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",id(11))).isEqualTo(sources);
        tx.executeWithoutResult(s -> { signatures.verify(ORGANIZATION,0); signatures.verify(USER_IDENTITY,id(11)); signatures.verify(ADMIN,id(11)); signatures.verify(ADMIN_DELEGABLE_ROLES,id(11)); });
    }

    @Test void organizationASyncNewChildRequiresReviewAndProductionStillRejectsUntrustedHr() {
        canonicalFixturePaths();
        var actor=new com.whatname.iam.service.event.TrustedEventContext("HR","fixture-client","fixture-request");
        var command=new com.whatname.iam.dto.department.DepartmentRequests.Sync("HR","org-"+b,1L,"NEW"+b,"Fixture child","L"+b,null,OffsetDateTime.now());
        assertThatThrownBy(() -> departmentAdapter.verifyTrustedEvent(actor,"HR_DEPARTMENT"))
                .isInstanceOfSatisfying(BusinessException.class,e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
        assertThat(organizationServiceFixture(false).sync(actor,command.deptCode(),command).get("processingStatus")).isEqualTo("APPLIED");
        assertThat(db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,id(11))).isZero();
    }

    @Test void organizationWaitingGrantRechecksAppointmentAfterReviewCommits() throws Exception {
        var ready=new CountDownLatch(1); var release=new CountDownLatch(1);
        String lowerBearer="Bearer "+jwt.issue(id(11),1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var change=pool.submit(() -> orgTx.executeWithoutResult(s -> {
                prepareOrganization(); addChild();
                organization.organizationChanged(Set.of(id(3)),List.of()); signOrganization(); ready.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            try {
                assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
                var attempt=pool.submit(() -> {
                    bind(lowerBearer);
                    try { grants.grant(new GrantRequest(sid(11),sid(30),"MANUAL","review-race",null,"test","v1")); return "ALLOWED"; }
                    catch(BusinessException e) { return e.errorCode().name(); }
                    finally { RequestContextHolder.resetRequestAttributes(); }
                });
                var root=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() ->
                        root.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0);
                release.countDown(); change.get(15,TimeUnit.SECONDS);
                assertThat(attempt.get(15,TimeUnit.SECONDS)).isEqualTo("PERMISSION_DENIED");
            } finally { release.countDown(); }
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND role_id=?",Long.class,id(11),id(30))).isZero();
    }

    @ParameterizedTest @ValueSource(strings={"appointment","function","resource","session","logout"})
    void waitsForRealDatabaseLockThenRejectsChangedAuthority(String kind) throws Exception {
        var locked=new CountDownLatch(1); var release=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var change=pool.submit(() -> tx.executeWithoutResult(s -> {
                switch(kind) {
                    case "appointment" -> db.queryForList("SELECT user_id FROM sys_admin WHERE user_id=? FOR UPDATE",id(10));
                    case "function", "resource" -> db.queryForList("SELECT id FROM sys_menu WHERE id=? FOR UPDATE",id(50));
                    default -> db.queryForList("SELECT id FROM sys_user WHERE id=? FOR UPDATE",id(10));
                }
                locked.countDown();
                try { if(!release.await(15,TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                switch(kind) {
                    case "appointment" -> { db.update("UPDATE sys_admin SET status=0 WHERE user_id=?",id(10)); signatures.sign(ADMIN,id(10)); }
                    case "function" -> { db.update("DELETE FROM sys_role_permission WHERE role_id=? AND menu_id=?",id(31),id(50)); signatures.sign(ROLE_MENUS,id(31)); }
                    case "resource" -> { db.update("UPDATE sys_menu SET status=0 WHERE id=?",id(50)); signatures.sign(RESOURCE_MENU,id(50)); }
                    case "session" -> { db.update("UPDATE sys_user SET session_version=2 WHERE id=?",id(10)); signatures.sign(USER_IDENTITY,id(10)); }
                    case "logout" -> db.update("INSERT INTO sys_session_invalidation(user_id,jti,expires_at,reason) VALUES(?,?,DATE_ADD(NOW(),INTERVAL 1 HOUR),'LOGOUT')",id(10),jwt.verifyBearer(bearer).jti());
                }
            }));
            assertThat(locked.await(10,TimeUnit.SECONDS)).isTrue();
            var attempt=pool.submit(() -> {
                bind(bearer);
                try { grants.grant(request()); return "ALLOWED"; }
                catch(BusinessException e) { return e.errorCode().name(); }
                finally { RequestContextHolder.resetRequestAttributes(); }
            });
            try {
                var root=new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(MYSQL.getJdbcUrl(),"root",MYSQL.getPassword()));
                await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(20)).until(() ->
                        root.queryForObject("SELECT COUNT(*) FROM performance_schema.data_lock_waits",Long.class)>0);
            } finally { release.countDown(); }
            change.get(15,TimeUnit.SECONDS);
            assertThat(attempt.get(15,TimeUnit.SECONDS)).isEqualTo(Set.of("session","logout").contains(kind)?"INVALID_TOKEN":"PERMISSION_DENIED");
        }
        unchanged();
    }
}
