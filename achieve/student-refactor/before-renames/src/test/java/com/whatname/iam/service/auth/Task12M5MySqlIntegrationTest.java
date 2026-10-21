package com.whatname.iam.service.auth;



import com.fasterxml.jackson.databind.*;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.dto.admin.AdminDtos;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import com.whatname.iam.support.TestDatabase;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real A + C services, MySQL and HTTP. ONLY D is replaced, exclusively in src/test.
 * Never runs an existing pending batch, resets schema, deletes users, or logs JWT/PII. */
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class Task12M5MySqlIntegrationTest {
    final String prefix="T12-"+UUID.randomUUID().toString().substring(0,8)+"-";
    final AtomicInteger seq=new AtomicInteger();
    final List<Long> accountIds=new ArrayList<>(),departmentIds=new ArrayList<>();
    final List<String> evidence=new CopyOnWriteArrayList<>();
    ConfigurableApplicationContext app;
    JdbcTemplate db; UserAccountMapper users; SensitiveDataCodec codec; JwtTokenService jwt;
    TestD data; CIntegrity integrity; ObjectMapper json; PlatformTransactionManager manager;
    long root,branch,child,outside,merged; UserAccount actor,target,other,superior;
    String bearer; Map<String,Object> settings; List<Map<String,Object>> original;

    @TestConfiguration(proxyBeanMethods=false)
    static class Wiring {
        @Bean @Primary TestD task12Data(JdbcTemplate db) { return new TestD(db); }
        @Bean @Primary CIntegrity task12CIntegrity() { return new CIntegrity(); }
        @Bean @Primary AdminPorts.Identity task12CIdentity(TestD data) { return () -> data.cActor.get(); }
        @Bean @Primary AdminPorts.Functions task12CFunctions(TestD data) { return data::requireFunction; }
        @Bean @Primary AdminPorts.Audit task12CAudit(TestD data) {
            return change -> data.audit(change.operation(),change.actorId(),change.targetId(),change.reason());
        }
    }
    static class CIntegrity implements AdminPorts.Integrity {
        final AtomicInteger calls=new AtomicInteger(); volatile boolean fail;
        public void verify(AdminState state) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive()); calls.incrementAndGet();
            assertFalse(state.users().isEmpty());
            if(fail) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
        public void sign(AdminState state,Set<Long> changed) { if(fail) throw new BusinessException(ErrorCode.INTEGRITY_FAILURE); }
    }
    /** Transaction-bound TEST fingerprints, not Ed25519 and not a production security provider. */
    static class TestD implements ManagementDataSecurity {
        final JdbcTemplate db; final ThreadLocal<Long> cActor=ThreadLocal.withInitial(()->0L);
        volatile String fault="";
        TestD(JdbcTemplate db) { this.db=db; }
        void stage(String name,ErrorCode code) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if(fault.equals(name)) throw new BusinessException(code);
        }
        public void requireFunction(long actor,String permission) { stage("function",ErrorCode.PERMISSION_DENIED); }
        public void verifyIdentity(UserAccount user) {
            stage("verify",ErrorCode.INTEGRITY_FAILURE);
            if(!fingerprint(user).equals(db.queryForObject("SELECT signature FROM sys_data_signature WHERE data_type='TASK12_USER' AND data_id=?",String.class,user.id)))
                throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
        void seed(UserAccount user) {
            db.update("INSERT INTO sys_data_signature(data_type,data_id,signature,key_id) VALUES('TASK12_USER',?,?,'TASK12_FAKE') ON DUPLICATE KEY UPDATE signature=VALUES(signature)",user.id,fingerprint(user));
        }
        public void signIdentity(UserAccount user) { seed(user); stage("sign",ErrorCode.INTEGRITY_FAILURE); }
        public void audit(String op,Long actor,Long target,String reason) {
            db.update("INSERT INTO sys_operation_log(user_id,request_id,source_system,operation_type,target_id,operation_desc,status) VALUES(?,?,'TASK12_TEST',?,?,'Task12 D substitute',1)",actor,UUID.randomUUID().toString(),op,target);
            stage("audit",ErrorCode.AUDIT_UNAVAILABLE);
            if(fault.equals("database")) db.update("INSERT INTO sys_operation_log(request_id,operation_type) VALUES(?,NULL)",UUID.randomUUID().toString());
        }
        public void verifyOrganization(List<Department> organization) { stage("verify",ErrorCode.INTEGRITY_FAILURE); }
        public void verifyUsers(List<Map<String,Object>> rows) { stage("verify",ErrorCode.INTEGRITY_FAILURE); }
        public void signOrganization(String op,Object actor,Map<String,Object> evidence) {
            long id=Long.parseLong(((List<?>)evidence.get("affectedDepartmentIds")).getFirst().toString());
            db.update("INSERT INTO sys_data_signature(data_type,data_id,signature,key_id) VALUES('TASK12_DEPT',?,?,'TASK12_FAKE') ON DUPLICATE KEY UPDATE signature=VALUES(signature)",id,Base64.getEncoder().encodeToString(new byte[64]));
            stage("sign",ErrorCode.INTEGRITY_FAILURE);
            audit(op,Long.parseLong(((com.whatname.iam.service.department.DepartmentCollaboration.Manager)actor).actorId()),id,"test");
        }
        static String fingerprint(UserAccount u) {
            try {
                String value=Arrays.asList(u.id,u.username,u.realNameCiphertext,u.phone,u.phoneHash,u.passwordHash,u.deptId,u.status,u.source,u.passwordInitialized,u.sessionVersion,u.grantRevision,u.hrVersion).toString();
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
                byte[] doubled=new byte[64];System.arraycopy(digest,0,doubled,0,32);System.arraycopy(digest,0,doubled,32,32);
                return Base64.getEncoder().encodeToString(doubled);
            } catch(Exception ex) { throw new AssertionError(ex); }
        }
    }
    @BeforeAll void start() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); var keys=generator.generateKeyPair();
        settings=new HashMap<>();
        settings.put("server.port",18089); settings.put("server.address","127.0.0.1");
        for(String key:List.of("data-key","phone-hmac-key","sms-hmac-key")) {
            byte[] value=new byte[32];new SecureRandom().nextBytes(value);settings.put("iam.security."+key,Base64.getEncoder().encodeToString(value));
        }
        settings.put("iam.security.jwt-private-key",Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        settings.put("iam.security.jwt-public-key",Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        settings.put("iam.security.jwt-issuer","task12");settings.put("iam.security.jwt-audience","task12");settings.put("iam.security.jwt-key-id","task12");
        settings.put("logging.level.root","WARN");settings.put("spring.main.banner-mode","off");
        app=new SpringApplicationBuilder(M1MySqlIntegrationTest.TestBootstrap.class,Wiring.class).properties(settings).run(TestDatabase.arguments("--server.port=18089"));
        db=app.getBean(JdbcTemplate.class);users=app.getBean(UserAccountMapper.class);codec=app.getBean(SensitiveDataCodec.class);
        jwt=app.getBean(JwtTokenService.class);data=app.getBean(TestD.class);integrity=app.getBean(CIntegrity.class);
        json=app.getBean(ObjectMapper.class);manager=app.getBean(PlatformTransactionManager.class);
        try(var connection=db.getDataSource().getConnection()) { assertEquals("MySQL",connection.getMetaData().getDatabaseProductName()); }
        assertInstanceOf(CBackedM1SecurityGateway.class,app.getBean(M1SecurityGateway.class));
        assertInstanceOf(com.whatname.iam.service.department.CBackedDepartmentCollaboration.class,app.getBean(com.whatname.iam.service.department.DepartmentCollaboration.class));
        original=db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'TEST-SEED-%' ORDER BY id");assertEquals(3,original.size());
    }
    @BeforeEach void fixtures() {
        data.fault="";integrity.fail=false;
        root=department(0);branch=department(root);child=department(branch);outside=department(root);merged=department(root);
        db.update("UPDATE sys_dept SET merged_into_dept_id=? WHERE id=?",branch,merged);
        // Lower target ID exercises sorted actor+target locking instead of actor-first locking.
        superior=user(root,3);target=user(child,2); other=user(outside,2);actor=user(branch,3);
        appointment(actor.id,2,branch);appointment(superior.id,1,root);
        data.cActor.set(superior.id);bearer="Bearer "+jwt.issue(actor.id,actor.sessionVersion);
    }
    @AfterAll void retireAndPreserveHistory() {
        try {
            if(db!=null) {
                for(Long id:accountIds) {db.update("UPDATE sys_admin SET status=0 WHERE user_id=?",id);db.update("UPDATE sys_user SET status=3,session_version=session_version+1 WHERE id=?",id);}
                for(Long id:departmentIds) db.update("UPDATE sys_dept SET status=0 WHERE id=?",id);
                assertEquals(original,db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'TEST-SEED-%' ORDER BY id"));
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE username LIKE ? AND status<>3",Integer.class,prefix+"%"));
                System.out.println("TASK12 evidence prefix="+prefix+" retiredUsers="+accountIds.size()+" frozenDepartments="+departmentIds.size()+" originalDepartmentsUnchanged=3 CIntegrityCalls="+integrity.calls.get());
                evidence.forEach(System.out::println);
            }
        } finally { if(app!=null) app.close(); }
    }
    @Test void scopedItemsAndTotalUseRealAppointmentAndSubtree() throws Exception {
        int before=integrity.calls.get();
        var response=http("GET","/users?keyword="+prefix,null,bearer,200);
        // Own actor and child target only; outside user and level-one superior are excluded.
        assertEquals(2,response.at("/data/total").asInt());assertEquals(2,response.at("/data/items").size());
        assertTrue(integrity.calls.get()>before);
        http("GET","/users/"+other.id,null,bearer,404);
        http("GET","/users?deptId="+outside,null,bearer,403);
        assertEquals(1,http("GET","/users?keyword="+prefix+"&deptId="+child,null,bearer,200).at("/data/total").asInt());
        http("GET","/users/"+target.id,null,bearer,200);
        // Same encrypted name inside/outside the real C subtree; outside must not be decrypted.
        db.update("UPDATE sys_user SET real_name_ciphertext='task17-outside-unreadable' WHERE id=?",other.id);
        try {
            var first=http("GET","/users?keyword=Twelve&pageSize=1",null,bearer,200);
            var second=http("GET","/users?keyword=Twelve&pageSize=1&page=2",null,bearer,200);
            assertEquals(2,first.at("/data/total").asInt());assertEquals(2,second.at("/data/total").asInt());
            assertEquals(Set.of(actor.id.toString(),target.id.toString()),Set.of(first.at("/data/items/0/userId").asText(),second.at("/data/items/0/userId").asText()));
            assertEquals(1,http("GET","/users?keyword=Twelve&deptId="+child+"&source=2&status=1",null,bearer,200).at("/data/total").asInt());
        } finally { db.update("UPDATE sys_user SET real_name_ciphertext=? WHERE id=?",other.realNameCiphertext,other.id); }
    }
    @Test void levelOneSeesOtherRootsAndInvalidInputDoesNotCreateAuthority() throws Exception {
        String top="Bearer "+jwt.issue(superior.id,1);
        http("GET","/users/"+other.id,null,top,200);
        http("GET","/users?deptId=0",null,top,400);
    }
    @Test void missingAndRevokedAppointmentRejectNewRequests() throws Exception {
        http("GET","/users",null,"Bearer "+jwt.issue(target.id,1),403);
        app.getBean(AdminService.class).revoke(actor.id.toString(),new AdminDtos.RevokeRequest("task12 revoke"));
        http("GET","/users",null,bearer,403);
    }
    @Test void pausedAndTerminatedActorsAreRejectedByRealC() throws Exception {
        for(int state:List.of(2,3)) {
            db.update("UPDATE sys_user SET status=? WHERE id=?",state,actor.id);
            http("GET","/users",null,bearer,403);
        }
    }
    @Test void frozenAncestorAndMergedManagementRootCannotAuthorize() throws Exception {
        db.update("UPDATE sys_dept SET status=0 WHERE id=?",root);
        http("GET","/users",null,bearer,403);
        db.update("UPDATE sys_dept SET status=1 WHERE id=?",root);
        db.update("UPDATE sys_admin SET dept_id=? WHERE user_id=?",merged,actor.id);
        http("GET","/users",null,bearer,403);
    }
    @Test void mergedPersonnelDepartmentCannotAuthorizeThroughA() throws Exception {
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?",merged,actor.id);
        data.seed(users.findById(actor.id));
        http("GET","/users",null,bearer,403);
        http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,403);
        assertEquals(1,users.findById(target.id).status);
    }
    @Test void mergedDepartmentCannotReceiveNewAUsers() throws Exception {
        // C is restored unchanged; its appointment/grant lifecycle gap is a team follow-up.
        // This suite verifies the rule only at the A entry point owned by this change.
        http("POST","/users/temporary",temporary(merged),"Bearer "+jwt.issue(superior.id,1),422);
    }
    @Test void administratorPersonnelDepartmentMayBeOutsideOwnManagementRoot() throws Exception {
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?",outside,actor.id);
        data.seed(users.findById(actor.id));
        assertEquals(1,http("GET","/users?keyword="+prefix,null,bearer,200).at("/data/total").asInt());
        http("GET","/users/"+target.id,null,bearer,200);
        http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,200);
        var created=http("POST","/users/temporary",temporary(child),bearer,200);
        accountIds.add(Long.parseLong(created.at("/data/userId").asText()));
        http("POST","/departments/"+child+"/freeze",Map.of("reason",prefix),bearer,200);
    }
    @Test void existingADepartmentTargetErrorsRemainOutOfScope() throws Exception {
        http("POST","/users/temporary",temporary(outside),bearer,403);
        http("POST","/departments/"+outside+"/freeze",Map.of("reason",prefix),bearer,403);
        assertEquals(1,db.queryForObject("SELECT status FROM sys_dept WHERE id=?",Integer.class,outside));
    }
    @Test void managementEntrypointsRejectAnExistingSnapshotBeforeTakingAuthorityOrWriting() {
        int integrityBefore=integrity.calls.get();
        int evidenceBefore=db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE source_system='TASK12_TEST' AND user_id=?",Integer.class,actor.id);
        var outer=new TransactionTemplate(manager);
        outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.executeWithoutResult(status->{
            // Establish an older caller snapshot; A must neither use it nor split the caller transaction.
            assertEquals(1,db.queryForObject("SELECT status FROM sys_user WHERE id=?",Integer.class,target.id));
            var userFailure=assertThrows(BusinessException.class,()->app.getBean(com.whatname.iam.service.user.UserService.class)
                    .changeStatus(bearer,target.id.toString(),new com.whatname.iam.dto.user.UserRequests.Status(2,prefix)));
            assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE,userFailure.errorCode());
            var actorContext=new com.whatname.iam.service.department.DepartmentCollaboration.Manager(actor.id.toString(),jwt.verifyBearer(bearer));
            var departmentFailure=assertThrows(BusinessException.class,()->app.getBean(com.whatname.iam.service.department.DepartmentService.class)
                    .freeze(actorContext,child,true,prefix));
            assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE,departmentFailure.errorCode());
            status.setRollbackOnly();
        });
        assertEquals(integrityBefore,integrity.calls.get());
        assertEquals(1,users.findById(target.id).status);assertEquals(1,users.findById(target.id).sessionVersion);
        assertEquals(1,db.queryForObject("SELECT status FROM sys_dept WHERE id=?",Integer.class,child));
        assertEquals(evidenceBefore,db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE source_system='TASK12_TEST' AND user_id=?",Integer.class,actor.id));
    }
    @Test void malformedOrganizationCannotCreateManagementScope() throws Exception {
        try {
            db.update("UPDATE sys_dept SET parent_id=? WHERE id=?",child,branch);
            http("GET","/users",null,bearer,503);
            assertEquals(1,users.findById(target.id).status);
        } finally {db.update("UPDATE sys_dept SET parent_id=? WHERE id=?",root,branch);}
    }
    @Test void temporaryCreationWithRealM5RollsBackAllFactsOnEvidenceFailure() throws Exception {
        for(String failure:List.of("sign","audit","database")) {
            data.fault=failure;
            var input=new HashMap<>(temporary(child));String reason=prefix+"create-fault-"+failure;input.put("reason",reason);
            http("POST","/users/temporary",input,bearer,503);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE temp_reason=?",Integer.class,reason));
        }
        data.fault="";
        var created=http("POST","/users/temporary",temporary(child),bearer,200);
        long id=Long.parseLong(created.at("/data/userId").asText());accountIds.add(id);
        var actual=users.findById(id);assertEquals(actor.id,actual.createdBy);assertEquals(2,actual.source);
        assertEquals(0,actual.passwordInitialized);assertEquals(child,actual.deptId);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Integer.class,id));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_type='TASK12_USER' AND data_id=?",Integer.class,id));
    }
    @Test void currentTargetDepartmentIsUsedAfterMove() throws Exception {
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?",outside,target.id);data.seed(users.findById(target.id));
        http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,404);
        assertEquals(1,users.findById(target.id).status);
    }
    @Test void statusWriteUsesRealM5AndSessionInvalidation() throws Exception {
        http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,200);
        assertEquals(2,users.findById(target.id).status);assertEquals(2,users.findById(target.id).sessionVersion);
        http("PATCH","/users/"+target.id+"/status",Map.of("status",1,"reason",prefix),bearer,200);
        assertEquals(2,users.findById(target.id).sessionVersion);
        db.update("UPDATE sys_user SET session_version=2 WHERE id=?",actor.id);data.seed(users.findById(actor.id));
        http("GET","/users",null,bearer,401);
    }
    @Test void signatureAuditAndDatabaseFaultsRollbackStatusVersionAndEvidence() throws Exception {
        String signature=db.queryForObject("SELECT signature FROM sys_data_signature WHERE data_type='TASK12_USER' AND data_id=?",String.class,target.id);
        for(String fault:List.of("sign","audit","database")) {
            data.fault=fault;
            http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,503);
            assertEquals(1,users.findById(target.id).status);assertEquals(1,users.findById(target.id).sessionVersion);
            assertEquals(signature,db.queryForObject("SELECT signature FROM sys_data_signature WHERE data_type='TASK12_USER' AND data_id=?",String.class,target.id));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE source_system='TASK12_TEST' AND target_id=?",Integer.class,target.id));
        }
    }
    @Test void missingCIntegrityAndDFunctionRejectBeforeBusinessMutation() throws Exception {
        integrity.fail=true;http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,503);
        integrity.fail=false;data.fault="function";http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,403);
        assertEquals(1,users.findById(target.id).status);
    }
    @Test void departmentReadsFreezeUnfreezeAndAuditRollbackUseRealC() throws Exception {
        var tree=http("GET","/departments",null,bearer,200);
        assertEquals(2,tree.at("/data/items").size());
        var members=http("GET","/departments/"+branch+"/users?includeDescendants=true",null,bearer,200);
        assertEquals(2,members.at("/data/items").size());assertEquals(2,members.at("/data/total").asInt());
        http("POST","/departments/"+child+"/freeze",Map.of("reason",prefix),bearer,200);
        assertEquals(0,db.queryForObject("SELECT status FROM sys_dept WHERE id=?",Integer.class,child));
        data.fault="audit";http("POST","/departments/"+child+"/unfreeze",Map.of("reason",prefix),bearer,503);
        assertEquals(0,db.queryForObject("SELECT status FROM sys_dept WHERE id=?",Integer.class,child));
        data.fault="";http("POST","/departments/"+child+"/unfreeze",Map.of("reason",prefix),bearer,200);
        http("POST","/departments/"+outside+"/freeze",Map.of("reason",prefix),bearer,403);
    }
    @Test void waitingRequestRechecksRealCRevocationAfterLockRelease() throws Exception { waitingChange("revoke"); }
    @Test void waitingRequestRechecksChangedScopeAfterLockRelease() throws Exception { waitingChange("scope"); }
    @Test void waitingRequestRechecksTargetMoveAfterLockRelease() throws Exception { waitingChange("target"); }
    @Test void waitingListRechecksRealCRevocationBeforeBuildingItsSnapshot() throws Exception { waitingChange("revoke",true); }
    private void waitingChange(String change) throws Exception { waitingChange(change,false); }
    private void waitingChange(String change,boolean list) throws Exception {
        var held=new CountDownLatch(1);var release=new CountDownLatch(1);var started=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var blocker=pool.submit(()-> {
                data.cActor.set(superior.id);
                try { return new TransactionTemplate(manager).execute(tx->{
                    if(change.equals("target")) app.getBean(AManagementPolicy.class).prepare(superior.id,target.id);
                    if(change.equals("revoke") || change.equals("scope"))
                        app.getBean(AdminService.class).revoke(actor.id.toString(),new AdminDtos.RevokeRequest("task12 concurrent revoke"));
                    if(change.equals("scope")) app.getBean(AdminService.class).appoint(new AdminDtos.AppointmentRequest(actor.id.toString(),2,Long.toString(outside),"task12 concurrent reappointment"));
                    // Target move is a fixture transition, not a claimed real HR/C entry point.
                    if(change.equals("target")) {db.update("UPDATE sys_user SET dept_id=? WHERE id=?",outside,target.id);data.seed(users.findById(target.id));}
                    held.countDown();await(release);return true;
                }); } finally { data.cActor.remove(); }
            });
            try {
                assertTrue(held.await(10,TimeUnit.SECONDS));
                var request=pool.submit(()->{
                    started.countDown();
                    return list?http("GET","/users?keyword="+prefix,null,bearer,403):
                            http("PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,change.equals("revoke")?403:404);
                });
                assertTrue(started.await(5,TimeUnit.SECONDS));
                Thread.sleep(250);assertFalse(request.isDone(),"request must wait for real M5 locks");
                release.countDown();blocker.get(10,TimeUnit.SECONDS);request.get(10,TimeUnit.SECONDS);
                assertEquals(1,users.findById(target.id).status);assertEquals(1,users.findById(target.id).sessionVersion);
            } finally {release.countDown();}
        }
    }
    @Test void defaultProductionDependenciesRejectAndHrRemainsUnavailable() throws Exception {
        var defaults=new HashMap<>(settings);defaults.put("server.port",18090);
        try(var ordinary=new SpringApplicationBuilder(M1MySqlIntegrationTest.TestBootstrap.class).properties(defaults).run(TestDatabase.arguments("--server.port=18090"))) {
            assertFalse(ordinary.getBean(ManagementDataSecurity.class) instanceof TestD);
            assertThrows(BusinessException.class,()->new TransactionTemplate(ordinary.getBean(PlatformTransactionManager.class)).execute(tx->{ordinary.getBean(AManagementPolicy.class).prepare(actor.id);return true;}));
            assertThrows(BusinessException.class,()->ordinary.getBean(com.whatname.iam.service.department.DepartmentCollaboration.class).authenticateHr(null,"HR_MERGE"));
            http(18090,"PATCH","/users/"+target.id+"/status",Map.of("status",2,"reason",prefix),bearer,503);
            http(18090,"GET","/departments",null,bearer,503);
            assertEquals(1,users.findById(target.id).status);
            assertThrows(BusinessException.class,()->new TransactionTemplate(ordinary.getBean(PlatformTransactionManager.class))
                    .executeWithoutResult(tx -> ordinary.getBean(com.whatname.iam.service.hr.HrCollaboration.class)
                            .revokeAll(null,"task12-no-hr",target.id)));
        }
        String event=prefix+"real-hr-unavailable";
        http("POST","/integrations/hr/merges",Map.of("sourceSystem","HR","eventId",event,"eventVersion",1,
                "sourceDeptCode",db.queryForObject("SELECT dept_code FROM sys_dept WHERE id=?",String.class,branch),
                "targetDeptCode",db.queryForObject("SELECT dept_code FROM sys_dept WHERE id=?",String.class,outside),
                "occurredAt","2026-10-16T12:00:00+08:00"),bearer,503);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?",Integer.class,event));
    }
    private long department(long parent) {
        var key=new GeneratedKeyHolder();
        db.update(c->{var s=c.prepareStatement("INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path) VALUES(?,?,?,'/')",new String[]{"id"});s.setLong(1,parent);s.setString(2,prefix+seq.incrementAndGet());s.setString(3,"Task12 test");return s;},key);
        long id=key.getKey().longValue();departmentIds.add(id);
        String path=parent==0?"/":db.queryForObject("SELECT dept_path FROM sys_dept WHERE id=?",String.class,parent);
        db.update("UPDATE sys_dept SET dept_path=? WHERE id=?",path+id+"/",id);return id;
    }
    private UserAccount user(long dept,int source) {
        int n=seq.incrementAndGet();String phone="13912%06d".formatted(new SecureRandom().nextInt(1000000));
        var key=new GeneratedKeyHolder();
        db.update(c->{var s=c.prepareStatement("INSERT INTO sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized,temp_reason,created_by) VALUES(?,?,?,?,?,?,?,1,1,?,?)",new String[]{"id"});
            Object[] values={prefix+"user"+n,codec.encrypt("Task Twelve"),codec.encrypt(phone),codec.phoneHash(phone),"test-only-unusable-hash",dept,source,source==2?prefix:null,source==2?superior.id:null};
            for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;},key);
        long id=key.getKey().longValue();accountIds.add(id);var user=users.findById(id);data.seed(user);
        return user;
    }
    private void appointment(long user,int level,long dept) {db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,?,?,1)",user,level,dept);}
    private Map<String,Object> temporary(long dept) {return Map.of("deptId",Long.toString(dept),"realName","Task Twelve","phone","13912%06d".formatted(new SecureRandom().nextInt(1000000)),"reason",prefix);}
    private static void await(CountDownLatch latch) { try {assertTrue(latch.await(10,TimeUnit.SECONDS));} catch(InterruptedException ex){Thread.currentThread().interrupt();throw new AssertionError(ex);} }
    private JsonNode http(String method,String path,Object body,String token,int expected) throws Exception {
        return http(18089,method,path,body,token,expected);
    }
    private JsonNode http(int port,String method,String path,Object body,String token,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/v1"+path)).timeout(java.time.Duration.ofSeconds(15)).header("Content-Type","application/json");
        if(token!=null)request.header("Authorization",token);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());var result=json.readTree(response.body());
        evidence.add("TASK12 "+method+" "+path.split("\\?")[0]+" -> "+response.statusCode()+" "+result.path("code").asText());
        assertEquals(expected,response.statusCode(),method+" "+path.split("\\?")[0]+" code="+result.path("code").asText());return result;
    }
}
