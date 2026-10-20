package com.whatname.iam.service.auth;

import com.whatname.iam.support.LocalTestDatabase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.auth.AuthRequests.*;
import com.whatname.iam.dto.user.UserRequests;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.user.UserService;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.PreparedStatement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;

/** Real personal MySQL + real HTTP. C/D and SMS are explicitly TEST ONLY substitutes. */
@EnabledIfEnvironmentVariable(named="TASK05_DB_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class M1MySqlIntegrationTest {
    static final String PREFIX="t05_"+UUID.randomUUID().toString().replace("-","").substring(0,10)+"_";
    static final String ORIGINAL="RiverStones27!",NEXT="AmberClouds63!";
    static final AtomicInteger sequence=new AtomicInteger(new SecureRandom().nextInt(800000));
    static final List<String> HTTP_EVIDENCE=new CopyOnWriteArrayList<>();
    ConfigurableApplicationContext context;
    JdbcTemplate db;AuthService auth;UserService users;UserAccountMapper mapper;SensitiveDataCodec codec;JwtTokenService jwt;
    TestGateway gateway;TestSms sms;PasswordHasher hasher;ObjectMapper json;
    String originalHash,placeholder;long allowedDept,outsideDept,mergedDept,frozenDept;
    final Set<Long> fixtureIds=ConcurrentHashMap.newKeySet();
    final Set<String> fixturePhones=ConcurrentHashMap.newKeySet();
    final List<Long> fixtureDepts=new ArrayList<>();
    UserAccount admin;String adminToken;
    Map<String,Object> settings;
    List<Map<String,Object>> originalDepartments;

    @BeforeAll void startRealApplicationAndCreateIsolatedFixtures() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);KeyPair keys=generator.generateKeyPair();
        settings=new HashMap<>();
        settings.put("server.port","18085");settings.put("server.address","127.0.0.1");
        settings.put("iam.security.data-key",randomKey());settings.put("iam.security.phone-hmac-key",randomKey());
        settings.put("iam.security.sms-hmac-key",randomKey());
        settings.put("iam.security.jwt-private-key",Base64.getEncoder().encodeToString(keys.getPrivate().getEncoded()));
        settings.put("iam.security.jwt-public-key",Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()));
        settings.put("iam.security.jwt-issuer","task05-issuer");settings.put("iam.security.jwt-audience","task05-api");settings.put("iam.security.jwt-key-id","task05-random");
        context=new SpringApplicationBuilder(TestBootstrap.class,TestWiring.class).profiles("task05-test").properties(settings)
                .run(LocalTestDatabase.arguments("--server.port=18085","--server.address=127.0.0.1"));
        db=context.getBean(JdbcTemplate.class);auth=context.getBean(AuthService.class);users=context.getBean(UserService.class);
        mapper=context.getBean(UserAccountMapper.class);codec=context.getBean(SensitiveDataCodec.class);jwt=context.getBean(JwtTokenService.class);
        gateway=context.getBean(TestGateway.class);sms=context.getBean(TestSms.class);hasher=context.getBean(PasswordHasher.class);json=context.getBean(ObjectMapper.class);
        try(var connection=Objects.requireNonNull(db.getDataSource()).getConnection()) {
            assertEquals("MySQL",connection.getMetaData().getDatabaseProductName());
        }
        // Does not alter schema or modify any of task04's 111 rows. Task06 owns migration 01.
        db.queryForObject("SELECT merged_into_dept_id FROM sys_dept LIMIT 1",Long.class);
        originalDepartments=db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'DEV-DEPT-R%' ORDER BY id");
        assertEquals(111,originalDepartments.size());
        long root=db.queryForObject("SELECT id FROM sys_dept WHERE dept_code='DEV-DEPT-R0002'",Long.class);
        String rootPath=db.queryForObject("SELECT dept_path FROM sys_dept WHERE id=?",String.class,root);
        allowedDept=department(root,rootPath,"allowed",1,null);outsideDept=department(root,rootPath,"outside",1,null);
        mergedDept=department(root,rootPath,"merged",1,allowedDept);frozenDept=department(root,rootPath,"frozen",0,null);
        originalHash=hasher.hash(ORIGINAL);placeholder=hasher.hash(UUID.randomUUID().toString());
    }

    @BeforeEach void manager() {
        gateway.failSign=false;gateway.failAudit=false;gateway.failVerify=false;gateway.denyFunction=false;gateway.failDatabase=false;sms.fail=false;sms.failSend=false;
        admin=seed("admin",1,1,allowedDept,3);
        gateway.managers.put(admin.id,Set.of(allowedDept,mergedDept,frozenDept));
        adminToken="Bearer "+jwt.issue(admin.id,admin.sessionVersion);
    }

    @AfterAll void closeAndRetireOnlyRegisteredTestFixtures() throws Exception {
        if(db!=null) {
            // Accounts and logs are permanent (also enforced by DB triggers). Retire only
            // registered fixtures, keep history/evidence and freeze the test-only departments.
            for(Long id:fixtureIds) {
                String name=db.queryForObject("SELECT username FROM sys_user WHERE id=?",String.class,id);
                assertTrue(name.startsWith(PREFIX) || (name.startsWith("temp") &&
                        db.queryForObject("SELECT temp_reason FROM sys_user WHERE id=?",String.class,id).startsWith(PREFIX)));
                db.update("UPDATE sys_user SET status=2,session_version=session_version+1 WHERE id=?",id);
                gateway.seedSignature(mapper.findById(id));
            }
            for(String phoneHash:fixturePhones) db.update("UPDATE sys_sms_code SET consumed_at=COALESCE(consumed_at,NOW()) WHERE phone_hash=?",phoneHash);
            for(Long id:fixtureDepts) db.update("UPDATE sys_dept SET status=0 WHERE id=? AND dept_code LIKE ?",id,PREFIX+"%");
            assertEquals(originalDepartments,db.queryForList("SELECT * FROM sys_dept WHERE dept_code LIKE 'DEV-DEPT-R%' ORDER BY id"));
            assertEquals(fixtureIds.size(),db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE status=2 AND (username LIKE ? OR temp_reason LIKE ?)",Integer.class,PREFIX+"%",PREFIX+"%"));
            System.out.println("TASK05_FIXTURES prefix="+PREFIX+" retiredUsers="+fixtureIds.size()+" frozenDepartments="+fixtureDepts.size()+" originalDepartmentsUnchanged=111");
        }
        if(context!=null) context.close();
        System.out.println("TASK05_HTTP_EVIDENCE "+String.join(" | ",HTTP_EVIDENCE));
    }

    @Test void usernameAndPhonePasswordLoginOverRealHttpWithSafeResponses() throws Exception {
        UserAccount u=seed("login",1,1,allowedDept,2);
        for(LoginType type:LoginType.values()) {
            JsonNode body=http("POST","/auth/login",Map.of("loginType",type.name(),"identifier",type==LoginType.USERNAME?u.username:phone(u),"password",ORIGINAL),null,200);
            assertEquals(u.id.toString(),body.at("/data/userId").asText());assertEquals(3600,body.at("/data/expiresIn").asInt());
            assertEquals(u.id,jwt.verify(body.at("/data/accessToken").asText()).userId());
            assertFalse(body.toString().contains("passwordHash"));
        }
        assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sys_login_log WHERE user_id=? AND login_status=1",Integer.class,u.id));
        http("POST","/auth/login",Map.of("loginType","SMS","identifier",phone(u),"password",ORIGINAL),null,400);
    }

    @Test void unavailableAccountsAndUnknownIdentifiersHaveSamePublicError() throws Exception {
        for(int[] state:new int[][]{{0,1},{1,2},{1,3}}) {
            UserAccount u=seed("denied",state[0],state[1],allowedDept,2);
            assertEquals("INVALID_CREDENTIALS",http("POST","/auth/login",Map.of("loginType","USERNAME","identifier",u.username,"password",ORIGINAL),null,401).path("code").asText());
        }
        assertEquals("INVALID_CREDENTIALS",http("POST","/auth/login",Map.of("loginType","USERNAME","identifier",PREFIX+"absent","password",ORIGINAL),null,401).path("code").asText());
    }

    @Test void fiveDurableFailuresLockFifteenMinutesThenSuccessResetsCounters() {
        UserAccount u=seed("locked",1,1,allowedDept,2);
        for(int i=1;i<=5;i++) {expect(ErrorCode.INVALID_CREDENTIALS,()->login(u,"WrongPassword91"));assertEquals(i,mapper.findById(u.id).pwdFailedCount);}
        var locked=mapper.findById(u.id);assertNotNull(locked.pwdLockedUntil);
        long remaining=java.time.Duration.between(java.time.LocalDateTime.now(),locked.pwdLockedUntil).getSeconds();assertTrue(remaining>=880 && remaining<=901);
        expect(ErrorCode.INVALID_CREDENTIALS,()->login(u,ORIGINAL));assertEquals(5,mapper.findById(u.id).pwdFailedCount);
        db.update("UPDATE sys_user SET pwd_locked_until=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE id=?",u.id);
        assertNotNull(login(u,ORIGINAL));assertEquals(0,mapper.findById(u.id).pwdFailedCount);assertNull(mapper.findById(u.id).pwdLockedUntil);
    }

    @Test void frozenDepartmentStillAllowsLoginAndPasswordProofs() {
        UserAccount u=seed("frozen",1,1,frozenDept,2);assertNotNull(login(u,ORIGINAL));
        String code=send(u,Purpose.RESET_PASSWORD);auth.changePassword(new Password(phone(u),code,NEXT),false);assertNotNull(login(u,NEXT));
    }

    @Test void initializeAndRepeatOverHttpPersistsHistoryAndConsumesOnce() throws Exception {
        UserAccount u=seed("initialize",0,1,allowedDept,2);
        JsonNode sent=http("POST","/auth/sms/send",Map.of("phone",phone(u),"purpose","SET_PASSWORD"),null,200);
        assertTrue(sent.at("/data/accepted").asBoolean());assertFalse(sent.toString().contains("codeHash"));
        String code=sms.code(phone(u),Purpose.SET_PASSWORD);
        Map<String,String> request=Map.of("phone",phone(u),"code",code,"newPassword",NEXT);
        http("POST","/auth/password/initialize",request,null,200);
        http("POST","/auth/password/initialize",request,null,401);
        assertEquals(1,mapper.findById(u.id).passwordInitialized);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,u.id));
        assertNotNull(login(u,NEXT));
    }

    @Test void concurrentInitializeSameProofHasExactlyOneCommittedWinner() throws Exception {
        UserAccount u=seed("race",0,1,allowedDept,2);String code=send(u,Purpose.SET_PASSWORD);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch ready=new CountDownLatch(2),go=new CountDownLatch(1);
        Callable<Boolean> attempt=()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));try {auth.changePassword(new Password(phone(u),code,NEXT),true);return true;}catch(BusinessException e){assertEquals(ErrorCode.INVALID_CREDENTIALS,e.errorCode());return false;}};
        try {
            Future<Boolean> a=pool.submit(attempt),b=pool.submit(attempt);assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();
            assertEquals(1,(a.get(15,TimeUnit.SECONDS)?1:0)+(b.get(15,TimeUnit.SECONDS)?1:0));
        } finally {pool.shutdownNow();}
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,u.id));
    }

    @Test void resetInvalidatesOldPasswordAndEveryOldSessionOverHttp() throws Exception {
        UserAccount u=seed("reset",1,1,allowedDept,2);String old="Bearer "+login(u,ORIGINAL).accessToken();
        gateway.managers.put(u.id,Set.of(allowedDept));
        String code=send(u,Purpose.RESET_PASSWORD);
        http("POST","/auth/password/reset",Map.of("phone",phone(u),"code",code,"newPassword",NEXT),null,200);
        assertEquals(2,mapper.findById(u.id).sessionVersion);expect(ErrorCode.INVALID_CREDENTIALS,()->login(u,ORIGINAL));
        expect(ErrorCode.INVALID_TOKEN,()->users.list(old,null,null,null,null,1,20));
        assertNotNull(login(u,NEXT));
        http("POST","/auth/logout",null,old,200); // old sv may still revoke only itself
    }

    @Test void wrongPurposeExpiryAndFiveAttemptsRemainDurableAfterFailure() {
        UserAccount u=seed("proof",0,1,allowedDept,2);String code=send(u,Purpose.SET_PASSWORD);
        expect(ErrorCode.INVALID_CREDENTIALS,()->auth.changePassword(new Password(phone(u),code,NEXT),false));
        for(int i=1;i<=5;i++) {
            String wrong=code.equals("000000")?"000001":"000000";
            expect(ErrorCode.INVALID_CREDENTIALS,()->auth.changePassword(new Password(phone(u),wrong,NEXT),true));
            assertEquals(i,db.queryForObject("SELECT attempts FROM sys_sms_code WHERE phone_hash=? AND purpose='SET_PASSWORD' ORDER BY id DESC LIMIT 1",Integer.class,u.phoneHash));
        }
        expect(ErrorCode.INVALID_CREDENTIALS,()->auth.changePassword(new Password(phone(u),code,NEXT),true));
        ageSms(u,Purpose.SET_PASSWORD);String expired=send(u,Purpose.SET_PASSWORD);
        db.update("UPDATE sys_sms_code SET expires_at=DATE_SUB(NOW(),INTERVAL 1 SECOND) WHERE phone_hash=?",u.phoneHash);
        expect(ErrorCode.INVALID_CREDENTIALS,()->auth.changePassword(new Password(phone(u),expired,NEXT),true));
        assertEquals(0,mapper.findById(u.id).passwordInitialized);
    }

    @Test void resendIsRateLimitedRevokesOldProofAndNeverStoresRawCode() {
        UserAccount u=seed("resend",0,1,allowedDept,2);String old=send(u,Purpose.SET_PASSWORD);
        expect(ErrorCode.RATE_LIMITED,()->send(u,Purpose.SET_PASSWORD));ageSms(u,Purpose.SET_PASSWORD);
        String replacement=send(u,Purpose.SET_PASSWORD);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=? AND consumed_at IS NULL",Integer.class,u.phoneHash));
        String hash=db.queryForObject("SELECT code_hash FROM sys_sms_code WHERE phone_hash=? ORDER BY id DESC LIMIT 1",String.class,u.phoneHash);
        assertEquals(64,hash.length());assertNotEquals(replacement,hash);
        assertNotEquals(old,replacement);
        expect(ErrorCode.INVALID_CREDENTIALS,()->auth.changePassword(new Password(phone(u),old,NEXT),true));
        auth.changePassword(new Password(phone(u),replacement,NEXT),true);
    }

    @Test void unknownPhoneHasOpaqueResponseAndNoSmsButSameResendLimit() throws Exception {
        String unknown=nextPhone();String hash=codec.phoneHash(unknown);fixturePhones.add(hash);
        JsonNode result=http("POST","/auth/sms/send",Map.of("phone",unknown,"purpose","SET_PASSWORD"),null,200);
        assertTrue(result.at("/data/accepted").asBoolean());assertNull(sms.deliveries.get(unknown+":SET_PASSWORD"));
        http("POST","/auth/sms/send",Map.of("phone",unknown,"purpose","SET_PASSWORD"),null,429);
    }

    @Test void providerFailureAndAuditFailureDoNotCommitProofOrReportSuccess() {
        UserAccount u=seed("smsfail",0,1,allowedDept,2);sms.fail=true;
        expect(ErrorCode.SMS_DELIVERY_UNAVAILABLE,()->send(u,Purpose.SET_PASSWORD));assertEquals(0,smsCount(u));
        sms.fail=false;gateway.failAudit=true;expect(ErrorCode.AUDIT_UNAVAILABLE,()->send(u,Purpose.SET_PASSWORD));assertEquals(0,smsCount(u));
        gateway.failAudit=false;sms.failSend=true;
        expect(ErrorCode.SMS_DELIVERY_UNAVAILABLE,()->send(u,Purpose.SET_PASSWORD));assertEquals(0,smsCount(u));
        sms.failSend=false;assertNotNull(send(u,Purpose.SET_PASSWORD));
    }

    @Test void simultaneousFirstSmsForUnknownPhoneCommitsOnlyOneProof() throws Exception {
        String phone=nextPhone(),hash=codec.phoneHash(phone);fixturePhones.add(hash);
        ExecutorService pool=Executors.newFixedThreadPool(4);CountDownLatch ready=new CountDownLatch(4),go=new CountDownLatch(1);
        try {
            List<Future<Boolean>> calls=new ArrayList<>();
            for(int i=0;i<4;i++) calls.add(pool.submit(()->{ready.countDown();assertTrue(go.await(5,TimeUnit.SECONDS));
                try {auth.sendSms(new SendSms(phone,Purpose.SET_PASSWORD));return true;}
                catch(BusinessException e){assertEquals(ErrorCode.RATE_LIMITED,e.errorCode());return false;}}));
            assertTrue(ready.await(5,TimeUnit.SECONDS));go.countDown();int success=0;
            for(var call:calls) if(call.get(20,TimeUnit.SECONDS)) success++;
            assertEquals(1,success);assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=?",Integer.class,hash));
        } finally {pool.shutdownNow();}
    }

    @Test void temporarySignatureAuditAndRealDatabaseFailuresRollbackOverHttp() throws Exception {
        for(String fault:List.of("signature","audit","database")) {
            String phone=nextPhone(),hash=codec.phoneHash(phone);fixturePhones.add(hash);
            gateway.failSign=fault.equals("signature");gateway.failAudit=fault.equals("audit");gateway.failDatabase=fault.equals("database");
            int before=db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE source_system='TASK05_TEST' AND user_id=?",Integer.class,admin.id);
            http("POST","/users/temporary",Map.of("realName","Test Person","phone",phone,"deptId",Long.toString(allowedDept),"reason",PREFIX+fault),adminToken,503);
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone_hash=?",Integer.class,hash));
            assertEquals(before,db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE source_system='TASK05_TEST' AND user_id=?",Integer.class,admin.id));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_data_signature s LEFT JOIN sys_user u ON u.id=s.data_id WHERE s.data_type='TASK05_USER' AND u.id IS NULL",Integer.class));
        }
        gateway.failSign=false;gateway.failAudit=false;gateway.failDatabase=false;
    }

    @Test void actualIdentityAndRecentFivePasswordsAreEnforcedAndHistoryIsTrimmed() {
        UserAccount u=seed("history",1,1,allowedDept,2);String code=send(u,Purpose.RESET_PASSWORD);
        for(String bad:List.of(ORIGINAL,"testperson77!","Contains"+phone(u),u.username+"77"))
            expect(ErrorCode.BUSINESS_RULE_VIOLATION,()->auth.changePassword(new Password(phone(u),code,bad),false));
        auth.changePassword(new Password(phone(u),code,NEXT),false);
        // Fill older history with distinct real scrypt encodings to test SQL retention and ordering.
        for(int i=0;i<5;i++) db.update("INSERT INTO sys_password_history(user_id,password_hash,created_at) VALUES(?,?,DATE_SUB(NOW(),INTERVAL ? DAY))",u.id,originalHash,i+1);
        ageSms(u,Purpose.RESET_PASSWORD);String reset=send(u,Purpose.RESET_PASSWORD);
        expect(ErrorCode.BUSINESS_RULE_VIOLATION,()->auth.changePassword(new Password(phone(u),reset,ORIGINAL),false));
        auth.changePassword(new Password(phone(u),reset,"WinterLantern84!"),false);
        assertEquals(5,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,u.id));
        assertEquals(mapper.findById(u.id).passwordHash,db.queryForObject("SELECT password_hash FROM sys_password_history WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",String.class,u.id));
    }

    @Test void passwordSignatureOrAuditFailureRollsBackPasswordHistoryProofVersionAndFakeEvidence() {
        UserAccount u=seed("rollback",1,1,allowedDept,2);String proof=send(u,Purpose.RESET_PASSWORD);
        String before=fakeSignature(u.id);int auditBefore=auditCount(u.id);
        gateway.failSign=true;expect(ErrorCode.INTEGRITY_FAILURE,()->auth.changePassword(new Password(phone(u),proof,NEXT),false));gateway.failSign=false;
        assertUnchangedPassword(u,proof,before,auditBefore);
        gateway.failAudit=true;expect(ErrorCode.AUDIT_UNAVAILABLE,()->auth.changePassword(new Password(phone(u),proof,NEXT),false));gateway.failAudit=false;
        assertUnchangedPassword(u,proof,before,auditBefore);
        auth.changePassword(new Password(phone(u),proof,NEXT),false);assertEquals(2,mapper.findById(u.id).sessionVersion);
    }

    @Test void createTemporaryOverHttpEncryptsIdentityRecordsTrustedCreatorAndNoRoles() throws Exception {
        String phone=nextPhone();fixturePhones.add(codec.phoneHash(phone));
        JsonNode response=http("POST","/users/temporary",Map.of("realName","Test Person","phone",phone,"deptId",Long.toString(allowedDept),"reason",PREFIX+"support"),adminToken,200);
        long id=response.at("/data/userId").asLong();fixtureIds.add(id);UserAccount u=mapper.findById(id);
        assertTrue(u.username.matches("temp[0-9]+"));assertEquals(2,u.source);assertNull(u.employeeNo);assertNull(u.employmentType);
        assertEquals(admin.id,u.createdBy);assertEquals(0,u.passwordInitialized);assertFalse(u.phone.contains(phone));
        assertEquals("Test Person",codec.decrypt(u.realNameCiphertext));assertEquals(phone,codec.decrypt(u.phone));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,id));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",Integer.class,id));
        expect(ErrorCode.INVALID_CREDENTIALS,()->login(u,ORIGINAL));
        http("POST","/users/temporary",Map.of("realName","Test Person","phone",phone,"deptId",Long.toString(allowedDept),"reason",PREFIX+"duplicate"),adminToken,409);
    }

    @Test void unsupportedTemporaryNamesRejectBeforeAnyAccountOrEvidenceWriteOverHttp() throws Exception {
        for(String name:List.of("··","𠀀","重".repeat(9))) {
            String phone=nextPhone(),hash=codec.phoneHash(phone);fixturePhones.add(hash);
            var audits=db.queryForList("SELECT * FROM sys_operation_log WHERE source_system='TASK05_TEST' AND user_id=? ORDER BY id",admin.id);
            int signatures=db.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_type='TASK05_USER'",Integer.class);
            try {
                assertEquals("BUSINESS_RULE_VIOLATION",http("POST","/users/temporary",Map.of("realName",name,"phone",phone,
                        "deptId",Long.toString(allowedDept),"reason",PREFIX+"badname"),adminToken,422).path("code").asText());
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone_hash=?",Integer.class,hash));
                assertEquals(audits,db.queryForList("SELECT * FROM sys_operation_log WHERE source_system='TASK05_TEST' AND user_id=? ORDER BY id",admin.id));
                assertEquals(signatures,db.queryForObject("SELECT COUNT(*) FROM sys_data_signature WHERE data_type='TASK05_USER'",Integer.class));
            } finally {
                // Register even an unexpected old-implementation success for normal fixture retirement.
                fixtureIds.addAll(db.queryForList("SELECT id FROM sys_user WHERE phone_hash=?",Long.class,hash));
            }
        }
    }

    @Test void supportedTemporaryNamesCanInitializeAndResetWithDeliveredProofOverHttp() throws Exception {
        for(String name:List.of("张三","Test Person","单明")) {
            String phone=nextPhone();fixturePhones.add(codec.phoneHash(phone));
            long id=http("POST","/users/temporary",Map.of("realName",name,"phone",phone,"deptId",Long.toString(allowedDept),
                    "reason",PREFIX+"nameflow"),adminToken,200).at("/data/userId").asLong();fixtureIds.add(id);
            http("POST","/auth/sms/send",Map.of("phone",phone,"purpose","SET_PASSWORD"),null,200);
            http("POST","/auth/password/initialize",Map.of("phone",phone,"code",sms.code(phone,Purpose.SET_PASSWORD),"newPassword",NEXT),null,200);
            UserAccount initialized=mapper.findById(id);assertEquals(1,initialized.passwordInitialized);assertEquals(1,initialized.sessionVersion);
            assertNotNull(login(initialized,NEXT));
            http("POST","/auth/sms/send",Map.of("phone",phone,"purpose","RESET_PASSWORD"),null,200);
            http("POST","/auth/password/reset",Map.of("phone",phone,"code",sms.code(phone,Purpose.RESET_PASSWORD),"newPassword","WinterLantern84!"),null,200);
            UserAccount reset=mapper.findById(id);assertEquals(2,reset.sessionVersion);assertEquals(1,reset.passwordInitialized);
            assertNotNull(login(reset,"WinterLantern84!"));
            assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,id));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=? AND consumed_at IS NULL",Integer.class,reset.phoneHash));
        }
    }

    @Test void olderCorruptHistoryReturns503AndPreservesAllPasswordFactsOverHttp() throws Exception {
        UserAccount u=seed("corruptold",1,1,allowedDept,2);
        // Legal nonempty ASCII fixture; current password and newest history stay valid and equal.
        db.update("INSERT INTO sys_password_history(user_id,password_hash,created_at) VALUES(?,?,DATE_SUB(NOW(),INTERVAL 1 DAY))",u.id,"task15-invalid-encoding");
        assertEquals(u.passwordHash,db.queryForObject("SELECT password_hash FROM sys_password_history WHERE user_id=? ORDER BY created_at DESC,id DESC LIMIT 1",String.class,u.id));
        String proof=send(u,Purpose.RESET_PASSWORD);var before=passwordFacts(u);
        for(int attempt=0;attempt<2;attempt++) {
            assertEquals("INTEGRITY_FAILURE",http("POST","/auth/password/reset",Map.of("phone",phone(u),"code",proof,"newPassword",NEXT),null,503).path("code").asText());
            assertEquals(before,passwordFacts(u));
        }
        assertNotNull(login(u,ORIGINAL));
    }

    @Test void concurrentTemporaryPhoneUniquenessHasOneWinner() throws Exception {
        String phone=nextPhone();fixturePhones.add(codec.phoneHash(phone));
        var request=new UserRequests.Temporary("Test Person",phone,Long.toString(allowedDept),PREFIX+"race");
        // Distinct managers prevent the actor lock alone from serializing the unique-key test.
        UserAccount other=seed("adminrace",1,1,allowedDept,3);gateway.managers.put(other.id,Set.of(allowedDept));
        String otherToken="Bearer "+jwt.issue(other.id,1);
        ExecutorService pool=Executors.newFixedThreadPool(2);CountDownLatch start=new CountDownLatch(1);
        try {
            List<Future<Boolean>> futures=new ArrayList<>();
            for(String token:List.of(adminToken,otherToken)) futures.add(pool.submit(()->{assertTrue(start.await(5,TimeUnit.SECONDS));try{var result=users.createTemporary(token,request);fixtureIds.add(Long.valueOf(result.userId()));return true;}catch(BusinessException e){assertEquals(ErrorCode.DUPLICATE_RESOURCE,e.errorCode());return false;}}));
            start.countDown();int success=0;for(var f:futures)if(f.get(20,TimeUnit.SECONDS))success++;assertEquals(1,success);
        } finally {pool.shutdownNow();}
    }

    @Test void mergedMissingAndOutOfScopeDepartmentsAreRejectedWithoutCreatingUsers() {
        for(long dept:List.of(mergedDept,Long.MAX_VALUE,outsideDept)) {
            String phone=nextPhone();var request=new UserRequests.Temporary("Test Person",phone,Long.toString(dept),PREFIX+"invalid");
            ErrorCode expected=dept==mergedDept?ErrorCode.BUSINESS_RULE_VIOLATION:ErrorCode.OUT_OF_SCOPE;
            expect(expected,()->users.createTemporary(adminToken,request));
            assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone_hash=?",Integer.class,codec.phoneHash(phone)));
        }
        // A currently nonexistent department inside a controlled test scope exercises existence.
        gateway.managers.put(admin.id,Set.of(Long.MAX_VALUE));
        expect(ErrorCode.BUSINESS_RULE_VIOLATION,()->users.createTemporary(adminToken,new UserRequests.Temporary("Test Person",nextPhone(),Long.toString(Long.MAX_VALUE),PREFIX+"missing")));
    }

    @Test void listAndDetailHaveSameDatabaseScopeAndNeverTrustClientAdminIds() throws Exception {
        UserAccount inside=seed("inside",1,1,allowedDept,2),outside=seed("outside",1,1,outsideDept,2);
        JsonNode result=http("GET","/users?keyword="+PREFIX+"&page=1&pageSize=1",null,adminToken,200);
        int count=db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE dept_id IN (?,?,?) AND LOCATE(?,username)>0",Integer.class,allowedDept,mergedDept,frozenDept,PREFIX);
        assertEquals(count,result.at("/data/total").asInt());assertEquals(1,result.at("/data/items").size());
        http("GET","/users/"+inside.id,null,adminToken,200);http("GET","/users/"+outside.id,null,adminToken,404);
        http("GET","/users?deptId="+outsideDept,null,adminToken,403);
        gateway.denyFunction=true;http("GET","/users?adminId="+admin.id,null,adminToken,403);gateway.denyFunction=false;
        var exact=users.list(adminToken,phone(inside),null,null,null,1,20);assertEquals(1,exact.total());assertEquals(inside.id.toString(),exact.items().getFirst().userId());
        http("GET","/users?pageSize=501",null,adminToken,400);
    }

    @Test void statusSourceAndDepartmentFiltersShareScopeCountAndPaginationOverHttp() throws Exception {
        String keyword=PREFIX+"matrix";
        List<UserAccount> matrix=new ArrayList<>();
        for(int source=1;source<=3;source++) for(int state=1;state<=3;state++) {
            matrix.add(seed("matrix",1,state,allowedDept,source));
            seed("matrix",1,state,outsideDept,source);
            seed("matrix",1,state,frozenDept,source);
        }
        String base="/users?keyword="+keyword;
        JsonNode all=http("GET",base,null,adminToken,200);
        assertEquals(18,all.at("/data/total").asInt());assertEquals(18,all.at("/data/items").size());
        for(int source=1;source<=3;source++) for(int state=1;state<=3;state++) {
            JsonNode byStatus=http("GET",base+"&status="+state,null,adminToken,200);
            assertEquals(6,byStatus.at("/data/total").asInt());
            for(JsonNode item:byStatus.at("/data/items")) assertEquals(state,item.path("status").asInt());
            JsonNode bySource=http("GET",base+"&source="+source,null,adminToken,200);
            assertEquals(6,bySource.at("/data/total").asInt());
            for(JsonNode item:bySource.at("/data/items")) assertEquals(source,item.path("source").asInt());
            String query=base+"&status="+state+"&source="+source;
            JsonNode first=http("GET",query+"&pageSize=1",null,adminToken,200);
            JsonNode second=http("GET",query+"&pageSize=1&page=2",null,adminToken,200);
            assertEquals(2,first.at("/data/total").asInt());assertEquals(2,second.at("/data/total").asInt());
            assertEquals(1,first.at("/data/items").size());assertEquals(1,second.at("/data/items").size());
            assertNotEquals(first.at("/data/items/0/userId"),second.at("/data/items/0/userId"));
            JsonNode exact=http("GET",query+"&deptId="+allowedDept,null,adminToken,200);
            assertEquals(1,exact.at("/data/total").asInt());assertEquals(1,exact.at("/data/items").size());
            final int expectedSource=source,expectedState=state;
            UserAccount expected=matrix.stream().filter(u->u.source==expectedSource && u.status==expectedState).findFirst().orElseThrow();
            assertEquals(expected.id.toString(),exact.at("/data/items/0/userId").asText());
        }
        JsonNode last=http("GET",base+"&page=1000000&pageSize=500",null,adminToken,200);
        assertEquals(18,last.at("/data/total").asInt());assertEquals(0,last.at("/data/items").size());
        JsonNode empty=http("GET",base+"absent&status=3&source=1&deptId="+allowedDept,null,adminToken,200);
        assertEquals(0,empty.at("/data/total").asInt());assertEquals(0,empty.at("/data/items").size());
    }

    @Test void filteredSearchNeverBypassesFunctionScopeOrCurrentSessionOverHttp() throws Exception {
        UserAccount inside=seed("filterguard",1,2,allowedDept,1),outside=seed("filterguard",1,2,outsideDept,1);
        // An out-of-scope OR phone match must neither count nor decrypt this identity.
        db.update("UPDATE sys_user SET real_name_ciphertext='unreadable-test-only' WHERE id=?",outside.id);
        String query="/users?status=2&source=1&keyword="+phone(outside);
        JsonNode hidden=http("GET",query,null,adminToken,200);
        assertEquals(0,hidden.at("/data/total").asInt());assertEquals(0,hidden.at("/data/items").size());
        http("GET",query+"&deptId="+outsideDept,null,adminToken,403);
        JsonNode found=http("GET","/users?status=2&source=1&deptId="+allowedDept+"&keyword="+inside.employeeNo,null,adminToken,200);
        assertEquals(1,found.at("/data/total").asInt());assertEquals(inside.id.toString(),found.at("/data/items/0/userId").asText());
        gateway.denyFunction=true;http("GET",query,null,adminToken,403);gateway.denyFunction=false;
        gateway.managers.put(admin.id,Set.of());http("GET",query,null,adminToken,403);
        gateway.managers.put(admin.id,Set.of(allowedDept));
        http("GET",query,null,null,401);
        db.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?",admin.id);
        http("GET",query,null,adminToken,401);
        // The forced stale-session assertion is complete; keep only this test's
        // retained fixtures internally consistent for later full-scope searches.
        gateway.seedSignature(mapper.findById(admin.id));renameFixture(outside,"Test Person");
    }

    @Test void chineseSingleCharacterNameSearchOverRealHttp() throws Exception {
        long dept=nameDepartment("single17");
        UserAccount u=seed("name17",1,1,dept,2);
        db.update("UPDATE sys_user SET real_name_ciphertext=? WHERE id=?",codec.encrypt("张小明"),u.id);
        gateway.seedSignature(mapper.findById(u.id));
        JsonNode result=http("GET","/users?deptId="+dept+"&keyword="+java.net.URLEncoder.encode("张",StandardCharsets.UTF_8),null,adminToken,200);
        assertEquals(1,result.at("/data/total").asInt());
        assertEquals(u.id.toString(),result.at("/data/items/0/userId").asText());
    }

    @Test void literalNameSubstringsUnicodeAndLegacyIdentifiersCoexistOverHttp() throws Exception {
        long dept=nameDepartment("literal17");
        UserAccount chinese=named("张小明",dept,1,1),latin=named("José Test%_\\Person",dept,1,2);
        for(String term:List.of("张","张小明","小明","小"))assertNameIds(dept,term,Set.of(chinese.id));
        for(String term:List.of("José","Jose\u0301","Test","%_\\","\\","Person"))assertNameIds(dept,term,Set.of(latin.id));
        assertNameIds(dept,"_",Set.of(chinese.id,latin.id)); // Both existing usernames literally contain underscores.
        for(String term:List.of("张明","jose","Jose","test","%Person"))assertNameIds(dept,term,Set.of());
        for(String term:List.of(chinese.username,chinese.username.toUpperCase(Locale.ROOT),chinese.employeeNo,phone(chinese)))assertNameIds(dept,term,Set.of(chinese.id));
        // A single row matching both username and name stays one result.
        renameFixture(latin,latin.username);assertNameIds(dept,latin.username,Set.of(latin.id));
        assertNameIds(dept,"  ",Set.of(chinese.id,latin.id));
        renameFixture(latin,"𠀀Alpha");assertNameIds(dept,"𠀀",Set.of(latin.id));
    }

    @Test void nameScopeFilterIntersectionAndExactTotalAcrossPagesOverHttp() throws Exception {
        long first=nameDepartment("matrix17a"),second=nameDepartment("matrix17b");
        gateway.managers.put(admin.id,Set.of(first,second));
        Set<Long> expected=new TreeSet<>();
        for(long dept:List.of(first,second,outsideDept))for(int source=1;source<=3;source++)for(int state=1;state<=3;state++) {
            UserAccount u=named("张小明",dept,state,source);
            if(dept==first && state==2 && source==1)expected.add(u.id);
        }
        // Outside-scope unreadable ciphertext must not affect count/decryption.
        UserAccount corrupt=named("张小明",outsideDept,2,1);
        db.update("UPDATE sys_user SET real_name_ciphertext='unreadable-name17' WHERE id=?",corrupt.id);
        String base="/users?keyword="+encoded("张")+"&status=2&source=1";
        Set<String> seen=new HashSet<>();
        for(int page=1;page<=3;page++) {
            JsonNode r=http("GET",base+"&pageSize=1&page="+page,null,adminToken,200);
            assertEquals(2,r.at("/data/total").asInt());assertEquals(page<=2?1:0,r.at("/data/items").size());
            for(JsonNode item:r.at("/data/items"))assertTrue(seen.add(item.path("userId").asText()));
        }
        JsonNode exact=http("GET",base+"&deptId="+first,null,adminToken,200);
        assertEquals(1,exact.at("/data/total").asInt());assertEquals(expected.iterator().next().toString(),exact.at("/data/items/0/userId").asText());
        http("GET",base+"&deptId="+outsideDept,null,adminToken,403);
        gateway.denyFunction=true;http("GET",base,null,adminToken,403);gateway.denyFunction=false;
        db.update("UPDATE sys_user SET session_version=session_version+1 WHERE id=?",admin.id);
        http("GET",base,null,adminToken,401);
        gateway.seedSignature(mapper.findById(admin.id));renameFixture(corrupt,"张小明");
    }

    @Test void corruptCandidateFailsWholeSearchEvenOutsideRequestedPageAndLegacyMatch() throws Exception {
        long dept=nameDepartment("bad18");UserAccount good=named("张三",dept,1,2),bad=named("李四",dept,1,2);
        String path="/users?deptId="+dept+"&pageSize=1&keyword="+good.username;
        db.update("UPDATE sys_user SET real_name_ciphertext='invalid-gcm-task18' WHERE id=?",bad.id);
        try {
            http("GET",path,null,adminToken,503); // Bad identity may not be silently skipped.
            gateway.seedSignature(mapper.findById(bad.id));
            http("GET",path,null,adminToken,503); // Valid signature over undecryptable name also fails.
            JsonNode empty=http("GET","/users?deptId="+dept+"&pageSize=1",null,adminToken,200);
            assertEquals(2,empty.at("/data/total").asInt());assertEquals(good.id.toString(),empty.at("/data/items/0/userId").asText());
        } finally {renameFixture(bad,"李四");}
    }

    @Test void concurrentCommittedRenameKeepsOneSnapshotAcrossCandidateBatches() throws Exception {
        long dept=nameDepartment("snapshot18");List<UserAccount> all=new ArrayList<>();
        for(int i=0;i<201;i++)all.add(named("张三",dept,1,2));
        UserAccount first=all.getFirst(),last=all.getLast();
        gateway.pauseId=first.id;gateway.read=new CountDownLatch(1);gateway.release=new CountDownLatch(1);
        var pool=Executors.newSingleThreadExecutor();
        try {
            Future<JsonNode> reader=pool.submit(()->http("GET","/users?deptId="+dept+"&pageSize=1&page=201&keyword="+encoded("张"),null,adminToken,200));
            assertTrue(gateway.read.await(10,TimeUnit.SECONDS));
            var manager=context.getBean(org.springframework.transaction.PlatformTransactionManager.class);
            new org.springframework.transaction.support.TransactionTemplate(manager).executeWithoutResult(status->{
                mapper.lockById(last.id);db.update("UPDATE sys_user SET real_name_ciphertext=? WHERE id=?",codec.encrypt("李四"),last.id);
                gateway.seedSignature(mapper.findById(last.id));
            });
            gateway.pauseId=0;gateway.release.countDown();
            JsonNode old=reader.get(20,TimeUnit.SECONDS);assertEquals(201,old.at("/data/total").asInt());
            assertEquals(last.id.toString(),old.at("/data/items/0/userId").asText());assertEquals("张三",old.at("/data/items/0/realName").asText());
            JsonNode current=http("GET","/users?deptId="+dept+"&keyword="+encoded("张"),null,adminToken,200);
            assertEquals(200,current.at("/data/total").asInt());assertNameIds(dept,"李",Set.of(last.id));
        } finally {gateway.pauseId=0;gateway.release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(20,TimeUnit.SECONDS));}
    }

    @Test void temporaryCreationSearchesImmediatelyAndSignatureAuditFaultsRollbackOverHttp() throws Exception {
        long dept=nameDepartment("create18");String phone=nextPhone();
        JsonNode r=http("POST","/users/temporary",Map.of("realName","张小明","phone",phone,"deptId",String.valueOf(dept),"reason",PREFIX+"name18"),adminToken,200);
        long id=r.at("/data/userId").asLong();fixtureIds.add(id);fixturePhones.add(codec.phoneHash(phone));assertNameIds(dept,"小明",Set.of(id));
        for(String fault:List.of("sign","audit")) {
            String fresh=nextPhone();gateway.failSign=fault.equals("sign");gateway.failAudit=fault.equals("audit");
            try {
                http("POST","/users/temporary",Map.of("realName","张三","phone",fresh,"deptId",String.valueOf(dept),"reason",PREFIX+"fault18"),adminToken,503);
                assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_user WHERE phone_hash=?",Integer.class,codec.phoneHash(fresh)));
            } finally {gateway.failSign=false;gateway.failAudit=false;}
        }
    }

    @Test void accurateTotalAndNoDuplicatesAcrossInternalBatchesAndPages() throws Exception {
        long dept=nameDepartment("batch18");List<Long> expected=new ArrayList<>();
        for(int i=0;i<205;i++) {
            UserAccount u=named(i%3==0?"张小明":"李四",dept,1,2);if(i%3==0)expected.add(u.id);
        }
        List<Long> actual=new ArrayList<>();
        for(int page=1;page<=5;page++) {
            JsonNode r=http("GET","/users?deptId="+dept+"&keyword="+encoded("小明")+"&pageSize=20&page="+page,null,adminToken,200);
            assertEquals(expected.size(),r.at("/data/total").asInt());
            for(JsonNode item:r.at("/data/items"))actual.add(item.path("userId").asLong());
        }
        assertEquals(expected,actual);assertEquals(actual.size(),new HashSet<>(actual).size());
    }

    private long nameDepartment(String suffix) {
        long dept=department(allowedDept,db.queryForObject("SELECT dept_path FROM sys_dept WHERE id=?",String.class,allowedDept),suffix,1,null);
        gateway.managers.put(admin.id,Set.of(dept));return dept;
    }
    private UserAccount named(String name,long dept,int state,int source) {
        UserAccount u=seed("name17",1,state,dept,source);renameFixture(u,name);return mapper.findById(u.id);
    }
    private void renameFixture(UserAccount u,String name) {
        db.update("UPDATE sys_user SET real_name_ciphertext=? WHERE id=?",codec.encrypt(name),u.id);
        gateway.seedSignature(mapper.findById(u.id));
    }
    private static String encoded(String term){return java.net.URLEncoder.encode(term,StandardCharsets.UTF_8);}
    private void assertNameIds(long dept,String term,Set<Long> expected) throws Exception {
        JsonNode r=http("GET","/users?deptId="+dept+"&keyword="+encoded(term),null,adminToken,200);
        Set<Long> ids=new HashSet<>();for(JsonNode item:r.at("/data/items"))assertTrue(ids.add(item.path("userId").asLong()));
        assertEquals(expected,ids);assertEquals(expected.size(),r.at("/data/total").asInt());
    }

    @Test void invalidFilterEnumsTypesAndPageBoundsReturnUnified400OverHttp() throws Exception {
        for(String query:List.of("status=0","status=4","status=-1","status=ACTIVE","status=1.5","status=2147483648",
                "source=0","source=4","source=-1","source=HR","source=1.5","source=2147483648",
                "page=0","page=1000001","page=2147483647","page=2147483648","page=1.5","page=no",
                "pageSize=0","pageSize=501","pageSize=no","deptId=0")) {
            assertEquals("INVALID_ARGUMENT",http("GET","/users?"+query,null,adminToken,400).path("code").asText());
        }
    }

    @Test void passwordPolicyFailuresExpose422AndKeepProofAndAccountUnchangedOverHttp() throws Exception {
        for(boolean initialize:List.of(true,false)) {
            UserAccount u=seed("policyhttp",initialize?0:1,1,allowedDept,2);
            String proof=send(u,initialize?Purpose.SET_PASSWORD:Purpose.RESET_PASSWORD);
            var before=passwordFacts(u);
            for(String bad:initialize?List.of("short"):List.of("short",ORIGINAL)) {
                JsonNode result=http("POST","/auth/password/"+(initialize?"initialize":"reset"),
                        Map.of("phone",phone(u),"code",proof,"newPassword",bad),null,422);
                assertEquals("BUSINESS_RULE_VIOLATION",result.path("code").asText());assertEquals(before,passwordFacts(u));
            }
        }
    }

    @Test void temporaryPauseResumeKeepsGrantsAndNeverRevivesOldJwtOverHttp() throws Exception {
        UserAccount u=seed("pause",1,1,allowedDept,2);gateway.managers.put(u.id,Set.of(allowedDept));
        String old="Bearer "+login(u,ORIGINAL).accessToken();
        // Test-only independent grant marker in C's real table; A must leave it untouched.
        var roleKey=new GeneratedKeyHolder();
        db.update(c->{var s=c.prepareStatement("INSERT INTO sys_role(role_code,role_name) VALUES(?,?)",new String[]{"id"});s.setString(1,PREFIX+"grant");s.setString(2,"TASK05 grant fixture");return s;},roleKey);
        long roleId=Objects.requireNonNull(roleKey.getKey()).longValue();db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",u.id,roleId);
        db.update("INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref) VALUES(?,?,'MANUAL',?)",u.id,roleId,PREFIX+"grant");
        try {
            http("PATCH","/users/"+u.id+"/status",Map.of("status",2,"reason",PREFIX+"pause"),adminToken,200);
            http("PATCH","/users/"+u.id+"/status",Map.of("status",2,"reason",PREFIX+"retry"),adminToken,200);
            assertEquals(2,mapper.findById(u.id).sessionVersion);expect(ErrorCode.INVALID_CREDENTIALS,()->login(u,ORIGINAL));
            expect(ErrorCode.INVALID_TOKEN,()->users.detail(old,u.id.toString()));
            http("POST","/auth/logout",null,old,200);
            http("PATCH","/users/"+u.id+"/status",Map.of("status",1,"reason",PREFIX+"resume"),adminToken,200);
            assertEquals(2,mapper.findById(u.id).sessionVersion);expect(ErrorCode.INVALID_TOKEN,()->users.detail(old,u.id.toString()));
            assertNotNull(login(u,ORIGINAL));assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",Integer.class,u.id,roleId));
            assertEquals(1,db.queryForObject("SELECT status FROM sys_user_role_source WHERE user_id=? AND role_id=?",Integer.class,u.id,roleId));
            http("PATCH","/users/"+u.id+"/status",Map.of("status",3,"reason",PREFIX+"illegal"),adminToken,400);
        } finally {
            db.update("UPDATE sys_role SET status=0 WHERE id=?",roleId);
            db.update("UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE user_id=? AND role_id=?",u.id,roleId);
            db.update("DELETE FROM sys_user_role WHERE user_id=? AND role_id=?",u.id,roleId);
        }
        UserAccount hr=seed("hr",1,1,allowedDept,1);
        http("PATCH","/users/"+hr.id+"/status",Map.of("status",2,"reason",PREFIX+"illegalHR"),adminToken,422);
    }

    @Test void logoutIsSingleJtiIdempotentLeavesOtherSessionAndWorksForTerminatedState() throws Exception {
        UserAccount u=seed("logout",1,1,allowedDept,2);gateway.managers.put(u.id,Set.of(allowedDept));
        String a="Bearer "+login(u,ORIGINAL).accessToken(),b="Bearer "+login(u,ORIGINAL).accessToken();
        http("POST","/auth/logout",null,a,200);http("POST","/auth/logout",null,a,200);
        expect(ErrorCode.INVALID_TOKEN,()->users.detail(a,u.id.toString()));assertNotNull(users.detail(b,u.id.toString()));
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_session_invalidation WHERE user_id=?",Integer.class,u.id));
        db.update("UPDATE sys_user SET status=3,session_version=session_version+1 WHERE id=?",u.id);
        // Model a legitimate state transition: its identity signature commits before logout.
        gateway.seedSignature(mapper.findById(u.id));
        http("POST","/auth/logout",null,b,200);assertEquals(2,db.queryForObject("SELECT COUNT(*) FROM sys_session_invalidation WHERE user_id=?",Integer.class,u.id));
    }

    @Test void signatureReadFailureAndWriteAuditFaultsRejectOverHttpAndRollback() throws Exception {
        UserAccount u=seed("faults",1,1,allowedDept,2);String before=fakeSignature(u.id);
        gateway.failVerify=true;http("POST","/auth/login",Map.of("loginType","USERNAME","identifier",u.username,"password",ORIGINAL),null,503);gateway.failVerify=false;
        gateway.failAudit=true;
        http("PATCH","/users/"+u.id+"/status",Map.of("status",2,"reason",PREFIX+"fault"),adminToken,503);
        assertEquals(1,mapper.findById(u.id).status);assertEquals(1,mapper.findById(u.id).sessionVersion);assertEquals(before,fakeSignature(u.id));
        String token="Bearer "+jwt.issue(u.id,1);http("POST","/auth/logout",null,token,503);
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM sys_session_invalidation WHERE user_id=?",Integer.class,u.id));
        gateway.failAudit=false;
    }

    @Test void defaultEnvironmentHasNoAllowGatewayOrSmsProvider() throws Exception {
        Map<String,Object> defaults=new HashMap<>(settings);defaults.put("server.port","18086");
        try(ConfigurableApplicationContext ordinary=new SpringApplicationBuilder(TestBootstrap.class).profiles("task05-default-proof").properties(defaults)
                .run(LocalTestDatabase.arguments("--server.port=18086","--server.address=127.0.0.1"))) {
            assertFalse(ordinary.getBean(M1SecurityGateway.class) instanceof TestGateway);
            expect(ErrorCode.SMS_DELIVERY_UNAVAILABLE,()->ordinary.getBean(AuthService.class).sendSms(new SendSms(nextPhone(),Purpose.SET_PASSWORD)));
            UserAccount u=seed("defaultdeny",1,1,allowedDept,2);
            expect(ErrorCode.INTEGRITY_FAILURE,()->ordinary.getBean(AuthService.class).login(new Login(LoginType.USERNAME,u.username,ORIGINAL),"127.0.0.1","test"));
        }
    }

    private List<Map<String,Object>> passwordFacts(UserAccount u) {
        List<Map<String,Object>> facts=new ArrayList<>(db.queryForList("SELECT * FROM sys_user WHERE id=?",u.id));
        facts.addAll(db.queryForList("SELECT * FROM sys_password_history WHERE user_id=? ORDER BY id",u.id));
        facts.addAll(db.queryForList("SELECT * FROM sys_sms_code WHERE phone_hash=? ORDER BY id",u.phoneHash));
        facts.addAll(db.queryForList("SELECT * FROM sys_data_signature WHERE data_type='TASK05_USER' AND data_id=?",u.id));
        facts.addAll(db.queryForList("SELECT * FROM sys_operation_log WHERE source_system='TASK05_TEST' AND target_id=? ORDER BY id",u.id));
        return facts;
    }
    private AuthService.LoginResult login(UserAccount u,String password) {return auth.login(new Login(LoginType.USERNAME,u.username,password),"127.0.0.1","TASK05_TEST");}
    private String send(UserAccount u,Purpose purpose) {auth.sendSms(new SendSms(phone(u),purpose));return sms.code(phone(u),purpose);}
    private String phone(UserAccount u) {return codec.decrypt(u.phone);}
    private int smsCount(UserAccount u) {return db.queryForObject("SELECT COUNT(*) FROM sys_sms_code WHERE phone_hash=?",Integer.class,u.phoneHash);}
    private void ageSms(UserAccount u,Purpose purpose) {db.update("UPDATE sys_sms_code SET created_at=DATE_SUB(NOW(),INTERVAL 61 SECOND) WHERE phone_hash=? AND purpose=?",u.phoneHash,purpose.name());}
    private String fakeSignature(long id){return db.queryForObject("SELECT signature FROM sys_data_signature WHERE data_type='TASK05_USER' AND data_id=?",String.class,id);}
    private int auditCount(long id){return db.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE source_system='TASK05_TEST' AND target_id=?",Integer.class,id);}
    private void assertUnchangedPassword(UserAccount u,String proof,String signature,int audits) {
        assertEquals(u.passwordHash,mapper.findById(u.id).passwordHash);assertEquals(1,mapper.findById(u.id).sessionVersion);
        assertEquals(1,db.queryForObject("SELECT COUNT(*) FROM sys_password_history WHERE user_id=?",Integer.class,u.id));
        assertNull(db.queryForObject("SELECT consumed_at FROM sys_sms_code WHERE phone_hash=? ORDER BY id DESC LIMIT 1",java.sql.Timestamp.class,u.phoneHash));
        assertEquals(signature,fakeSignature(u.id));assertEquals(audits,auditCount(u.id));
    }
    private UserAccount seed(String suffix,int initialized,int state,long dept,int source) {
        String phone=nextPhone(),username=PREFIX+suffix+sequence.incrementAndGet(),hash=codec.phoneHash(phone);fixturePhones.add(hash);
        var key=new GeneratedKeyHolder();
        db.update(connection->{PreparedStatement s=connection.prepareStatement("INSERT INTO sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,status,source,employee_no,employment_type,password_initialized,created_by,temp_reason) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",new String[]{"id"});
            Object[] values={username,codec.encrypt("Test Person"),codec.encrypt(phone),hash,initialized==1?originalHash:placeholder,dept,state,source,source==1?PREFIX+"SG"+sequence.get():null,source==1?1:null,initialized,source==2?admin.id:null,source==2?PREFIX+"fixture":null};
            for(int i=0;i<values.length;i++)s.setObject(i+1,values[i]);return s;},key);
        long id=Objects.requireNonNull(key.getKey()).longValue();fixtureIds.add(id);UserAccount u=mapper.findById(id);
        if(initialized==1)db.update("INSERT INTO sys_password_history(user_id,password_hash) VALUES(?,?)",id,originalHash);
        gateway.seedSignature(u);
        return u;
    }
    private long department(long parent,String parentPath,String suffix,int status,Long merged) {
        var key=new GeneratedKeyHolder();
        db.update(c->{PreparedStatement s=c.prepareStatement("INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path,status,merged_into_dept_id) VALUES(?,?,?,?,?,?)",new String[]{"id"});
            Object[] args={parent,PREFIX+suffix,"TASK05 "+suffix,parentPath,status,null};for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s;},key);
        long id=Objects.requireNonNull(key.getKey()).longValue();fixtureDepts.add(id);db.update("UPDATE sys_dept SET dept_path=?,merged_into_dept_id=? WHERE id=?",parentPath+id+"/",merged,id);return id;
    }
    private static String nextPhone(){return "13905%06d".formatted(sequence.incrementAndGet());}
    private static String randomKey(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getEncoder().encodeToString(bytes);}
    private static void expect(ErrorCode error,org.junit.jupiter.api.function.Executable work){assertEquals(error,assertThrows(BusinessException.class,work).errorCode());}
    private JsonNode http(String method,String path,Object body,String bearer,int expected) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:18085/api/v1"+path)).timeout(java.time.Duration.ofSeconds(20)).header("Content-Type","application/json").header("X-Request-Id",PREFIX+sequence.incrementAndGet());
        if(bearer!=null)request.header("Authorization",bearer);
        request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
        JsonNode result=json.readTree(response.body());
        HTTP_EVIDENCE.add(method+" "+path.split("\\?")[0]+" -> "+response.statusCode()+" "+result.path("code").asText());
        assertEquals(expected,response.statusCode(),method+" "+path+" code="+result.path("code").asText());
        assertTrue(result.hasNonNull("requestId"));return result;
    }

    @TestConfiguration(proxyBeanMethods=false) @EnableAutoConfiguration
    @ComponentScan(basePackages="com.whatname.iam",excludeFilters={@ComponentScan.Filter(type=FilterType.REGEX,
            pattern={".*Test.*","com.whatname.iam.IamApplication"}),
            @ComponentScan.Filter(type=FilterType.ANNOTATION,classes=TestConfiguration.class)})
    public static class TestBootstrap {}

    @TestConfiguration(proxyBeanMethods=false) @Profile("task05-test")
    public static class TestWiring {
        @Bean @Primary TestGateway task05Gateway(JdbcTemplate db){return new TestGateway(db);}
        @Bean @Primary TestSms task05Sms(){return new TestSms();}
    }
    public static class TestSms implements SmsSender {
        final Map<String,String> deliveries=new ConcurrentHashMap<>();volatile boolean fail,failSend;
        public void requireAvailable(){if(fail)throw new BusinessException(ErrorCode.SMS_DELIVERY_UNAVAILABLE);}
        public void send(String phone,String purpose,String code){requireAvailable();if(failSend)throw new BusinessException(ErrorCode.SMS_DELIVERY_UNAVAILABLE);deliveries.put(phone+":"+purpose,code);}
        String code(String phone,Purpose purpose){return Objects.requireNonNull(deliveries.get(phone+":"+purpose.name()),"Test SMS was not delivered");}
    }
    /** Fake fingerprint + actual transaction-bound SQL evidence; NOT D Ed25519 or C M5. */
    public static class TestGateway implements M1SecurityGateway {
        final JdbcTemplate db;final Map<Long,Set<Long>> managers=new ConcurrentHashMap<>();
        volatile boolean failVerify,failSign,failAudit,denyFunction,failDatabase;
        volatile long pauseId;volatile CountDownLatch read,release;
        TestGateway(JdbcTemplate db){this.db=db;}
        public void verifyIdentity(UserAccount u) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            String stored=db.queryForObject("SELECT signature FROM sys_data_signature WHERE data_type='TASK05_USER' AND data_id=?",String.class,u.id);
            if(failVerify || !fingerprint(u).equals(stored))throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);
            if(u.id==pauseId) {
                read.countDown();
                try {if(!release.await(15,TimeUnit.SECONDS))throw new AssertionError("candidate snapshot barrier timed out");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
            }
        }
        public Set<Long> authorizeManagement(UserAccount actor,String permission) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            if(denyFunction || !managers.containsKey(actor.id))throw new BusinessException(ErrorCode.PERMISSION_DENIED);
            assertTrue(Set.of("IAM_USERS_VIEW","IAM_USERS_WRITE").contains(permission));return managers.get(actor.id);
        }
        public void signIdentity(UserAccount u) {assertTrue(TransactionSynchronizationManager.isActualTransactionActive());seedSignature(u);if(failSign)throw new BusinessException(ErrorCode.INTEGRITY_FAILURE);}
        void seedSignature(UserAccount u) {db.update("INSERT INTO sys_data_signature(data_type,data_id,signature,key_id) VALUES('TASK05_USER',?,?,'TASK05_FAKE') ON DUPLICATE KEY UPDATE signature=VALUES(signature)",u.id,fingerprint(u));}
        public void audit(String operation,Long actor,Long target,String reason) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            db.update("INSERT INTO sys_operation_log(user_id,request_id,source_system,operation_type,target_type,target_id,status,operation_desc) VALUES(?,?,'TASK05_TEST',?,'USER',?,1,'C/D substitute; real integration pending')",actor,PREFIX+UUID.randomUUID().toString().substring(0,8),operation,target);
            if(failAudit)throw new BusinessException(ErrorCode.AUDIT_UNAVAILABLE);
            // A real NOT NULL violation after earlier writes proves JDBC failure rollback.
            if(failDatabase)db.update("INSERT INTO sys_operation_log(request_id,operation_type) VALUES(?,NULL)",PREFIX+"fault");
        }
        private static String fingerprint(UserAccount u) {
            try {String data=Arrays.asList(u.id,u.username,u.realNameCiphertext,u.phone,u.phoneHash,u.passwordHash,u.employeeNo,u.deptId,u.status,u.source,u.employmentType,u.passwordInitialized,u.sessionVersion,u.grantRevision,u.hrVersion,u.createdBy,u.tempReason).toString();
                byte[] hash=MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));byte[] twice=new byte[64];System.arraycopy(hash,0,twice,0,32);System.arraycopy(hash,0,twice,32,32);return Base64.getEncoder().encodeToString(twice);
            } catch(Exception e){throw new AssertionError(e);}
        }
    }
}
