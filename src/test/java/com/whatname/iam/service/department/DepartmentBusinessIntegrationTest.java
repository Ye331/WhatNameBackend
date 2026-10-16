package com.whatname.iam.service.department;

import com.whatname.iam.support.LocalTestDatabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.exception.GlobalExceptionHandler;
import com.whatname.iam.common.security.SensitiveDataCodec;
import com.whatname.iam.controller.department.DepartmentController;
import com.whatname.iam.dto.department.DepartmentRequests;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.service.event.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import static org.junit.jupiter.api.Assertions.*;

/** Real personal MySQL + real HTTP; C/D is an explicitly test-only integration port.
 * External events and append-only test audit evidence remain, as required by retention rules.
 * No production development switch enables this test port.
 */
@EnabledIfEnvironmentVariable(named="DEPARTMENT_DB_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DepartmentBusinessIntegrationTest {
    @TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @MapperScan({"com.whatname.iam.mapper.department","com.whatname.iam.mapper.event"})
    @Import({DepartmentService.class,ExternalEventService.class,DepartmentController.class,
            GlobalExceptionHandler.class,SensitiveDataCodec.class})
    static class TestApplication {
        @Bean TestPort testPort(JdbcTemplate jdbc) { return new TestPort(jdbc); }
    }
    @TestConfiguration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @MapperScan({"com.whatname.iam.mapper.department","com.whatname.iam.mapper.event"})
    @Import({DepartmentService.class,ExternalEventService.class,DepartmentController.class,
            GlobalExceptionHandler.class,SensitiveDataCodec.class,DepartmentIntegrationConfiguration.class})
    static class DefaultApplication { }
    static class TestPort implements DepartmentCollaboration {
        final JdbcTemplate jdbc;
        volatile String fault="";
        volatile Set<Long> visible;
        volatile boolean denyRecovery;
        TestPort(JdbcTemplate jdbc) {this.jdbc=jdbc;}
        private void reject(String stage) {
            if(fault.equals(stage)) throw new BusinessException(stage.equals("audit")?ErrorCode.AUDIT_UNAVAILABLE:
                    stage.equals("permission")?ErrorCode.PERMISSION_DENIED:ErrorCode.INTEGRITY_FAILURE);
        }
        public Manager authenticateManager(HttpServletRequest r,String permission) {
            if(!"test-manager".equals(r.getHeader("X-Test-Identity"))) throw new BusinessException(ErrorCode.INVALID_TOKEN);
            reject("permission"); return new Manager("task06-test-manager");
        }
        public TrustedEventContext authenticateHr(HttpServletRequest r,String type) {
            if(!"test-hr".equals(r.getHeader("X-Test-Identity"))) throw new BusinessException(ErrorCode.INVALID_SERVICE);
            return new TrustedEventContext("HR","task06-test-hr",UUID.randomUUID().toString());
        }
        public void verifyTrustedEvent(TrustedEventContext c,String type) {
            if(!Set.of("task06-test-hr","task06-test-oa").contains(c.clientId())) throw new BusinessException(ErrorCode.INVALID_SERVICE);
        }
        public void authorizeRecovery(TrustedEventContext c,String type) {
            if(denyRecovery) throw new BusinessException(ErrorCode.PERMISSION_DENIED);
            verifyTrustedEvent(c,type);
        }
        public Set<Long> scope(Manager actor,String p,List<Department> all) {
            reject("permission"); return visible==null?all.stream().map(Department::getId).collect(java.util.stream.Collectors.toSet()):visible;
        }
        public void verifyOrganization(List<Department> all) { reject("verify"); }
        public void verifyUsers(List<Map<String,Object>> users) { reject("verifyUsers"); }
        public void organizationChanged(Set<Long> ids,List<Map<String,Object>> users) { reject("scopeChange"); }
        public void signAndAudit(String operation,Object actor,Map<String,Object> evidence) {
            reject("sign");
            jdbc.update("INSERT INTO sys_operation_log(request_id,source_system,operation_type,operation_desc) VALUES(?,?,?,?)",
                    "task06-"+UUID.randomUUID(),"HR",operation,"task06 isolated C/D test port");
            reject("audit");
        }
    }
    ServletWebServerApplicationContext app;
    JdbcTemplate db;
    DepartmentService service;
    ExternalEventService events;
    TestPort port;
    ObjectMapper json;
    SensitiveDataCodec codec;
    String prefix;
    String base;
    List<Map<String,Object>> seedBefore;
    final AtomicInteger sequence=new AtomicInteger();
    final TrustedEventContext hr=new TrustedEventContext("HR","task06-test-hr","test-request");
    final OffsetDateTime time=OffsetDateTime.parse("2026-10-16T12:00:00+08:00");

    @BeforeAll void start() {
        prefix="T06-"+Long.toString(System.currentTimeMillis(),36)+"-";
        String key=Base64.getEncoder().encodeToString(new byte[32]); // isolated test key, no production default
        byte[] hmacBytes=new byte[32]; Arrays.fill(hmacBytes,(byte)1); String hmacKey=Base64.getEncoder().encodeToString(hmacBytes);
        app=(ServletWebServerApplicationContext)new SpringApplication(TestApplication.class).run(LocalTestDatabase.arguments(
                "--server.port=18086","--server.address=127.0.0.1",
                "--iam.security.data-key="+key,"--iam.security.phone-hmac-key="+hmacKey,
                "--logging.level.root=WARN","--spring.main.banner-mode=off"));
        db=app.getBean(JdbcTemplate.class);service=app.getBean(DepartmentService.class);events=app.getBean(ExternalEventService.class);
        port=app.getBean(TestPort.class);json=app.getBean(ObjectMapper.class);codec=app.getBean(SensitiveDataCodec.class);
        base="http://127.0.0.1:18086/api/v1";
        seedBefore=db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'DEV-DEPT-R%' ORDER BY id");
        assertEquals(111,seedBefore.size());
    }
    @AfterEach void resetPort() {port.fault="";port.visible=null;port.denyRecovery=false;}
    @AfterAll void cleanup() {
        try {
            if(db!=null) {
                // Permanent-account/source triggers are respected. Precisely retire this run's fixtures.
                db.update("UPDATE sys_user_role_source SET status=0,revoked_at=CURRENT_TIMESTAMP WHERE status=1 AND user_id IN (SELECT id FROM sys_user WHERE username LIKE ?)",prefix+"%");
                db.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE username LIKE ?)",prefix+"%");
                db.update("UPDATE sys_role SET status=0 WHERE role_code LIKE ?",prefix+"%");
                db.update("UPDATE sys_user SET status=3,session_version=session_version+1 WHERE username LIKE ? AND status<>3",prefix+"%");
                db.update("UPDATE sys_dept SET status=0 WHERE dept_code LIKE ?",prefix+"%");
                assertEquals(seedBefore,db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'DEV-DEPT-R%' ORDER BY id"));
            }
        } finally { if(app!=null) app.close(); }
    }
    String event() {return prefix+"E"+sequence.incrementAndGet();}
    String code(String name) {return prefix+name+sequence.incrementAndGet();}
    DepartmentRequests.Sync sync(String code,String parent,long version,String manager,String event) {
        return new DepartmentRequests.Sync("HR",event,version,code,"Name "+code,parent,manager,time);
    }
    long create(String code,String parent,long version) {
        return Long.parseLong(service.sync(hr,code,sync(code,parent,version,null,event())).get("deptId").toString());
    }
    Map<String,Object> row(long id) {return db.queryForMap("SELECT * FROM sys_dept WHERE id=?",id);}
    long user(long dept,String sg) {
        String name=prefix+"user"+sequence.incrementAndGet();
        String phone="199"+String.format("%08d",(System.currentTimeMillis()+sequence.incrementAndGet())%100_000_000L);
        db.update("INSERT INTO sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,employee_no,dept_id,source,employment_type,hr_version) VALUES(?,?,?,?,?,?,?,1,1,10)",
                name,codec.encrypt("isolated test"),codec.encrypt(phone),codec.phoneHash(phone),"test-not-login-hash",sg,dept);
        return db.queryForObject("SELECT id FROM sys_user WHERE username=?",Long.class,name);
    }
    DepartmentRequests.Merge mergeRequest(String source,String target,long version,String event) {
        return new DepartmentRequests.Merge("HR",event,version,source,target,time);
    }
    HttpResponse<String> http(String method,String path,Object body,String identity) throws Exception {
        var b=HttpRequest.newBuilder(URI.create(base+path)).header("Content-Type","application/json");
        if(identity!=null) b.header("X-Test-Identity",identity);
        b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return HttpClient.newHttpClient().send(b.build(),HttpResponse.BodyHandlers.ofString());
    }
    void error(ErrorCode code,org.junit.jupiter.api.function.Executable work) {
        assertEquals(code,assertThrows(BusinessException.class,work).errorCode());
    }

    @Test void hr01HttpCreateManagerOptionalPreservedAndOldVersionRejected() throws Exception {
        String root=code("ROOT"),child=code("SYNC");long rootId=create(root,"",1);
        var body=sync(child,root,1,null,event());
        HttpResponse<String> response=http("PUT","/integrations/hr/departments/"+child,body,"test-hr");
        assertEquals(200,response.statusCode());long id=Long.parseLong(json.readTree(response.body()).at("/data/deptId").asText());
        assertNull(row(id).get("manager_id")); assertTrue(json.readTree(response.body()).at("/data/deptId").isTextual());
        String sg=prefix+"SG1";long manager=user(rootId,sg);
        service.sync(hr,child,sync(child,root,2,sg,event())); assertEquals(manager,((Number)row(id).get("manager_id")).longValue());
        service.sync(hr,child,sync(child,root,3,null,event())); assertEquals(manager,((Number)row(id).get("manager_id")).longValue());
        error(ErrorCode.INVALID_DEPT_MAPPING,() -> service.sync(hr,child,sync(child,root,4,"unknown-manager",event())));
        error(ErrorCode.INVALID_HR_ORDER,() -> service.sync(hr,child,sync(child,root,2,null,event())));
        assertEquals(3L,((Number)row(id).get("hr_version")).longValue());
        assertEquals(response.body().contains("password"),false);
    }
    @Test void syncReparentsWholeSubtreeAndRejectsCycles() {
        String root=code("ROOT"),a=code("A"),b=code("B"),c=code("C");create(root,"",1);long ai=create(a,root,1),bi=create(b,root,1),ci=create(c,a,1);
        service.sync(hr,a,sync(a,b,2,null,event()));
        assertEquals(row(bi).get("dept_path")+Long.toString(ai)+"/"+ci+"/",row(ci).get("dept_path"));
        Map<String,Object> before=row(bi);
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.sync(hr,b,sync(b,c,2,null,event())));
        assertEquals(before,row(bi));
    }
    @Test void mergeVersionOnlySourceMovesPeopleChildrenAndRetainsChildFreeze() {
        String root=code("ROOT"),s=code("SRC"),t=code("DST"),c=code("CHILD");create(root,"",1);
        long si=create(s,root,10),ti=create(t,root,40),ci=create(c,s,3);long uid=user(si,prefix+"SG-MERGE");
        String role=code("ROLE");db.update("INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",role,"task06 isolated test role");
        long roleId=db.queryForObject("SELECT id FROM sys_role WHERE role_code=?",Long.class,role);
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",uid,roleId);
        db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id) VALUES(?,?,'DEPT_POSITION',?,?)",uid,roleId,prefix+"test-position",si);
        var grantsBefore=db.queryForList("SELECT * FROM sys_user_role WHERE user_id=?",uid);
        var sourcesBefore=db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",uid);
        service.freeze(new DepartmentCollaboration.Manager("test"),ci,true,"test child freeze");
        var beforeUser=db.queryForMap("SELECT * FROM sys_user WHERE id=?",uid);
        var request=mergeRequest(s,t,11,event());
        Map<String,Object> result=service.merge(hr,request);assertEquals(true,result.get("rolesPreserved"));
        assertEquals(ti,((Number)row(si).get("merged_into_dept_id")).longValue());
        assertEquals(11L,((Number)row(si).get("hr_version")).longValue());assertEquals(40L,((Number)row(ti).get("hr_version")).longValue());
        assertEquals(ti,((Number)row(ci).get("parent_id")).longValue()); assertEquals(0,((Number)row(ci).get("status")).intValue());
        assertEquals(row(ti).get("dept_path")+Long.toString(ci)+"/",row(ci).get("dept_path"));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_dept WHERE parent_id=?",Integer.class,si));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE dept_id=?",Integer.class,si));
        var afterUser=db.queryForMap("SELECT * FROM sys_user WHERE id=?",uid);beforeUser.put("dept_id",ti);beforeUser.put("updated_at",afterUser.get("updated_at"));assertEquals(beforeUser,afterUser);
        assertEquals(grantsBefore,db.queryForList("SELECT * FROM sys_user_role WHERE user_id=?",uid));
        assertEquals(sourcesBefore,db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=?",uid));
        assertEquals(result,service.merge(hr,request));
        String invalid=code("INVALID");
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.sync(hr,invalid,sync(invalid,s,1,null,event())));
    }
    @Test void frozenSourceOrAncestorRequiresExplicitUnfreeze() {
        String root=code("ROOT"),s=code("SRC"),t=code("DST");long ri=create(root,"",1),si=create(s,root,10);create(t,"",40);
        var actor=new DepartmentCollaboration.Manager("test");service.freeze(actor,ri,true,"freeze root");
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.merge(hr,mergeRequest(s,t,11,event())));
        service.freeze(actor,si,false,"unfreeze child only");
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.merge(hr,mergeRequest(s,t,11,event())));
        service.freeze(actor,ri,false,"explicit root recovery");
        service.freeze(actor,si,true,"freeze source");error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.merge(hr,mergeRequest(s,t,11,event())));
        service.freeze(actor,si,false,"explicit source recovery");service.merge(hr,mergeRequest(s,t,11,event()));
    }
    @Test void selfAndDescendantMergeRejected() {
        String root=code("ROOT"),child=code("CHILD");long ri=create(root,"",10);create(child,root,40);
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.merge(hr,mergeRequest(root,root,11,event())));
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.merge(hr,mergeRequest(root,child,11,event())));
        assertNull(row(ri).get("merged_into_dept_id"));
    }
    @Test void dp01Dp02ScopePaginationStringsAndHttpFreeze() throws Exception {
        String root=code("ROOT"),child=code("CHILD"),outside=code("OUT");long ri=create(root,"",1),ci=create(child,root,1),oi=create(outside,"",1);
        user(ri,prefix+"SG-LIST1");user(ci,prefix+"SG-LIST2");user(oi,prefix+"SG-OUT");port.visible=Set.of(ri,ci);
        JsonNode tree=json.readTree(http("GET","/departments?includeDescendants=false",null,"test-manager").body()).path("data");assertEquals(1,tree.path("items").size());
        assertEquals("0",tree.path("items").get(0).path("parentId").asText());assertFalse(tree.toString().contains("deptPath"));
        assertEquals(2,json.readTree(http("GET","/departments",null,"test-manager").body()).at("/data/items").size());
        var direct=json.readTree(http("GET","/departments/"+ri+"/users",null,"test-manager").body()).path("data");assertEquals(1,direct.path("total").asInt());
        var descendants=json.readTree(http("GET","/departments/"+ri+"/users?includeDescendants=true&pageSize=1&page=2",null,"test-manager").body()).path("data");
        assertEquals(2,descendants.path("total").asInt());assertEquals(1,descendants.path("items").size());assertTrue(descendants.path("items").get(0).path("userId").isTextual());
        port.visible=Set.of(ri);assertEquals(1,json.readTree(http("GET","/departments/"+ri+"/users?includeDescendants=true",null,"test-manager").body()).at("/data/total").asInt());
        assertEquals(403,http("GET","/departments/"+oi+"/users",null,"test-manager").statusCode());
        assertEquals(400,http("GET","/departments/"+ri+"/users?pageSize=501",null,"test-manager").statusCode());
        assertEquals(200,http("POST","/departments/"+ri+"/freeze",Map.of("reason","HTTP test"),"test-manager").statusCode());assertEquals(0,((Number)row(ri).get("status")).intValue());
        assertEquals(200,http("POST","/departments/"+ri+"/unfreeze",Map.of("reason","HTTP restore"),"test-manager").statusCode());
        port.fault="permission";assertEquals(403,http("POST","/departments/"+ri+"/unfreeze",Map.of("reason","deny"),"test-manager").statusCode());
        assertEquals(401,http("GET","/departments",null,null).statusCode());
    }
    @Test void signaturesAuditAndScopeFailureRollBackMergeAndKeepPending() {
        for(String stage:List.of("verify","verifyUsers","scopeChange","sign","audit")) {
            String root=code("ROOT"),s=code("SRC"),t=code("DST");create(root,"",1);long si=create(s,root,10),ti=create(t,root,40),uid=user(si,prefix+"SG-F"+sequence.incrementAndGet());
            var before=row(si);String event=event();long audits=db.queryForObject("SELECT COUNT(*) FROM sys_operation_log",Long.class);
            port.fault=stage;error(stage.equals("audit")?ErrorCode.AUDIT_UNAVAILABLE:ErrorCode.INTEGRITY_FAILURE,() -> service.merge(hr,mergeRequest(s,t,11,event)));
            assertEquals(before,row(si));assertEquals(si,db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,uid));
            assertEquals("PENDING_RECOVERY",db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,event));
            assertEquals(audits,db.queryForObject("SELECT COUNT(*) FROM sys_operation_log",Long.class));assertEquals(40L,((Number)row(ti).get("hr_version")).longValue());port.fault="";
        }
    }
    @Test void freezeAuditFailureRollsBackWithoutAccountChanges() {
        String r=code("ROOT");long id=create(r,"",1),u=user(id,prefix+"SG-FREEZE");var before=row(id);var user=db.queryForMap("SELECT * FROM sys_user WHERE id=?",u);
        port.fault="audit";error(ErrorCode.AUDIT_UNAVAILABLE,() -> service.freeze(new DepartmentCollaboration.Manager("test"),id,true,"failed freeze"));
        assertEquals(before,row(id));assertEquals(user,db.queryForMap("SELECT * FROM sys_user WHERE id=?",u));
    }
    @Test void sameEventConcurrentAppliesExactlyOnceAndDifferentContentConflicts() throws Exception {
        String code=code("CONCURRENT");var request=sync(code,"",1,null,event());
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch go=new CountDownLatch(1);List<Future<Object>> futures=new ArrayList<>();
        for(int i=0;i<2;i++) futures.add(pool.submit(() -> {go.await();try{return service.sync(hr,code,request);}catch(BusinessException e){return e.errorCode();}}));
        go.countDown();for(Future<Object> future:futures) {Object r=future.get(30,TimeUnit.SECONDS);assertTrue(r instanceof Map || r==ErrorCode.VERSION_CONFLICT);}pool.shutdownNow();
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_dept WHERE dept_code=?",Integer.class,code));
        assertEquals(service.sync(hr,code,request),service.sync(hr,code,request));
        error(ErrorCode.IDEMPOTENCY_CONFLICT,() -> service.sync(hr,code,new DepartmentRequests.Sync("HR",request.eventId(),1L,code,"changed","",null,time)));
        assertTrue(db.queryForObject("SELECT payload_ciphertext FROM sys_external_event WHERE event_id=?",String.class,request.eventId()).startsWith("gcm1:"));
    }
    @Test void oaNullVersionStableDigestFailureClassificationAndControlledRecovery() {
        var oa=new TrustedEventContext("OA","task06-test-oa","request-1");String event=event();AtomicInteger applied=new AtomicInteger();
        var c=new ExternalEventService.Command(event,"OA_DECISION",null,"USER","test-subject",null,Map.of("decision","APPROVE","nonce","nonce-1","requestId","r1"));
        Map<String,Object> first=events.execute(oa,c,() -> {applied.incrementAndGet();return Map.of("eventId",event,"processingStatus","APPLIED");});
        var retry=new ExternalEventService.Command(event,"OA_DECISION",null,"USER","test-subject",null,Map.of("requestId","r2","nonce","nonce-2","decision","APPROVE"));
        assertEquals(first,events.execute(oa,retry,() -> {fail("replayed callback");return Map.of();}));assertEquals(1,applied.get());
        String failed=event();var fc=new ExternalEventService.Command(failed,"OA_DECISION",null,"USER","test-subject",null,Map.of("decision","APPROVE"));
        error(ErrorCode.AUDIT_UNAVAILABLE,() -> events.execute(oa,fc,() -> {throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE);}));
        error(ErrorCode.AUTH_STATE_UNAVAILABLE,() -> events.execute(oa,fc,Map::of));
        port.denyRecovery=true;error(ErrorCode.PERMISSION_DENIED,() -> events.recover(oa,fc,Map::of));port.denyRecovery=false;
        assertEquals(Map.of("done",true),events.recover(oa,fc,() -> Map.of("done",true)));
        assertEquals(Map.of("done",true),events.recover(oa,fc,() -> {fail("APPLIED must not rerun");return Map.of();}));
        String rejected=event();var rc=new ExternalEventService.Command(rejected,"OA_DECISION",null,"USER","test-subject",null,Map.of("decision","REJECT"));
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> events.execute(oa,rc,() -> {throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);}));
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> events.recover(oa,rc,Map::of));
        assertEquals("REJECTED",db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,rejected));
    }
    @Test void concurrentReparentingCannotCreateCycle() throws Exception {
        String r=code("ROOT"),a=code("A"),b=code("B");create(r,"",1);long ai=create(a,r,1),bi=create(b,r,1);
        var ar=sync(a,b,2,null,event());var br=sync(b,a,2,null,event());CountDownLatch go=new CountDownLatch(1);ExecutorService pool=Executors.newFixedThreadPool(2);
        Future<?> f1=pool.submit(() -> {try{go.await();service.sync(hr,a,ar);}catch(Exception expected){}});
        Future<?> f2=pool.submit(() -> {try{go.await();service.sync(hr,b,br);}catch(Exception expected){}});
        go.countDown();f1.get(35,TimeUnit.SECONDS);f2.get(35,TimeUnit.SECONDS);pool.shutdownNow();
        assertFalse(((Number)row(ai).get("parent_id")).longValue()==bi && ((Number)row(bi).get("parent_id")).longValue()==ai);
        assertTrue(((Number)row(ai).get("hr_version")).longValue()==2 || ((Number)row(bi).get("hr_version")).longValue()==2);
    }
    @Test void mergedAssignmentRejectedAndSourceSpoofRejected() {
        String s=code("SRC"),t=code("DST");long si=create(s,"",10);create(t,"",40);service.merge(hr,mergeRequest(s,t,11,event()));
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> tx.execute(status -> service.requireAssignableDepartment(si)));
        String child=code("CHILD");error(ErrorCode.BUSINESS_RULE_VIOLATION,() -> service.sync(hr,child,sync(child,s,1,null,event())));
        error(ErrorCode.INVALID_SERVICE,() -> service.sync(new TrustedEventContext("HR","forged","test"),t,sync(t,"",41,null,event())));
    }
    @Test void hr05RealHttpAndMergedNodesRemainVisibleAsEmptySources() throws Exception {
        String s=code("SRC"),t=code("DST");long si=create(s,"",10),ti=create(t,"",40);
        port.visible=Set.of(si,ti);
        var response=http("POST","/integrations/hr/merges",mergeRequest(s,t,11,event()),"test-hr");
        assertEquals(200,response.statusCode());assertTrue(json.readTree(response.body()).at("/data/rolesPreserved").asBoolean());
        var items=json.readTree(http("GET","/departments",null,"test-manager").body()).at("/data/items");
        JsonNode source=null;for(JsonNode item:items) if(item.path("deptId").asText().equals(Long.toString(si))) source=item;
        assertNotNull(source);assertTrue(source.path("merged").asBoolean());assertEquals(Long.toString(ti),source.path("mergedIntoDeptId").asText());
        assertEquals(0,json.readTree(http("GET","/departments/"+si+"/users?includeDescendants=true",null,"test-manager").body()).at("/data/total").asInt());
    }
    @Test void defaultProductionAssemblyRefusesAllSixHttpEntrypoints() throws Exception {
        try(var defaultApp=new SpringApplication(DefaultApplication.class).run(LocalTestDatabase.arguments("--server.port=18087","--server.address=127.0.0.1",
                "--logging.level.root=WARN","--spring.main.banner-mode=off"))) {
            String previous=base;base="http://127.0.0.1:18087/api/v1";
            try {
                String code=code("DENY");
                assertEquals(503,http("GET","/departments",null,"test-manager").statusCode());
                assertEquals(503,http("GET","/departments/1/users",null,"test-manager").statusCode());
                assertEquals(503,http("POST","/departments/1/freeze",Map.of("reason","test"),"test-manager").statusCode());
                assertEquals(503,http("POST","/departments/1/unfreeze",Map.of("reason","test"),"test-manager").statusCode());
                assertEquals(503,http("PUT","/integrations/hr/departments/"+code,sync(code,"",1,null,event()),"test-hr").statusCode());
                assertEquals(503,http("POST","/integrations/hr/merges",mergeRequest("source","target",1,event()),"test-hr").statusCode());
            } finally {base=previous;}
        }
    }
    @Test void controlledCrashRecoveryRotatesOwnerAndFencesOldToken() {
        var oa=new TrustedEventContext("OA","task06-test-oa","recovery-test");String event=event();
        var command=new ExternalEventService.Command(event,"OA_DECISION",null,"USER","test-subject",null,Map.of("decision","APPROVE"));
        error(ErrorCode.AUTH_STATE_UNAVAILABLE,() -> events.execute(oa,command,() -> {throw new IllegalStateException("simulated crash");}));
        String oldToken=db.queryForObject("SELECT claim_token FROM sys_external_event WHERE event_id=?",String.class,event);
        db.update("UPDATE sys_external_event SET processing_status='PROCESSING' WHERE event_id=?",event);
        error(ErrorCode.VERSION_CONFLICT,() -> events.execute(oa,command,Map::of));
        AtomicInteger count=new AtomicInteger();events.recover(oa,command,() -> {count.incrementAndGet();return Map.of("done",true);});
        assertEquals(1,count.get());assertNotEquals(oldToken,db.queryForObject("SELECT claim_token FROM sys_external_event WHERE event_id=?",String.class,event));
        assertEquals(0,db.update("UPDATE sys_external_event SET processing_status='REJECTED' WHERE event_id=? AND claim_token=? AND processing_status='PROCESSING'",event,oldToken));
        assertEquals("APPLIED",db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,event));
    }

    @Test void frozenAncestorAndHiddenMergeDestinationArePresentedWithinScope() {
        String root=code("ROOT"),child=code("CHILD"),target=code("HIDDEN");
        long ri=create(root,"",10),ci=create(child,root,1),ti=create(target,"",40);
        var actor=new DepartmentCollaboration.Manager("test");
        service.freeze(actor,ri,true,"ancestor presentation");
        service.freeze(actor,ci,false,"child cannot undo ancestor");
        port.visible=Set.of(ci);
        Map<?,?> item=(Map<?,?>)((List<?>)service.tree(actor,true).get("items")).getFirst();
        assertEquals(1,item.get("status"));assertEquals(true,item.get("effectivelyFrozen"));
        assertEquals("0",item.get("parentId"));assertFalse(item.containsKey("deptPath"));
        port.visible=null;service.freeze(actor,ri,false,"authorized ancestor recovery");
        service.merge(hr,mergeRequest(root,target,11,event()));
        port.visible=Set.of(ri);
        item=(Map<?,?>)((List<?>)service.tree(actor,true).get("items")).getFirst();
        assertEquals(true,item.get("merged"));assertNull(item.get("mergedIntoDeptId"));
        assertEquals(0L,service.users(actor,ri,true,1,20).get("total"));
        assertEquals(ti,((Number)row(ci).get("parent_id")).longValue());
    }

    @Test void eventCallbackWritesAndAppliedRollBackOnUnsafeResult() {
        var oa=new TrustedEventContext("OA","task06-test-oa","transaction-test");
        String event=event(),probe=code("TX-PROBE");
        var command=new ExternalEventService.Command(event,"OA_DECISION",null,"USER","test-subject",null,Map.of("decision","APPROVE"));
        error(ErrorCode.INVALID_ARGUMENT,() -> events.execute(oa,command,() -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertEquals(java.sql.Connection.TRANSACTION_SERIALIZABLE,
                    org.springframework.transaction.support.TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            db.update("INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",probe,"transaction rollback probe");
            return Map.of("accessToken","must never be retained");
        }));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_role WHERE role_code=?",Integer.class,probe));
        var result=db.queryForMap("SELECT processing_status,result_data FROM sys_external_event WHERE event_id=?",event);
        assertEquals("REJECTED",result.get("processing_status"));assertNull(result.get("result_data"));
    }

    @Test void eventOrchestrationRejectsAmbientTransactionAndRecoveryPayloadTampering() {
        var oa=new TrustedEventContext("OA","task06-test-oa","boundary-test");
        String event=event();
        var command=new ExternalEventService.Command(event,"OA_DECISION",null,"USER","test-subject",null,Map.of("decision","APPROVE"));
        var tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        assertThrows(IllegalStateException.class,() -> tx.execute(status -> events.execute(oa,command,Map::of)));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?",Integer.class,event));
        error(ErrorCode.AUDIT_UNAVAILABLE,() -> events.execute(oa,command,() -> {throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE);}));
        String token=db.queryForObject("SELECT claim_token FROM sys_external_event WHERE event_id=?",String.class,event);
        db.update("UPDATE sys_external_event SET payload_ciphertext=? WHERE event_id=?",codec.encrypt("tampered retained canonical payload"),event);
        error(ErrorCode.INTEGRITY_FAILURE,() -> events.recover(oa,command,() -> {fail("tampered payload callback");return Map.of();}));
        assertEquals(token,db.queryForObject("SELECT claim_token FROM sys_external_event WHERE event_id=?",String.class,event));
        assertEquals("PENDING_RECOVERY",db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,event));
    }
}
