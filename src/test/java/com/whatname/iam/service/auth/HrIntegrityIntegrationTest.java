package com.whatname.iam.service.auth;

import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.department.DepartmentRequests;
import com.whatname.iam.dto.hr.HrRequests.*;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.mapper.department.OrganizationWriteMapper;
import com.whatname.iam.mapper.user.UserAccountMapper;
import com.whatname.iam.service.department.*;
import com.whatname.iam.service.event.*;
import com.whatname.iam.service.hr.*;
import com.whatname.iam.service.permission.*;
import com.whatname.iam.support.TestDatabase;

import jakarta.servlet.http.HttpServletRequest;

import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

import java.security.*;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Actual A event/HR/organization services, MySQL transactions and D canonical signatures/audit. HR
 * service authentication and C HR/reapproval are explicit test-only substitutes. This does not
 * validate those missing production providers. No original user is changed.
 */
@ExtendWith(TestDatabase.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HrIntegrityIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @ComponentScan(
            basePackages = "com.whatname.iam",
            excludeFilters = {
                @ComponentScan.Filter(
                        type = FilterType.REGEX,
                        pattern = {".*Test.*", "com.whatname.iam.IamApplication"}),
                @ComponentScan.Filter(
                        type = FilterType.ANNOTATION,
                        classes = TestConfiguration.class)
            })
    static class Bootstrap {}

    @TestConfiguration(proxyBeanMethods = false)
    static class Fixtures {
        @Bean
        @Primary
        FixtureDepartment fixtureDepartment(
                ManagementDataSecurity data, DataSignatureService signatures, JdbcTemplate db) {
            return new FixtureDepartment(data, signatures, db);
        }

        @Bean
        @Primary
        FixtureGrants fixtureGrants(DataSignatureService signatures, JdbcTemplate db) {
            return new FixtureGrants(signatures, db);
        }

        @Bean
        AuditFailure hrAuditFailure() {
            return new AuditFailure();
        }
    }

    static class FixtureDepartment implements DepartmentCollaboration {
        final ManagementDataSecurity data;
        final DataSignatureService signatures;
        final JdbcTemplate db;
        boolean rejectInside,
                failAfterSign,
                corruptMergedIdentity,
                corruptOtherDepartment,
                mismatchEvent;
        long transientDepartment, unaffectedDepartment;

        FixtureDepartment(
                ManagementDataSecurity data, DataSignatureService signatures, JdbcTemplate db) {
            this.data = data;
            this.signatures = signatures;
            this.db = db;
        }

        public Manager authenticateManager(HttpServletRequest request, String permission) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }

        public TrustedEventContext authenticateHr(HttpServletRequest request, String type) {
            throw new UnsupportedOperationException("No production HR auth in this fixture");
        }

        public void verifyTrustedEvent(TrustedEventContext actor, String type) {
            if (actor == null
                    || !"HR".equals(actor.sourceSystem())
                    || !"fixture-hr".equals(actor.clientId())
                    || !Set.of(
                                    "HR_EMPLOYEE",
                                    "HR_TRANSFER",
                                    "HR_TERMINATION",
                                    "HR_DEPARTMENT",
                                    "HR_MERGE")
                            .contains(type)) throw new BusinessException(ErrorCode.INVALID_SERVICE);
            if (rejectInside && TransactionSynchronizationManager.isActualTransactionActive())
                throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }

        public void authorizeRecovery(TrustedEventContext actor, String type) {
            throw new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE);
        }

        public Set<Long> scope(Manager actor, String permission, List<Department> all) {
            throw new BusinessException(ErrorCode.PERMISSION_DENIED);
        }

        public void verifyOrganization(List<Department> all) {
            data.verifyOrganization(all);
        }

        public void verifyUsers(List<Map<String, Object>> users) {
            data.verifyUsers(users);
        }

        public void prepareOrganizationChange(TrustedEventContext actor, String eventId) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(
                    ExternalEventService.applying(actor, "HR_DEPARTMENT", eventId) != null
                            || ExternalEventService.applying(actor, "HR_MERGE", eventId) != null);
        }

        public void organizationChanged(Set<Long> departments, List<Map<String, Object>> moved) {
            // No administrator fixtures or production policy are supplied by this test.
            if (corruptMergedIdentity && !moved.isEmpty())
                db.update(
                        "UPDATE sys_user SET password_hash='unexpected-fixture-mutation' WHERE"
                            + " id=?",
                        moved.get(0).get("id"));
            if (corruptOtherDepartment)
                db.update("UPDATE sys_dept SET status=0 WHERE id=?", unaffectedDepartment);
        }

        public void signAndAudit(String operation, Object actor, Map<String, Object> evidence) {
            var passed = new LinkedHashMap<>(evidence);
            if (mismatchEvent) passed.put("eventId", "different-event");
            data.signOrganization(operation, actor, passed);
            if (failAfterSign) {
                for (Object id : (Collection<?>) evidence.get("affectedDepartmentIds")) {
                    transientDepartment = Long.parseLong(id.toString());
                    signatures.verify(DEPARTMENT, transientDepartment);
                }
                signatures.verify(ORGANIZATION, 0);
                throw new BusinessException(
                        ErrorCode
                                .AUTH_STATE_UNAVAILABLE); // Deliberately roll back HR01's new
                                                          // member.
            }
        }
    }

    static class FixtureGrants implements HrCollaboration {
        final DataSignatureService signatures;
        final JdbcTemplate db;
        boolean omitCollectionSignature, corruptIdentity;

        FixtureGrants(DataSignatureService signatures, JdbcTemplate db) {
            this.signatures = signatures;
            this.db = db;
        }

        public void prepare(
                TrustedEventContext actor, String eventId, String eventType, String employeeNo) {
            assertNotNull(ExternalEventService.applying(actor, eventType, eventId));
        }

        public void complete(long userId) {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
        }

        private void revoke(long id) {
            signatures.verify(USER_GRANTS, id);
            // This fixture has no grants/appointments: only model C owning the revision and seal.
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM sys_user_role WHERE user_id=?",
                            Integer.class,
                            id));
            db.update("UPDATE sys_user SET grant_revision=grant_revision+1 WHERE id=?", id);
            if (corruptIdentity)
                db.update(
                        "UPDATE sys_user SET password_hash='unexpected-fixture-mutation' WHERE"
                            + " id=?",
                        id);
            if (!omitCollectionSignature) signatures.sign(USER_GRANTS, id);
        }

        public void revokePosition(
                TrustedEventContext actor, String eventId, long userId, long oldDepartmentId) {
            revoke(userId);
        }

        public void revokeAll(TrustedEventContext actor, String eventId, long userId) {
            revoke(userId);
        }

        public void requireRehireEligible(long id) {
            signatures.verify(USER_GRANTS, id);
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                + " status=1",
                            Integer.class,
                            id));
            assertEquals(
                    0,
                    db.queryForObject(
                            "SELECT COUNT(*) FROM sys_admin WHERE user_id=? AND status=1",
                            Integer.class,
                            id));
        }

        public void departmentChanged(long id, long oldDepartmentId, long nextDepartmentId) {
            assertEquals(
                    nextDepartmentId,
                    db.queryForObject("SELECT dept_id FROM sys_user WHERE id=?", Long.class, id));
        }
    }

    @Intercepts(
            @org.apache.ibatis.plugin.Signature(
                    type = Executor.class,
                    method = "update",
                    args = {MappedStatement.class, Object.class}))
    static class AuditFailure implements Interceptor {
        boolean fail;
        String targetType;

        public Object intercept(Invocation call) throws Throwable {
            if (fail
                    && ((MappedStatement) call.getArgs()[0])
                            .getId()
                            .equals("com.whatname.iam.mapper.audit.AuditMapper.insert")
                    && (targetType == null
                            || call.getArgs()[1] instanceof Map<?, ?> entry
                                    && targetType.equals(entry.get("targetType"))))
                throw new org.springframework.dao.DataAccessResourceFailureException(
                        "fixture HR audit outage");
            return call.proceed();
        }
    }

    final String prefix = "AHR" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    final TrustedEventContext actor =
            new TrustedEventContext("HR", "fixture-hr", "fixture-request");
    final List<Long> accounts = new ArrayList<>();
    ConfigurableApplicationContext app;
    JdbcTemplate db;
    UserAccountMapper users;
    OrganizationWriteMapper organization;
    DataSignatureService signatures;
    ManagementDataSecurity data;
    SensitiveDataCodec codec;
    HrService hr;
    DepartmentService departments;
    TransactionTemplate tx;
    ObjectMapper json;
    FixtureDepartment port;
    FixtureGrants grants;
    AuditFailure failure;
    List<Map<String, Object>> originalDepartments, originalOrgSignatures;
    long source, target;
    String sourceCode, targetCode;
    int seq;
    UserAccount person;

    @BeforeAll
    void start() throws Exception {
        var rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        var jwt = rsa.generateKeyPair();
        var signing = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        var settings = new HashMap<String, Object>();
        settings.put("server.port", 0);
        settings.put("logging.level.root", "WARN");
        settings.put("iam.security.jwt-private-key", encode(jwt.getPrivate().getEncoded()));
        settings.put("iam.security.jwt-public-key", encode(jwt.getPublic().getEncoded()));
        settings.put("iam.security.jwt-issuer", prefix);
        settings.put("iam.security.jwt-audience", prefix);
        settings.put("iam.security.jwt-key-id", "fixture-hr-jwt");
        settings.put("iam.security.data-signing.key-id", "fixture-hr");
        settings.put(
                "iam.security.data-signing.private-key", encode(signing.getPrivate().getEncoded()));
        settings.put(
                "iam.security.data-signing.public-keys.fixture-hr",
                encode(signing.getPublic().getEncoded()));
        for (String key : List.of("data-key", "phone-hmac-key", "sms-hmac-key")) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            settings.put("iam.security." + key, encode(bytes));
        }
        app =
                new SpringApplicationBuilder(Bootstrap.class, Fixtures.class)
                        .properties(settings)
                        .run(
                                TestDatabase.arguments(
                                        "--server.port=0", "--server.address=127.0.0.1"));
        db = app.getBean(JdbcTemplate.class);
        users = app.getBean(UserAccountMapper.class);
        organization = app.getBean(OrganizationWriteMapper.class);
        signatures = app.getBean(DataSignatureService.class);
        data = app.getBean(ManagementDataSecurity.class);
        codec = app.getBean(SensitiveDataCodec.class);
        hr = app.getBean(HrService.class);
        departments = app.getBean(DepartmentService.class);
        json = app.getBean(ObjectMapper.class);
        port = app.getBean(FixtureDepartment.class);
        grants = app.getBean(FixtureGrants.class);
        failure = app.getBean(AuditFailure.class);
        tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        assertEquals(
                "MySQL",
                db.execute(
                        (org.springframework.jdbc.core.ConnectionCallback<String>)
                                c -> c.getMetaData().getDatabaseProductName()));
        originalDepartments = db.queryForList("SELECT * FROM sys_dept ORDER BY id");
        originalOrgSignatures =
                db.queryForList(
                        "SELECT * FROM sys_data_signature WHERE data_type IN"
                            + " ('ORGANIZATION','DEPARTMENT') ORDER BY data_type,data_id");
        var available =
                db.queryForList(
                        "SELECT d.id,d.dept_code FROM sys_dept d WHERE d.parent_id<>0 AND"
                            + " d.status=1 AND d.merged_into_dept_id IS NULL AND NOT EXISTS(SELECT"
                            + " 1 FROM sys_dept c WHERE c.parent_id=d.id) AND NOT EXISTS(SELECT 1"
                            + " FROM sys_user u WHERE u.dept_id=d.id) ORDER BY d.id LIMIT 2");
        assertEquals(2, available.size(), "Requires the two isolated test leaves");
        source = ((Number) available.get(0).get("id")).longValue();
        sourceCode = available.get(0).get("dept_code").toString();
        target = ((Number) available.get(1).get("id")).longValue();
        targetCode = available.get(1).get("dept_code").toString();
        tx.executeWithoutResult(
                status -> {
                    organization
                            .organization()
                            .forEach(d -> signatures.sign(DEPARTMENT, d.getId()));
                    signatures.sign(ORGANIZATION, 0);
                });
    }

    @BeforeEach
    void fixtures() {
        port.rejectInside = false;
        port.failAfterSign = false;
        port.corruptMergedIdentity = false;
        port.corruptOtherDepartment = false;
        port.mismatchEvent = false;
        port.transientDepartment = 0;
        port.unaffectedDepartment = target;
        grants.omitCollectionSignature = false;
        grants.corruptIdentity = false;
        failure.fail = false;
        failure.targetType = null;
        // Only this class's previous users are retired; source must contain this case's person
        // alone.
        tx.executeWithoutResult(
                status -> {
                    for (long id : accounts) {
                        db.update("UPDATE sys_user SET dept_id=? WHERE id=?", target, id);
                        signatures.sign(USER_IDENTITY, id);
                    }
                });
        person = account();
    }

    @AfterEach
    void restoreOrganization() {
        failure.fail = false;
        port.failAfterSign = false;
        if (originalDepartments != null)
            tx.executeWithoutResult(
                    status -> {
                        restoreDepartmentRows();
                        organization
                                .organization()
                                .forEach(d -> signatures.sign(DEPARTMENT, d.getId()));
                        signatures.sign(ORGANIZATION, 0);
                    });
    }

    @AfterAll
    void retire() {
        if (db != null && originalDepartments != null && originalOrgSignatures != null) {
            failure.fail = false;
            tx.executeWithoutResult(
                    status -> {
                        for (long id : accounts) {
                            db.update(
                                    "UPDATE sys_user SET status=2,session_version=session_version+1"
                                        + " WHERE id=?",
                                    id);
                            signatures.sign(USER_IDENTITY, id);
                            signatures.sign(USER_GRANTS, id);
                        }
                        restoreDepartmentRows();
                        db.update(
                                "DELETE FROM sys_data_signature WHERE data_type IN"
                                    + " ('ORGANIZATION','DEPARTMENT')");
                        for (var row : originalOrgSignatures)
                            db.update(
                                    "INSERT INTO"
                                        + " sys_data_signature(id,data_type,data_id,signature,sign_version,key_id,created_at,updated_at)"
                                        + " VALUES(?,?,?,?,?,?,?,?)",
                                    row.get("id"),
                                    row.get("data_type"),
                                    row.get("data_id"),
                                    row.get("signature"),
                                    row.get("sign_version"),
                                    row.get("key_id"),
                                    row.get("created_at"),
                                    row.get("updated_at"));
                    });
            assertEquals(
                    originalDepartments, db.queryForList("SELECT * FROM sys_dept ORDER BY id"));
            assertEquals(
                    originalOrgSignatures,
                    db.queryForList(
                            "SELECT * FROM sys_data_signature WHERE data_type IN"
                                + " ('ORGANIZATION','DEPARTMENT') ORDER BY data_type,data_id"));
            for (long id : accounts) {
                assertEquals(2, users.findById(id).status);
                assertEquals(0, count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id));
                assertEquals(
                        0,
                        count(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                    + " status=1",
                                id));
                assertEquals(
                        0,
                        count("SELECT COUNT(*) FROM sys_admin WHERE user_id=? AND status=1", id));
            }
            System.out.println(
                    "A_STAGE2_HR_CLEANUP prefix="
                            + prefix
                            + " retiredUsers="
                            + accounts.size()
                            + " originalOrganizationAndSignaturesRestored=true");
        }
        if (app != null) app.close();
    }

    @Test
    void newHrAccountCreatesIdentityAndEmptyGrantSignaturesInTheEventTransaction() {
        String sg = prefix + "N" + ++seq, event = event();
        var result =
                hr.employee(
                        actor,
                        sg,
                        new Employee(
                                "HR",
                                event,
                                1L,
                                sg,
                                "李四",
                                phone(),
                                sourceCode,
                                Action.UPSERT,
                                EmploymentType.FORMAL,
                                now()));
        long id = Long.parseLong(result.get("userId").toString());
        accounts.add(id);
        verify(USER_IDENTITY, id);
        verify(USER_GRANTS, id);
        assertEquals("APPLIED", eventStatus(event));
        tx.executeWithoutResult(
                status -> {
                    var facts = (Map<?, ?>) signatures.snapshot(USER_GRANTS, id).get("facts");
                    assertEquals(List.of(), facts.get("roles"));
                    assertEquals(List.of(), facts.get("sources"));
                });
    }

    @Test
    void profileUpdateUsesRealIdentitySignatureAndSanitizedSealedHrEvidence() throws Exception {
        String event = event();
        hr.employee(actor, person.employeeNo, employee(event, 2, Action.UPSERT, sourceCode));
        verify(USER_IDENTITY, person.id);
        var evidence =
                json.readValue(
                        db.queryForObject(
                                "SELECT evidence FROM sys_operation_log WHERE external_event_id=?",
                                String.class,
                                event),
                        Map.class);
        @SuppressWarnings("unchecked")
        var seal = (Map<String, Object>) evidence.remove("seal");
        assertEquals(Boolean.TRUE, tx.execute(status -> signatures.verifySeal(evidence, seal)));
        assertEquals("HR", evidence.get("sourceSystem"));
        assertFalse(evidence.toString().contains(person.passwordHash));
        assertFalse(evidence.toString().contains(codec.decrypt(person.phone)));
        assertEquals("APPLIED", eventStatus(event));
    }

    @Test
    void suspendAndResumeSignCurrentStateWithoutRevivingTheOldSession() {
        String token = "Bearer " + app.getBean(JwtTokenService.class).issue(person.id, 1);
        tx.executeWithoutResult(
                status ->
                        assertEquals(
                                person.id.longValue(),
                                app.getBean(PermissionService.class).currentUser(token)));
        hr.employee(actor, person.employeeNo, employee(event(), 2, Action.SUSPEND, sourceCode));
        hr.employee(actor, person.employeeNo, employee(event(), 3, Action.RESUME, sourceCode));
        assertEquals(1, users.findById(person.id).status);
        assertEquals(2, users.findById(person.id).sessionVersion);
        verify(USER_IDENTITY, person.id);
        expect(
                ErrorCode.INVALID_TOKEN,
                () ->
                        tx.executeWithoutResult(
                                status -> app.getBean(PermissionService.class).currentUser(token)));
    }

    @Test
    void rehireUsesSameAccountAndDoesNotRestoreHistoricalGrants() {
        hr.terminate(actor, new Termination("HR", event(), 2L, person.employeeNo, now()));
        hr.employee(actor, person.employeeNo, employee(event(), 3, Action.REHIRE, sourceCode));
        assertEquals(1, users.findById(person.id).status);
        assertEquals(3, users.findById(person.id).sessionVersion);
        assertEquals(0, count("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", person.id));
        verify(USER_IDENTITY, person.id);
        verify(USER_GRANTS, person.id);
    }

    @Test
    void transferReloadsTheCProducedGrantRevisionBeforeSigningIdentity() {
        String event = event();
        hr.transfer(
                actor,
                new Transfer("HR", event, 2L, person.employeeNo, sourceCode, targetCode, now()));
        assertEquals(target, users.findById(person.id).deptId);
        assertEquals(2, users.findById(person.id).grantRevision);
        verify(USER_IDENTITY, person.id);
        verify(USER_GRANTS, person.id);
        assertEquals("APPLIED", eventStatus(event));
    }

    @Test
    void employeeUpsertDepartmentChangeUsesTheSameCAndDMovePath() {
        hr.employee(actor, person.employeeNo, employee(event(), 2, Action.UPSERT, targetCode));
        assertEquals(target, users.findById(person.id).deptId);
        assertEquals(2, users.findById(person.id).grantRevision);
        verify(USER_IDENTITY, person.id);
    }

    @Test
    void terminationSignsNewStatusSessionAndCRevisionAndInvalidatesOldSession() {
        String token = "Bearer " + app.getBean(JwtTokenService.class).issue(person.id, 1);
        tx.executeWithoutResult(
                status ->
                        assertEquals(
                                person.id.longValue(),
                                app.getBean(PermissionService.class).currentUser(token)));
        hr.terminate(actor, new Termination("HR", event(), 2L, person.employeeNo, now()));
        UserAccount current = users.findById(person.id);
        assertEquals(3, current.status);
        assertEquals(2, current.sessionVersion);
        assertEquals(2, current.grantRevision);
        verify(USER_IDENTITY, person.id);
        verify(USER_GRANTS, person.id);
        expect(
                ErrorCode.INVALID_TOKEN,
                () ->
                        tx.executeWithoutResult(
                                status -> app.getBean(PermissionService.class).currentUser(token)));
    }

    @Test
    void departmentRenameSignsTheDepartmentAndCompleteOrganization() {
        var old = department(source);
        String event = event();
        departments.sync(
                actor,
                sourceCode,
                new DepartmentRequests.Sync(
                        "HR",
                        event,
                        version(old),
                        sourceCode,
                        "HR fixture renamed",
                        parentCode(old),
                        null,
                        now()));
        verify(DEPARTMENT, source);
        verify(ORGANIZATION, 0);
        assertEquals("APPLIED", eventStatus(event));
    }

    @Test
    void departmentReparentSignsChangedPathsAndCompleteOrganization() {
        var old = department(source);
        String event = event();
        departments.sync(
                actor,
                sourceCode,
                new DepartmentRequests.Sync(
                        "HR",
                        event,
                        version(old),
                        sourceCode,
                        old.get("dept_name").toString(),
                        targetCode,
                        null,
                        now()));
        verify(DEPARTMENT, source);
        verify(ORGANIZATION, 0);
        assertEquals(
                department(target).get("dept_path") + Long.toString(source) + "/",
                department(source).get("dept_path"));
    }

    @Test
    void newDepartmentIsSignedInItsTransactionThenRolledBackWithoutLeavingAMember() {
        String code = prefix + "D", event = event();
        int before = count("SELECT COUNT(*) FROM sys_dept");
        port.failAfterSign = true;
        expect(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                () ->
                        departments.sync(
                                actor,
                                code,
                                new DepartmentRequests.Sync(
                                        "HR",
                                        event,
                                        1L,
                                        code,
                                        "HR new fixture",
                                        targetCode,
                                        null,
                                        now())));
        assertTrue(port.transientDepartment > 0);
        assertEquals(before, count("SELECT COUNT(*) FROM sys_dept"));
        assertEquals(0, count("SELECT COUNT(*) FROM sys_dept WHERE dept_code=?", code));
        assertEquals("PENDING_RECOVERY", eventStatus(event));
    }

    @Test
    void mergeSignsEveryMovedIdentityAndKeepsTheGrantCollectionUnchanged() throws Exception {
        String grantsBefore = signature(USER_GRANTS, person.id), event = event();
        departments.merge(actor, merge(event));
        verify(DEPARTMENT, source);
        verify(DEPARTMENT, target);
        verify(ORGANIZATION, 0);
        verify(USER_IDENTITY, person.id);
        assertEquals(target, users.findById(person.id).deptId);
        assertEquals(grantsBefore, signature(USER_GRANTS, person.id));
        assertEquals("APPLIED", eventStatus(event));
        var entries =
                db.queryForList(
                        "SELECT target_type,target_id,evidence FROM sys_operation_log WHERE"
                            + " external_event_id=? ORDER BY id",
                        event);
        assertEquals(2, entries.size());
        for (var entry : entries) {
            var evidence = json.readValue(entry.get("evidence").toString(), Map.class);
            @SuppressWarnings("unchecked")
            var seal = (Map<String, Object>) evidence.remove("seal");
            assertEquals(Boolean.TRUE, tx.execute(status -> signatures.verifySeal(evidence, seal)));
            if ("USER".equals(entry.get("target_type"))) {
                assertEquals(person.id.longValue(), ((Number) entry.get("target_id")).longValue());
                assertEquals(
                        Long.toString(source), ((Map<?, ?>) evidence.get("before")).get("deptId"));
                assertEquals(
                        Long.toString(target), ((Map<?, ?>) evidence.get("after")).get("deptId"));
            } else {
                assertEquals("DEPARTMENT", entry.get("target_type"));
                assertEquals("1", evidence.get("movedUserCount"));
                assertFalse(evidence.containsKey("movedUsers"));
            }
        }
    }

    @Test
    void mergeRejectsTamperedUnprojectedIdentityWithoutReplacingItsSignature() {
        String before = signature(USER_IDENTITY, person.id), event = event();
        db.update("UPDATE sys_user SET password_hash='tampered' WHERE id=?", person.id);
        expect(ErrorCode.INTEGRITY_FAILURE, () -> departments.merge(actor, merge(event)));
        assertEquals(source, users.findById(person.id).deptId);
        assertEquals(before, signature(USER_IDENTITY, person.id));
        assertNull(department(source).get("merged_into_dept_id"));
    }

    @Test
    void mergeAuditFailureRollsBackPeopleOrganizationSignaturesAndAppliedResult() {
        String userBefore = signature(USER_IDENTITY, person.id),
                orgBefore = signature(ORGANIZATION, 0),
                event = event();
        failure.fail = true;
        failure.targetType = "DEPARTMENT";
        expect(ErrorCode.AUDIT_UNAVAILABLE, () -> departments.merge(actor, merge(event)));
        assertEquals(source, users.findById(person.id).deptId);
        assertEquals(userBefore, signature(USER_IDENTITY, person.id));
        assertEquals(orgBefore, signature(ORGANIZATION, 0));
        assertNull(department(source).get("merged_into_dept_id"));
        assertEquals("PENDING_RECOVERY", eventStatus(event));
        assertEquals(
                0,
                count("SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?", event));
    }

    @Test
    void employeeAuditFailureRollsBackProfileAndIdentitySignature() {
        var before = db.queryForMap("SELECT * FROM sys_user WHERE id=?", person.id);
        String old = signature(USER_IDENTITY, person.id), event = event();
        failure.fail = true;
        expect(
                ErrorCode.AUDIT_UNAVAILABLE,
                () ->
                        hr.employee(
                                actor,
                                person.employeeNo,
                                employee(event, 2, Action.UPSERT, sourceCode)));
        assertEquals(before, db.queryForMap("SELECT * FROM sys_user WHERE id=?", person.id));
        assertEquals(old, signature(USER_IDENTITY, person.id));
        assertEquals("PENDING_RECOVERY", eventStatus(event));
    }

    @Test
    void aNeverRepairsAnUnsignedCGrantRevision() {
        String before = signature(USER_IDENTITY, person.id),
                oldGrants = signature(USER_GRANTS, person.id);
        grants.omitCollectionSignature = true;
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        hr.transfer(
                                actor,
                                new Transfer(
                                        "HR",
                                        event(),
                                        2L,
                                        person.employeeNo,
                                        sourceCode,
                                        targetCode,
                                        now())));
        assertEquals(1, users.findById(person.id).grantRevision);
        assertEquals(source, users.findById(person.id).deptId);
        assertEquals(before, signature(USER_IDENTITY, person.id));
        assertEquals(oldGrants, signature(USER_GRANTS, person.id));
    }

    @Test
    void constructingATrustedContextCannotAuthorizeHrSigning() {
        expect(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                () ->
                        tx.executeWithoutResult(
                                status ->
                                        data.signOrganization(
                                                "HR_EMPLOYEE",
                                                actor,
                                                Map.of(
                                                        "eventId",
                                                        event(),
                                                        "userId",
                                                        person.id.toString()))));
    }

    @Test
    void serviceAuthorityIsRecheckedInsideTheEventWriteTransaction() {
        port.rejectInside = true;
        String event = event();
        var before = users.findById(person.id);
        expect(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                () ->
                        hr.employee(
                                actor,
                                person.employeeNo,
                                employee(event, 2, Action.UPSERT, sourceCode)));
        assertEquals(before.hrVersion, users.findById(person.id).hrVersion);
        assertEquals("PENDING_RECOVERY", eventStatus(event));
        assertNull(ExternalEventService.applying(actor, "HR_EMPLOYEE", event));
    }

    @Test
    void mergeCannotSealAnUnexpectedIdentityMutationAfterTheInitialVerification() {
        port.corruptMergedIdentity = true;
        String old = signature(USER_IDENTITY, person.id);
        expect(ErrorCode.INTEGRITY_FAILURE, () -> departments.merge(actor, merge(event())));
        assertEquals(person.passwordHash, users.findById(person.id).passwordHash);
        assertEquals(source, users.findById(person.id).deptId);
        assertEquals(old, signature(USER_IDENTITY, person.id));
    }

    @Test
    void hrAuditCannotBorrowTheSameTransactionForADifferentEventId() {
        port.mismatchEvent = true;
        String old = signature(USER_IDENTITY, person.id);
        expect(
                ErrorCode.AUTH_STATE_UNAVAILABLE,
                () ->
                        hr.employee(
                                actor,
                                person.employeeNo,
                                employee(event(), 2, Action.UPSERT, sourceCode)));
        assertEquals(1, users.findById(person.id).hrVersion);
        assertEquals(old, signature(USER_IDENTITY, person.id));
    }

    @Test
    void missingOriginalDepartmentSignatureCannotBeInitializedByHrSync() {
        db.update(
                "DELETE FROM sys_data_signature WHERE data_type='DEPARTMENT' AND data_id=?",
                source);
        var old = department(source);
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        departments.sync(
                                actor,
                                sourceCode,
                                new DepartmentRequests.Sync(
                                        "HR",
                                        event(),
                                        version(old),
                                        sourceCode,
                                        "untrusted",
                                        parentCode(old),
                                        null,
                                        now())));
        assertEquals(
                0,
                count(
                        "SELECT COUNT(*) FROM sys_data_signature WHERE data_type='DEPARTMENT' AND"
                            + " data_id=?",
                        source));
    }

    @Test
    void hrTransferCannotSealAPasswordMutationPerformedByAnotherCollaborator() {
        grants.corruptIdentity = true;
        String before = signature(USER_IDENTITY, person.id);
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        hr.transfer(
                                actor,
                                new Transfer(
                                        "HR",
                                        event(),
                                        2L,
                                        person.employeeNo,
                                        sourceCode,
                                        targetCode,
                                        now())));
        assertEquals(person.passwordHash, users.findById(person.id).passwordHash);
        assertEquals(before, signature(USER_IDENTITY, person.id));
        assertEquals(1, users.findById(person.id).grantRevision);
    }

    @Test
    void hrDepartmentCannotSealAnUndeclaredChangeToAnotherDepartment() {
        port.corruptOtherDepartment = true;
        var old = department(source);
        String before = signature(ORGANIZATION, 0);
        expect(
                ErrorCode.INTEGRITY_FAILURE,
                () ->
                        departments.sync(
                                actor,
                                sourceCode,
                                new DepartmentRequests.Sync(
                                        "HR",
                                        event(),
                                        version(old),
                                        sourceCode,
                                        "fixture rename",
                                        parentCode(old),
                                        null,
                                        now())));
        assertEquals(1, ((Number) department(target).get("status")).intValue());
        assertEquals(before, signature(ORGANIZATION, 0));
    }

    @Test
    void verifiedEventExecutionDoesNotEscapeToRequiresNewOrTheNextTransaction() {
        String event = event();
        var nested = new TransactionTemplate(tx.getTransactionManager());
        nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        var command =
                new ExternalEventService.Command(
                        event,
                        "HR_EMPLOYEE",
                        2L,
                        "USER",
                        person.employeeNo,
                        now(),
                        Map.of("case", "execution-lifecycle"));
        app.getBean(ExternalEventService.class)
                .execute(
                        actor,
                        command,
                        () -> {
                            assertNotNull(
                                    ExternalEventService.applying(actor, "HR_EMPLOYEE", event));
                            nested.executeWithoutResult(
                                    status ->
                                            assertNull(
                                                    ExternalEventService.applying(
                                                            actor, "HR_EMPLOYEE", event)));
                            assertNotNull(
                                    ExternalEventService.applying(actor, "HR_EMPLOYEE", event));
                            return Map.of("eventId", event, "processingStatus", "APPLIED");
                        });
        tx.executeWithoutResult(
                status -> assertNull(ExternalEventService.applying(actor, "HR_EMPLOYEE", event)));
    }

    UserAccount account() {
        String username = prefix + ++seq, sg = prefix + "S" + seq, phone = phone();
        long id =
                insert(
                        "INSERT INTO"
                            + " sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,employee_no,dept_id,source,employment_type,status,password_initialized,session_version,grant_revision,hr_version)"
                            + " VALUES(?,?,?,?,?,?,?,1,1,1,1,1,1,1)",
                        username,
                        codec.encrypt("张三"),
                        codec.encrypt(phone),
                        codec.phoneHash(phone),
                        app.getBean(PasswordHasher.class).hash("Cobalt!Ridge847"),
                        sg,
                        source);
        accounts.add(id);
        tx.executeWithoutResult(
                status -> {
                    signatures.sign(USER_IDENTITY, id);
                    signatures.sign(USER_GRANTS, id);
                });
        return users.findById(id);
    }

    Employee employee(String event, long version, Action action, String department) {
        return new Employee(
                "HR",
                event,
                version,
                person.employeeNo,
                "李四",
                phone(),
                department,
                action,
                EmploymentType.FORMAL,
                now());
    }

    DepartmentRequests.Merge merge(String event) {
        return new DepartmentRequests.Merge(
                "HR", event, version(department(source)), sourceCode, targetCode, now());
    }

    Map<String, Object> department(long id) {
        return db.queryForMap("SELECT * FROM sys_dept WHERE id=?", id);
    }

    String parentCode(Map<String, Object> row) {
        long id = ((Number) row.get("parent_id")).longValue();
        return id == 0 ? "" : department(id).get("dept_code").toString();
    }

    long version(Map<String, Object> row) {
        return ((Number) row.get("hr_version")).longValue() + 1;
    }

    String event() {
        return prefix + "E" + ++seq;
    }

    String phone() {
        return "137"
                + String.format(
                        "%08d", Integer.toUnsignedLong((prefix + ++seq).hashCode()) % 100000000);
    }

    OffsetDateTime now() {
        return OffsetDateTime.now().minusMinutes(1);
    }

    String eventStatus(String event) {
        return db.queryForObject(
                "SELECT processing_status FROM sys_external_event WHERE source_system='HR' AND"
                    + " event_id=?",
                String.class,
                event);
    }

    String signature(SignedDataType type, long id) {
        return db.queryForObject(
                "SELECT signature FROM sys_data_signature WHERE data_type=? AND data_id=?",
                String.class,
                type.name(),
                id);
    }

    void verify(SignedDataType type, long id) {
        tx.executeWithoutResult(status -> signatures.verify(type, id));
    }

    int count(String sql, Object... args) {
        return db.queryForObject(sql, Integer.class, args);
    }

    long insert(String sql, Object... args) {
        var key = new GeneratedKeyHolder();
        db.update(
                c -> {
                    var s = c.prepareStatement(sql, new String[] {"id"});
                    for (int i = 0; i < args.length; i++) s.setObject(i + 1, args[i]);
                    return s;
                },
                key);
        return Objects.requireNonNull(key.getKey()).longValue();
    }

    void restoreDepartmentRows() {
        for (var row : originalDepartments)
            db.update(
                    "UPDATE sys_dept SET"
                        + " parent_id=?,dept_code=?,dept_name=?,dept_path=?,manager_id=?,status=?,merged_into_dept_id=?,hr_version=?,sort_order=?,created_at=?,updated_at=?"
                        + " WHERE id=?",
                    row.get("parent_id"),
                    row.get("dept_code"),
                    row.get("dept_name"),
                    row.get("dept_path"),
                    row.get("manager_id"),
                    row.get("status"),
                    row.get("merged_into_dept_id"),
                    row.get("hr_version"),
                    row.get("sort_order"),
                    row.get("created_at"),
                    row.get("updated_at"),
                    row.get("id"));
    }

    static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    static void expect(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).errorCode());
    }
}
