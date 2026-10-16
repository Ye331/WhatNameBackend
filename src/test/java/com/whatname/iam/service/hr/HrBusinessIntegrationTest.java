package com.whatname.iam.service.hr;

import com.fasterxml.jackson.databind.*;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.*;
import com.whatname.iam.common.security.*;
import com.whatname.iam.controller.hr.HrController;
import com.whatname.iam.controller.auth.AuthController;
import com.whatname.iam.dto.auth.AuthRequests;
import com.whatname.iam.dto.hr.HrRequests.*;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.auth.*;
import com.whatname.iam.service.department.*;
import com.whatname.iam.service.event.*;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual MySQL/HTTP/transactions/crypto. All C/D writes below are src/test-only substitutes. */
@EnabledIfEnvironmentVariable(named="TASK07_DB_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HrBusinessIntegrationTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @MapperScan({"com.whatname.iam.mapper.user","com.whatname.iam.mapper.auth","com.whatname.iam.mapper.department","com.whatname.iam.mapper.event"})
    @Import({HrService.class,HrController.class,DepartmentService.class,ExternalEventService.class,
            SensitiveDataCodec.class,PasswordHasher.class,PasswordPolicy.class,JwtTokenService.class,
            SmsCodeHasher.class,PublicRequestLimiter.class,AuthService.class,AuthController.class,GlobalExceptionHandler.class})
    static class TestApplication {
        @Bean TestPort testPort(JdbcTemplate db) { return new TestPort(db); }
        @Bean(name="hrClock") MutableClock hrClock() { return new MutableClock(); }
        @Bean SmsSender sms() { return (phone,purpose,code)->{throw new BusinessException(ErrorCode.SMS_DELIVERY_UNAVAILABLE);}; }
    }
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @MapperScan({"com.whatname.iam.mapper.user","com.whatname.iam.mapper.department","com.whatname.iam.mapper.event"})
    @Import({HrService.class,HrController.class,DepartmentService.class,ExternalEventService.class,
            SensitiveDataCodec.class,PasswordHasher.class,GlobalExceptionHandler.class,
            DepartmentIntegrationConfiguration.class,HrIntegrationConfiguration.class,M1SecurityConfiguration.class})
    static class DefaultApplication {}

    static class MutableClock extends Clock {
        volatile Instant now=Instant.parse("2026-10-16T06:00:00Z");
        public Instant instant(){return now;} public ZoneId getZone(){return ZoneOffset.UTC;}
        public Clock withZone(ZoneId z){return this;}
    }
    /** Fake fingerprint, source reconciliation and SQL audit prove A's transaction boundary;
     * they do not implement or validate C's production algorithms or D's Ed25519/service auth. */
    static class TestPort implements DepartmentCollaboration,HrCollaboration,M1SecurityGateway {
        final JdbcTemplate db;volatile String fault="";
        TestPort(JdbcTemplate db){this.db=db;}
        void stage(String s){assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if(fault.equals(s))throw new BusinessException(s.equals("audit")?ErrorCode.AUDIT_UNAVAILABLE:ErrorCode.INTEGRITY_FAILURE);}
        public Manager authenticateManager(HttpServletRequest r,String p){throw new BusinessException(ErrorCode.PERMISSION_DENIED);}
        public TrustedEventContext authenticateHr(HttpServletRequest r,String t){
            if(!"task07-hr".equals(r.getHeader("X-Test-Identity")))throw new BusinessException(ErrorCode.INVALID_SERVICE);
            return new TrustedEventContext("HR","task07-hr","test-request");
        }
        public void verifyTrustedEvent(TrustedEventContext c,String type){
            if(!"task07-hr".equals(c.clientId()) || !"HR".equals(c.sourceSystem())
                    || !Set.of("HR_EMPLOYEE","HR_TRANSFER","HR_TERMINATION").contains(type))throw new BusinessException(ErrorCode.INVALID_SERVICE);
            if(TransactionSynchronizationManager.isActualTransactionActive())stage("service");
        }
        public void authorizeRecovery(TrustedEventContext c,String t){verifyTrustedEvent(c,t);}
        public Set<Long> scope(Manager a,String p,List<Department> d){throw new BusinessException(ErrorCode.PERMISSION_DENIED);}
        public void verifyOrganization(List<Department> all){stage("organization");}
        public void verifyUsers(List<Map<String,Object>> users){stage("verify");}
        public void organizationChanged(Set<Long> d,List<Map<String,Object>> u){stage("scope");}
        public void signAndAudit(String op,Object actor,Map<String,Object> e){
            stage("signature");
            db.update("INSERT INTO sys_operation_log(request_id,source_system,external_event_id,operation_type,target_type,target_id,operation_desc,evidence,status) VALUES(?,'TASK07_TEST',?,?,'USER',?,'task07 C/D substitute',?,1)",
                    UUID.randomUUID().toString(),e.get("eventId"),op,Long.parseLong(e.get("userId").toString()),encode(e));
            stage("audit");
            if(fault.equals("database")) db.update("INSERT INTO sys_operation_log(request_id,operation_type) VALUES(?,NULL)",UUID.randomUUID().toString());
        }
        public void verifyIdentity(UserAccount u){stage("verify");
            String stored=db.queryForObject("SELECT signature FROM sys_data_signature WHERE data_type='TASK07_USER' AND data_id=?",String.class,u.id);
            if(!fingerprint(u).equals(stored))throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
        public Set<Long> authorizeManagement(UserAccount a,String p){throw new BusinessException(ErrorCode.PERMISSION_DENIED);}
        public void signIdentity(UserAccount u){
            stage("identity");seedSignature(u);stage("afterIdentity");
        }
        void seedSignature(UserAccount u){db.update("INSERT INTO sys_data_signature(data_type,data_id,signature,key_id) VALUES('TASK07_USER',?,?,'TASK07_FAKE') ON DUPLICATE KEY UPDATE signature=VALUES(signature)",u.id,fingerprint(u));}
        public void audit(String op,Long actor,Long target,String reason){stage("authAudit");}
        private void lockFacts(long user){
            stage("facts");db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=? ORDER BY id FOR UPDATE",user);
            db.queryForList("SELECT * FROM sys_user_role WHERE user_id=? ORDER BY id FOR UPDATE",user);
            db.queryForList("SELECT * FROM sys_admin WHERE user_id=? FOR UPDATE",user);
            Integer missing=db.queryForObject("SELECT COUNT(*) FROM sys_user_role r WHERE r.user_id=? AND NOT EXISTS (SELECT 1 FROM sys_user_role_source s WHERE s.user_id=r.user_id AND s.role_id=r.role_id AND s.status=1)",Integer.class,user);
            if(missing!=0)throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
        }
        public void revokePosition(TrustedEventContext actor,String event,long user,long dept){
            lockFacts(user);db.update("UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE user_id=? AND status=1 AND source_type='DEPT_POSITION' AND source_dept_id=?",user,dept);
            reconcile(user);stage("revoke");
        }
        public void revokeAll(TrustedEventContext actor,String event,long user){
            lockFacts(user);db.update("UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE user_id=? AND status=1",user);
            db.update("UPDATE sys_admin SET status=0 WHERE user_id=?",user);reconcile(user);stage("revoke");
        }
        void reconcile(long user){
            db.update("DELETE r FROM sys_user_role r WHERE r.user_id=? AND NOT EXISTS (SELECT 1 FROM sys_user_role_source s WHERE s.user_id=r.user_id AND s.role_id=r.role_id AND s.status=1)",user);
            db.update("UPDATE sys_user SET grant_revision=grant_revision+1 WHERE id=?",user);
        }
        public void requireRehireEligible(long user){
            lockFacts(user);
            if(db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=1",Integer.class,user)!=0
                    || db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Integer.class,user)!=0
                    || db.queryForObject("SELECT COUNT(*) FROM sys_admin WHERE user_id=? AND status=1",Integer.class,user)!=0)
                throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            stage("rehire");
        }
        public void departmentChanged(long user,long old,long next){
            assertEquals(next,db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?",Long.class,user));stage("scope");
        }
        static String fingerprint(UserAccount u){
            try {
                String fact=Arrays.asList(u.id,u.username,u.realNameCiphertext,u.phone,u.phoneHash,u.passwordHash,u.employeeNo,u.deptId,u.status,u.source,u.employmentType,u.passwordInitialized,u.sessionVersion,u.grantRevision,u.hrVersion).toString();
                byte[] digest=MessageDigest.getInstance("SHA-256").digest(fact.getBytes(StandardCharsets.UTF_8));
                byte[] doubled=new byte[64];System.arraycopy(digest,0,doubled,0,32);System.arraycopy(digest,0,doubled,32,32);
                return Base64.getEncoder().encodeToString(doubled);
            }catch(Exception e){throw new AssertionError(e);}
        }
        static String encode(Object o){try{return new ObjectMapper().writeValueAsString(o);}catch(Exception e){throw new AssertionError(e);}}
    }

    ServletWebServerApplicationContext app;JdbcTemplate db;HrService hr;UserAccountMapper users;
    TestPort port;SensitiveDataCodec codec;PasswordHasher passwords;AuthService auth;JwtTokenService jwt;
    ObjectMapper json;MutableClock clock;String prefix,deptA,deptB,merged,base;long a,b,mergedId,role1,role2;
    List<Map<String,Object>> seedBefore;final AtomicInteger seq=new AtomicInteger();
    final TrustedEventContext actor=new TrustedEventContext("HR","task07-hr","test-request");
    final OffsetDateTime time=OffsetDateTime.parse("2026-10-16T14:00:00+08:00");
    final List<String> evidence=new CopyOnWriteArrayList<>();
    @BeforeAll void start() throws Exception {
        prefix="T07-"+Long.toString(System.currentTimeMillis(),36)+"-";
        KeyPairGenerator g=KeyPairGenerator.getInstance("RSA");g.initialize(2048);KeyPair keys=g.generateKeyPair();
        List<String> args=new ArrayList<>(List.of("--server.port=18087","--server.address=127.0.0.1",
                "--spring.config.import=","--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/unified_permission?characterEncoding=UTF-8&connectionTimeZone=Asia/Taipei",
                "--spring.datasource.username=iam_dev","--spring.datasource.password="+System.getenv("TASK07_DB_PASSWORD"),
                "--iam.security.data-key="+key(0),"--iam.security.phone-hmac-key="+key(1),"--iam.security.sms-hmac-key="+key(2),
                "--iam.security.jwt-private-key="+Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()),
                "--iam.security.jwt-public-key="+Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
                "--iam.security.jwt-issuer=task07","--iam.security.jwt-audience=task07","--iam.security.jwt-key-id=task07",
                "--logging.level.root=WARN","--spring.main.banner-mode=off"));
        app=(ServletWebServerApplicationContext)new SpringApplication(TestApplication.class).run(args.toArray(String[]::new));
        db=app.getBean(JdbcTemplate.class);hr=app.getBean(HrService.class);users=app.getBean(UserAccountMapper.class);
        port=app.getBean(TestPort.class);codec=app.getBean(SensitiveDataCodec.class);passwords=app.getBean(PasswordHasher.class);
        auth=app.getBean(AuthService.class);jwt=app.getBean(JwtTokenService.class);clock=app.getBean(MutableClock.class);json=app.getBean(ObjectMapper.class);
        base="http://127.0.0.1:18087/api/v1";
        try(var connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
            assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());
        }
        seedBefore=db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'DEV-DEPT-R%' ORDER BY id");assertEquals(111,seedBefore.size());
        deptA=prefix+"A";deptB=prefix+"B";merged=prefix+"M";
        a=department(deptA,null);b=department(deptB,null);mergedId=department(merged,b);
        role1=role("R1");role2=role("R2");
    }
    static String key(int n){byte[] bytes=new byte[32];Arrays.fill(bytes,(byte)n);return Base64.getEncoder().encodeToString(bytes);}
    @AfterEach void reset(){port.fault="";clock.now=time.toInstant();}
    @AfterAll void close(){
        try {if(db!=null){
            db.update("UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE status=1 AND user_id IN (SELECT id FROM sys_user WHERE employee_no LIKE ?)",prefix+"%");
            db.update("DELETE FROM sys_user_role WHERE user_id IN (SELECT id FROM sys_user WHERE employee_no LIKE ?)",prefix+"%");
            db.update("UPDATE sys_admin SET status=0 WHERE user_id IN (SELECT id FROM sys_user WHERE employee_no LIKE ?)",prefix+"%");
            db.update("UPDATE sys_user SET status=3,session_version=session_version+1 WHERE employee_no LIKE ? AND status<>3",prefix+"%");
            db.update("UPDATE sys_dept SET status=0 WHERE dept_code LIKE ?",prefix+"%");
            db.update("UPDATE sys_role SET status=0 WHERE role_code LIKE ?",prefix+"%");
            assertEquals(seedBefore,db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'DEV-DEPT-R%' ORDER BY id"));
            System.out.println("TASK07_FIXTURES prefix="+prefix+" users="+db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE employee_no LIKE ?",Integer.class,prefix+"%")+" seedUnchanged=111");
            System.out.println("TASK07_HTTP_EVIDENCE "+String.join(" | ",evidence));
        }}finally{if(app!=null)app.close();}
    }
    long department(String code,Long target){
        db.update("INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path,merged_into_dept_id) VALUES(0,?,?,'/',NULL)",code,code);
        long id=db.queryForObject("SELECT id FROM sys_dept WHERE dept_code=?",Long.class,code);
        db.update("UPDATE sys_dept SET dept_path=?,merged_into_dept_id=? WHERE id=?","/"+id+"/",target,id);return id;
    }
    long role(String name){db.update("INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",prefix+name,prefix+name);return db.queryForObject("SELECT id FROM sys_role WHERE role_code=?",Long.class,prefix+name);}
    String event(){return prefix+"E"+seq.incrementAndGet();}
    String sg(){return prefix+"SG"+String.format("%06d",seq.incrementAndGet());}
    String phone(){return "198"+String.format("%08d",(System.currentTimeMillis()+seq.incrementAndGet())%100_000_000L);}
    Employee request(String sg,long v,String dept,Action action){return new Employee("HR",event(),v,sg,prefix.replace("-","")+"张三",phone(),dept,action,EmploymentType.FORMAL,time);}
    UserAccount create(){String sg=sg();Employee r=request(sg,1,deptA,Action.UPSERT);hr.employee(actor,sg,r);return users.lockByEmployeeNo(sg);}
    UserAccount row(UserAccount u){return users.findById(u.id);}
    void sources(UserAccount u){
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?),(?,?)",u.id,role1,u.id,role2);
        source(u,role1,"DEPT_POSITION",a,"old1");source(u,role1,"MANUAL",null,"manual");
        source(u,role2,"DEPT_POSITION",a,"old2");source(u,role1,"OA_APPROVAL",null,"oa");
        source(u,role1,"DEPT_POSITION",b,"otherdept");
    }
    void source(UserAccount u,long role,String type,Long dept,String ref){db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id) VALUES(?,?,?,?,?)",u.id,role,type,prefix+ref,dept);}
    int activeSources(UserAccount u){return db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=1",Integer.class,u.id);}
    int relations(UserAccount u){return db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Integer.class,u.id);}
    Transfer transfer(UserAccount u,long v){return new Transfer("HR",event(),v,u.employeeNo,deptA,deptB,time);}
    Termination termination(UserAccount u,long v,OffsetDateTime when){return new Termination("HR",event(),v,u.employeeNo,when);}
    List<Map<String,Object>> facts(UserAccount u){
        List<Map<String,Object>> f=new ArrayList<>(db.queryForList("SELECT * FROM sys_user WHERE id=?",u.id));
        f.addAll(db.queryForList("SELECT * FROM sys_user_role_source WHERE user_id=? ORDER BY id",u.id));
        f.addAll(db.queryForList("SELECT * FROM sys_user_role WHERE user_id=? ORDER BY id",u.id));
        f.addAll(db.queryForList("SELECT * FROM sys_admin WHERE user_id=?",u.id));
        f.addAll(db.queryForList("SELECT * FROM sys_data_signature WHERE data_type='TASK07_USER' AND data_id=?",u.id));
        f.addAll(db.queryForList("SELECT * FROM sys_operation_log WHERE source_system='TASK07_TEST' AND target_id=? ORDER BY id",u.id));return f;
    }
    void expect(ErrorCode code,Runnable work){assertEquals(code,assertThrows(BusinessException.class,work::run).errorCode());}
    JsonNode http(String method,String path,Object body,int status) throws Exception {return httpAt(base,method,path,body,status,"task07-hr");}
    JsonNode httpAt(String server,String method,String path,Object body,int status,String identity) throws Exception {
        HttpRequest req=HttpRequest.newBuilder(URI.create(server+path)).header("Content-Type","application/json").header("X-Test-Identity",identity)
                .method(method,HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response=HttpClient.newHttpClient().send(req,HttpResponse.BodyHandlers.ofString());
        assertEquals(status,response.statusCode(),"HTTP status "+method+" "+path);evidence.add(method+" "+path.replaceAll("T07-[^/]+","<fixture>")+" "+response.statusCode());return json.readTree(response.body());
    }

    @Test void firstUpsertUsesRealInitialsEncryptedDataAndUnknownPlaceholderOverHttp() throws Exception {
        String sg=sg();Employee r=request(sg,10,deptA,Action.UPSERT);
        JsonNode result=http("PUT","/integrations/hr/employees/"+sg,r,200);UserAccount u=users.findById(result.at("/data/userId").asLong());
        assertTrue(u.username.endsWith("zs") || u.username.endsWith("zs"+sg.substring(sg.length()-6)));
        assertEquals("zs",HrLoginName.initials("张三"));assertEquals(1,u.source);assertEquals(1,u.employmentType);assertEquals(0,u.passwordInitialized);
        assertEquals(r.realName(),codec.decrypt(u.realNameCiphertext));assertEquals(r.phone(),codec.decrypt(u.phone));assertEquals(codec.phoneHash(r.phone()),u.phoneHash);
        assertEquals(PasswordHasher.VerificationResult.MISMATCH,passwords.verify("SomePassword92!",u.passwordHash));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,u.id));
        assertEquals(0,relations(u));assertFalse(result.toString().contains(r.phone()));
        String payload=db.queryForObject("SELECT payload_ciphertext FROM sys_external_event WHERE event_id=?",String.class,r.eventId());
        assertTrue(payload.startsWith("gcm1:"));assertFalse(payload.contains(r.phone()));
        http("POST","/auth/login",Map.of("loginType","USERNAME","identifier",u.username,"password","SomePassword92!"),401);
    }
    @Test void profileSyncPreservesIdentityCredentialsAndUsernameAndSupportsOutsourced() throws Exception {
        UserAccount u=create();initializeFixture(u);u=row(u);String stored=u.passwordHash;
        Employee r=new Employee("HR",event(),20L,u.employeeNo,prefix.replace("-","")+"李四",phone(),deptA,null,EmploymentType.OUTSOURCED,time);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,r,200);UserAccount after=row(u);
        assertEquals(u.id,after.id);assertEquals(u.username,after.username);assertEquals(stored,after.passwordHash);assertEquals(1,after.passwordInitialized);
        assertEquals(u.sessionVersion,after.sessionVersion);assertEquals(2,after.employmentType);assertEquals(20,after.hrVersion);
    }
    @Test void phoneConflictRollsBackAccountAndVersionAndNamesGetSgSuffix() throws Exception {
        UserAccount u=create();String sg=sg();Employee r=request(sg,1,deptA,Action.UPSERT);
        http("PUT","/integrations/hr/employees/"+sg,r,200);UserAccount v=users.lockByEmployeeNo(sg);
        assertEquals(HrLoginName.initials(r.realName())+sg.substring(sg.length()-6),v.username);
        var before=facts(v);Employee clash=new Employee("HR",event(),3L,sg,r.realName(),codec.decrypt(u.phone),deptA,Action.UPSERT,EmploymentType.FORMAL,time);
        assertEquals("DUPLICATE_RESOURCE",http("PUT","/integrations/hr/employees/"+sg,clash,409).path("code").asText());assertEquals(before,facts(v));
    }
    @Test void suspendResumePreservesGrantsAndRejectsOldJwtAfterRealLogin() throws Exception {
        UserAccount u=create();sources(u);initializeFixture(u);String token=login(u);long sv=row(u).sessionVersion;
        Employee suspend=request(u.employeeNo,5,deptA,Action.SUSPEND);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,suspend,200);assertEquals(2,row(u).status);assertEquals(sv+1,row(u).sessionVersion);
        assertEquals(5,activeSources(u));assertEquals(2,relations(u));currentSession(token,false);
        http("POST","/auth/login",Map.of("loginType","USERNAME","identifier",u.username,"password","RiverStones27!"),401);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,suspend,200);assertEquals(sv+1,row(u).sessionVersion);
        hr.employee(actor,u.employeeNo,request(u.employeeNo,6,deptA,Action.SUSPEND));assertEquals(sv+1,row(u).sessionVersion);
        hr.employee(actor,u.employeeNo,request(u.employeeNo,7,deptA,Action.UPSERT));assertEquals(2,row(u).status);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,8,deptA,Action.RESUME),200);
        assertEquals(1,row(u).status);assertEquals(5,activeSources(u));assertEquals(2,relations(u));currentSession(token,false);currentSession(login(u),true);
    }
    void initializeFixture(UserAccount u){db.update("UPDATE sys_user SET password_hash=?,password_initialized=1 WHERE id=?",passwords.hash("RiverStones27!"),u.id);port.seedSignature(row(u));}
    String login(UserAccount u) throws Exception {return http("POST","/auth/login",Map.of("loginType","USERNAME","identifier",u.username,"password","RiverStones27!"),200).at("/data/accessToken").asText();}
    void currentSession(String token,boolean allowed){var principal=jwt.verify(token);TransactionTemplate tx=new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        if(allowed)tx.executeWithoutResult(s->assertNotNull(auth.requireCurrentSession(principal)));
        else expect(ErrorCode.INVALID_TOKEN,()->tx.executeWithoutResult(s->auth.requireCurrentSession(principal)));
    }
    @Test void explicitTransferRevokesOnlyOldPositionSourcesAndKeepsSessionVersion() throws Exception {
        UserAccount u=create();sources(u);long sv=u.sessionVersion;
        Transfer r=transfer(u,5);http("POST","/integrations/hr/transfers",r,200);
        assertEquals(b,row(u).deptId);assertEquals(3,activeSources(u));assertEquals(1,relations(u));assertEquals(sv,row(u).sessionVersion);assertEquals(2,row(u).grantRevision);
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=0",Integer.class,u.id));
        assertEquals(3,db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=1 AND (source_type<>'DEPT_POSITION' OR source_dept_id=?)",Integer.class,u.id,b));
        http("POST","/integrations/hr/transfers",r,200);assertEquals(2,row(u).grantRevision);assertEquals(5,row(u).hrVersion);
    }
    @Test void hr02ImplicitTransferUsesSameRevokeAndOnlyOnePersonnelVersion() throws Exception {
        UserAccount u=create();sources(u);Employee r=request(u.employeeNo,40,deptB,Action.UPSERT);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,r,200);assertEquals(b,row(u).deptId);assertEquals(3,activeSources(u));assertEquals(1,relations(u));assertEquals(40,row(u).hrVersion);
        assertEquals(2,row(u).grantRevision);assertEquals(u.sessionVersion,row(u).sessionVersion);
    }
    @Test void futureTerminationRejectsUnchangedThenNewEventSucceedsAtExactBoundary() throws Exception {
        UserAccount u=create();sources(u);initializeFixture(u);String old=login(u);
        db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id) VALUES(?,2,?)",u.id,a);
        var before=facts(u);Termination future=termination(u,9,time.plusSeconds(1));
        assertEquals("BUSINESS_RULE_VIOLATION",http("POST","/integrations/hr/terminations",future,422).path("code").asText());assertEquals(before,facts(u));
        assertEquals("REJECTED",db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,future.eventId()));
        clock.now=time.plusSeconds(1).toInstant();http("POST","/integrations/hr/terminations",future,422);assertEquals(before,facts(u));
        Termination applied=termination(u,9,time.plusSeconds(1));http("POST","/integrations/hr/terminations",applied,200);
        assertEquals(3,row(u).status);assertEquals(9,row(u).hrVersion);assertEquals(0,activeSources(u));assertEquals(0,relations(u));assertEquals(u.sessionVersion+1,row(u).sessionVersion);
        assertEquals(0,db.queryForObject("SELECT status FROM sys_admin WHERE user_id=?",Integer.class,u.id));currentSession(old,false);
        long sv=row(u).sessionVersion;http("POST","/integrations/hr/terminations",applied,200);assertEquals(sv,row(u).sessionVersion);
        assertEquals(applied.effectiveAt().toLocalDateTime(),db.queryForObject("SELECT occurred_at FROM sys_external_event WHERE event_id=?",java.time.LocalDateTime.class,applied.eventId()));
    }
    @Test void departedOnlyExplicitRehireNeverResurrectsGrantsOrJwt() throws Exception {
        UserAccount u=create();sources(u);initializeFixture(u);String old=login(u);String hash=row(u).passwordHash;
        hr.terminate(actor,termination(u,2,time));
        for(Action action:List.of(Action.UPSERT,Action.RESUME,Action.SUSPEND)){
            var before=facts(u);http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,3,deptA,action),422);assertEquals(before,facts(u));
        }
        http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,5,deptA,Action.REHIRE),200);
        assertEquals(1,row(u).status);assertEquals(hash,row(u).passwordHash);assertEquals(u.id,row(u).id);assertEquals(0,relations(u));assertEquals(0,activeSources(u));
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND status=0",Integer.class,u.id));currentSession(old,false);currentSession(login(u),true);
    }
    @Test void unknownTransitionsAndBadDepartmentsAreRejectedWithoutCreatingAccount() throws Exception {
        for(Action action:List.of(Action.SUSPEND,Action.RESUME,Action.REHIRE)) {String sg=sg();http("PUT","/integrations/hr/employees/"+sg,request(sg,1,deptA,action),404);assertNull(users.lockByEmployeeNo(sg));}
        UserAccount u=create();var before=facts(u);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,3,merged,Action.UPSERT),422);assertEquals(before,facts(u));
        http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,3,prefix+"missing",Action.UPSERT),422);assertEquals(before,facts(u));
        Transfer wrong=new Transfer("HR",event(),3L,u.employeeNo,deptB,deptA,time);http("POST","/integrations/hr/transfers",wrong,422);assertEquals(before,facts(u));
        http("POST","/integrations/hr/transfers",new Transfer("HR",event(),3L,sg(),deptA,deptB,time),404);
        http("POST","/integrations/hr/terminations",new Termination("HR",event(),3L,sg(),time),404);
        // Frozen and merged are independent: HR may assign a frozen target, M6 later blocks business.
        db.update("UPDATE sys_dept SET status=0 WHERE id=?",b);
        try {hr.transfer(actor,transfer(u,4));assertEquals(b,row(u).deptId);}finally{db.update("UPDATE sys_dept SET status=1 WHERE id=?",b);}
    }
    @Test void requiredFieldsOffsetEnumsAndTrustedIdentityCannotBeBypassed() throws Exception {
        UserAccount u=create();Employee r=request(u.employeeNo,3,deptA,Action.UPSERT);
        Map<String,Object> data=json.convertValue(r,new com.fasterxml.jackson.core.type.TypeReference<>(){});
        for(String field:List.of("realName","phone","deptCode","employmentType","occurredAt")) {Map<String,Object> bad=new HashMap<>(data);bad.remove(field);http("PUT","/integrations/hr/employees/"+u.employeeNo,bad,400);}
        for(String field:List.of("occurredAt","employmentType","employeeAction")){Map<String,Object> bad=new HashMap<>(data);bad.put(field,field.equals("occurredAt")?"2026-10-16T14:00:00":"INVALID");http("PUT","/integrations/hr/employees/"+u.employeeNo,bad,400);}
        http("PUT","/integrations/hr/employees/wrong",r,400);
        httpAt(base,"PUT","/integrations/hr/employees/"+u.employeeNo,r,401,"forged");
        data.put("sourceSystem","OA");http("PUT","/integrations/hr/employees/"+u.employeeNo,data,401);
        http("POST","/integrations/hr/terminations",Map.of("sourceSystem","HR","eventId",event(),"eventVersion",3,"sgNo",u.employeeNo,"effectiveAt","2026-10-16T14:00:00"),400);
        assertEquals(1,row(u).hrVersion);
    }
    @Test void idempotencyConflictOldOrderAndPersonnelSequencesAreIndependent() throws Exception {
        UserAccount u=create();Employee r=request(u.employeeNo,50,deptA,Action.UPSERT);
        JsonNode first=http("PUT","/integrations/hr/employees/"+u.employeeNo,r,200);var before=facts(u);
        assertEquals(first.path("data"),http("PUT","/integrations/hr/employees/"+u.employeeNo,r,200).path("data"));assertEquals(before,facts(u));
        Employee changed=new Employee("HR",r.eventId(),r.eventVersion(),u.employeeNo,r.realName(),phone(),deptA,r.employeeAction(),r.employmentType(),time);
        assertEquals("IDEMPOTENCY_CONFLICT",http("PUT","/integrations/hr/employees/"+u.employeeNo,changed,409).path("code").asText());
        assertEquals("INVALID_HR_ORDER",http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,49,deptA,Action.SUSPEND),422).path("code").asText());assertEquals(before,facts(u));
        UserAccount independent=create();assertEquals(1,independent.hrVersion);
    }
    @Test void concurrentSameEventAppliesOnceAndDistinctVersionsNeverRegress() throws Exception {
        UserAccount u=create();Employee r=request(u.employeeNo,5,deptA,Action.SUSPEND);
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start=new CountDownLatch(1);Callable<String> task=()->{start.await();try{hr.employee(actor,u.employeeNo,r);return "APPLIED";}catch(BusinessException e){return e.errorCode().name();}};
            Future<String> f1=pool.submit(task),f2=pool.submit(task);start.countDown();
            Set<String> statuses=new HashSet<>(List.of(f1.get(40,TimeUnit.SECONDS),f2.get(40,TimeUnit.SECONDS)));
            assertTrue(statuses.contains("APPLIED"));assertTrue(Set.of("APPLIED","VERSION_CONFLICT").containsAll(statuses));assertEquals(u.sessionVersion+1,row(u).sessionVersion);
            Future<?> high=pool.submit(()->hr.employee(actor,u.employeeNo,request(u.employeeNo,30,deptA,Action.RESUME)));
            Future<?> low=pool.submit(()->{try{hr.employee(actor,u.employeeNo,request(u.employeeNo,20,deptA,Action.UPSERT));}catch(BusinessException e){assertEquals(ErrorCode.INVALID_HR_ORDER,e.errorCode());}});
            high.get(40,TimeUnit.SECONDS);low.get(40,TimeUnit.SECONDS);assertEquals(30,row(u).hrVersion);
        }finally{pool.shutdownNow();}
    }
    @Test void transferFailuresRollbackSourcesDepartmentVersionSignatureAndAudit() throws Exception {
        UserAccount u=create();sources(u);
        for(String fault:List.of("organization","service","verify","facts","revoke","scope","identity","afterIdentity","signature","audit","database")){
            var before=facts(u);port.fault=fault;Transfer r=transfer(u,5);http("POST","/integrations/hr/transfers",r,503);port.fault="";
            assertEquals(before,facts(u),"rollback stage "+fault);assertEquals("PENDING_RECOVERY",db.queryForObject("SELECT processing_status FROM sys_external_event WHERE event_id=?",String.class,r.eventId()));
        }
    }
    @Test void terminationFailuresRollbackAdministratorAndAllEntitlements() throws Exception {
        UserAccount u=create();sources(u);db.update("INSERT INTO sys_admin(user_id,admin_level,dept_id) VALUES(?,2,?)",u.id,a);
        for(String fault:List.of("revoke","afterIdentity","signature","audit","database")){
            var before=facts(u);port.fault=fault;http("POST","/integrations/hr/terminations",termination(u,5,time),503);port.fault="";assertEquals(before,facts(u));
        }
    }
    @Test void implicitTransferAndCreationFailuresDoNotLeavePartialAccount() throws Exception {
        UserAccount u=create();sources(u);var before=facts(u);port.fault="audit";
        http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,9,deptB,Action.SUSPEND),503);assertEquals(before,facts(u));
        String sg=sg();http("PUT","/integrations/hr/employees/"+sg,request(sg,1,deptA,Action.UPSERT),503);assertNull(users.lockByEmployeeNo(sg));
    }
    @Test void absentSourceHistoryAndMissingProductionCRejectInsteadOfReportingSuccess() throws Exception {
        UserAccount u=create();db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",u.id,role1);
        var before=facts(u);http("POST","/integrations/hr/transfers",transfer(u,3),503);assertEquals(before,facts(u));
        HrService unavailable=new HrService(app.getBean(ExternalEventService.class),app.getBean(com.whatname.iam.mapper.department.OrganizationWriteMapper.class),
                app.getBean(DepartmentService.class),port,users,port,new HrIntegrationConfiguration().unavailableHrCollaboration(),codec,passwords,clock);
        expect(ErrorCode.AUTH_STATE_UNAVAILABLE,()->unavailable.terminate(actor,termination(u,4,time)));assertEquals(before,facts(u));
    }
    @Test void concurrentPhoneAndSgUniquenessHaveOneWinnerAndNoPartialAccount() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        try {
            String commonPhone=phone();String sg1=sg(),sg2=sg();
            Employee r1=new Employee("HR",event(),1L,sg1,prefix.replace("-","")+"王五",commonPhone,deptA,Action.UPSERT,EmploymentType.FORMAL,time);
            Employee r2=new Employee("HR",event(),1L,sg2,r1.realName(),commonPhone,deptA,Action.UPSERT,EmploymentType.FORMAL,time);
            CountDownLatch start=new CountDownLatch(1);
            List<Future<String>> work=new ArrayList<>();
            for(Employee r:List.of(r1,r2))work.add(pool.submit(()->{start.await();try{hr.employee(actor,r.sgNo(),r);return "APPLIED";}catch(BusinessException e){return e.errorCode().name();}}));
            start.countDown();List<String> outcomes=new ArrayList<>();for(Future<String> f:work)outcomes.add(f.get(40,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(outcomes,"APPLIED"));assertEquals(1,Collections.frequency(outcomes,"DUPLICATE_RESOURCE"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE employee_no IN (?,?)",Integer.class,sg1,sg2));
            String commonSg=sg();Employee initial=request(commonSg,1,deptA,Action.UPSERT);
            Future<String> one=pool.submit(()->{try{hr.employee(actor,commonSg,initial);return "APPLIED";}catch(BusinessException e){return e.errorCode().name();}});
            Employee other=new Employee("HR",event(),1L,commonSg,initial.realName(),initial.phone(),deptA,Action.UPSERT,EmploymentType.FORMAL,time);
            Future<String> two=pool.submit(()->{try{hr.employee(actor,commonSg,other);return "APPLIED";}catch(BusinessException e){return e.errorCode().name();}});
            List<String> same=List.of(one.get(40,TimeUnit.SECONDS),two.get(40,TimeUnit.SECONDS));assertEquals(1,Collections.frequency(same,"APPLIED"));assertEquals(1,Collections.frequency(same,"INVALID_HR_ORDER"));
            assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE employee_no=?",Integer.class,commonSg));
        }finally{pool.shutdownNow();}
    }
    @Test void rehireFailsClosedOnRemainingEntitlementsAndCFailure() throws Exception {
        UserAccount u=create();hr.terminate(actor,termination(u,2,time));
        var before=facts(u);port.fault="rehire";http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,4,deptA,Action.REHIRE),503);port.fault="";assertEquals(before,facts(u));
        source(u,role1,"MANUAL",null,"invalidRehire");before=facts(u);
        http("PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,4,deptA,Action.REHIRE),503);assertEquals(before,facts(u));
    }
    @Test void productionDefaultAllThreeHttpEntrypointsFailClosed() throws Exception {
        try(var ordinary=(ServletWebServerApplicationContext)new SpringApplication(DefaultApplication.class).run(
                "--server.port=18088","--server.address=127.0.0.1","--spring.config.import=",
                "--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/unified_permission?characterEncoding=UTF-8&connectionTimeZone=Asia/Taipei",
                "--spring.datasource.username=iam_dev","--spring.datasource.password="+System.getenv("TASK07_DB_PASSWORD"),
                "--logging.level.root=WARN","--spring.main.banner-mode=off")) {
            String server="http://127.0.0.1:18088/api/v1";UserAccount u=create();var before=facts(u);
            httpAt(server,"PUT","/integrations/hr/employees/"+u.employeeNo,request(u.employeeNo,3,deptA,Action.UPSERT),503,"task07-hr");
            httpAt(server,"POST","/integrations/hr/transfers",transfer(u,3),503,"task07-hr");
            httpAt(server,"POST","/integrations/hr/terminations",termination(u,3,time),503,"task07-hr");assertEquals(before,facts(u));
        }
    }
}
