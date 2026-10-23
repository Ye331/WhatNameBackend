package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.hr.HrRequests.*;
import com.whatname.iam.entity.department.Department;
import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.repository.department.OrganizationWriteRepository;
import com.whatname.iam.repository.event.ExternalEventRepository;
import com.whatname.iam.repository.user.UserAccountRepository;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.service.department.*;
import com.whatname.iam.service.event.*;
import com.whatname.iam.service.hr.*;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.utility.*;

import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 真实的 HR 编排、授权写入、签名审计和隔离 MySQL；只有 HR 服务认证和账号安全入口是测试替身。 */
@SpringBootTest(
        classes = {
            com.whatname.iam.IamApplication.class,
            HrGrantCollaborationIT.Configuration.class
        },
        properties = "spring.config.import=")
@Testcontainers
class HrGrantCollaborationIT {
    @TestConfiguration
    static class Configuration {
        @Bean
        Fault hrFault() {
            return new Fault();
        }
    }

    @Container
    static final MySQLContainer<?> MYSQL =
            new MySQLContainer<>(
                            DockerImageName.parse(
                                            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b")
                                    .asCompatibleSubstituteFor("mysql"))
                    .withDatabaseName("unified_permission")
                    .withTmpFs(Map.of("/var/lib/mysql", "rw"))
                    .withCommand("--default-time-zone=+08:00")
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("db/schema.sql").toAbsolutePath()),
                            "/docker-entrypoint-initdb.d/01.sql");

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        GrantIntegrationIT.settings(r);
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);

    }

    @org.aspectj.lang.annotation.Aspect
    static class Fault extends com.whatname.iam.support.RepositoryProbe {
        volatile String point, hit;

        public Object intercept(Invocation call) throws Throwable {
            String id = call.id();
            Object result = call.proceed();
            if (point != null
                    && ((point.equals("APPLIED") && id.endsWith("ExternalEventRepository.applied"))
                            || (id.endsWith("SignatureRepository.save")
                                    && point.equals(((Map<?, ?>) call.parameters()).get("type")))
                            || (id.endsWith("AuditRepository.insert")
                                    && point.equals(
                                            ((Map<?, ?>) call.parameters()).get("operation"))))) {
                hit = point;
                throw new IllegalStateException("fixture persistence failure");
            }
            return result;
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired HrGrantCollaboration c;
    @Autowired AdminOrganizationLifecycle lifecycle;
    @Autowired AdminPolicyService policy;
    @Autowired DepartmentAccessService productionTrust;
    @Autowired OrganizationWriteRepository org;
    @Autowired UserAccountRepository users;
    @Autowired ExternalEventRepository eventMapper;
    @Autowired PasswordHasher passwords;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager manager;
    @Autowired DataSignatureService signatures;
    @Autowired AuditService audits;
    @Autowired PermissionService permissions;
    @Autowired JwtTokenService jwt;
    @Autowired GrantHistoryService history;
    @Autowired Fault fault;
    static final AtomicLong NEXT = new AtomicLong(1000);
    static final TrustedEventContext ACTOR =
            new TrustedEventContext("HR", "fixture-hr", "fixture-request");
    static final OffsetDateTime AT = OffsetDateTime.parse("2026-10-18T00:00:00+08:00");
    long b;
    String protocol = "";
    CountDownLatch ready, release;

    long id(int n) {
        return b + n;
    }

    String code(int n) {
        return "D" + id(n);
    }

    String employee() {
        return "SG" + id(20);
    }

    String event() {
        return "hr-" + b;
    }

    TransactionTemplate tx, serial;

    @BeforeEach
    void seed() {
        b = NEXT.getAndAdd(1000);
        fault.point = null;
        fault.hit = null;
        protocol = "";
        ready = null;
        release = null;
        tx = new TransactionTemplate(manager);
        serial = new TransactionTemplate(manager);
        serial.setIsolationLevel(java.sql.Connection.TRANSACTION_SERIALIZABLE);
        for (int d : List.of(1, 2, 3, 4))
            db.update(
                    "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                            + " VALUES(?,?,?,'Fixture',?)",
                    id(d),
                    d == 1 ? 0 : id(d == 4 ? 2 : 1),
                    code(d),
                    "/"
                            + id(1)
                            + "/"
                            + (d == 1 ? "" : d == 4 ? id(2) + "/" + id(4) + "/" : id(d) + "/"));
        for (int u : List.of(10, 20, 21, 22))
            db.update(
                    "INSERT INTO"
                        + " sys_user(id,username,employee_no,real_name,phone,password_hash,dept_id,source,employment_type,status,password_initialized,hr_version)"
                        + " VALUES(?,?,?,'fixture',?,'fixture',?,1,1,1,1,1)",
                    id(u),
                    "U" + id(u),
                    "SG" + id(u),
                    String.format("138%08d", id(u)),
                    id(u == 10 ? 1 : u == 20 ? 2 : u == 21 ? 4 : 3));
        for (int u : List.of(10, 20, 21, 22))
            db.update(
                    "INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(?,?,?,1)",
                    id(u),
                    u == 10 ? 1 : u == 21 ? 3 : 2,
                    id(u == 10 ? 1 : u == 20 ? 2 : u == 21 ? 4 : 3));
        for (int r : List.of(30, 31, 32))
            db.update(
                    "INSERT INTO sys_role(id,role_code,role_name,role_type,status)"
                            + " VALUES(?,?,'Fixture',?,?)",
                    id(r),
                    "R" + id(r),
                    r == 32 ? 2 : 1,
                    r == 32 ? 0 : 1);
        for (int r : List.of(30, 31))
            db.update(
                    "INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                            + " VALUES(?,?,?)",
                    id(20),
                    id(r),
                    id(10));
        db.update(
                "INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                        + " VALUES(?,?,?),(?,?,?)",
                id(21),
                id(30),
                id(20),
                id(21),
                id(32),
                id(10));
        source(30, "DEPT_POSITION", "old-one", 2);
        source(30, "DEPT_POSITION", "old-two", 2);
        source(30, "MANUAL", "manual", null);
        source(30, "OA_APPROVAL", "approval", null);
        source(30, "DEPT_POSITION", "other", 3);
        source(31, "DEPT_POSITION", "last-old", 2);
        source(32, "MANUAL", "disabled", null);
        for (int r : List.of(30, 31, 32))
            db.update(
                    "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                    id(20),
                    id(r),
                    id(10));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(ORGANIZATION, 0);
                    for (int d : List.of(1, 2, 3, 4)) signatures.sign(DEPARTMENT, id(d));
                    for (int a : List.of(10, 20, 21, 22)) {
                        signatures.sign(ADMIN, id(a));
                        signatures.sign(ADMIN_DELEGABLE_ROLES, id(a));
                    }
                    for (int r : List.of(30, 31, 32)) signatures.sign(ROLE_MENUS, id(r));
                    for (int u : List.of(10, 20, 21, 22)) {
                        signatures.sign(USER_IDENTITY, id(u));
                        signatures.sign(USER_GRANTS, id(u));
                    }
                });
    }

    void source(int role, String type, String ref, Integer department) {
        db.update(
                "INSERT INTO"
                    + " sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id,granted_by)"
                    + " VALUES(?,?,?,?,?,?)",
                id(20),
                id(role),
                type,
                ref,
                department == null ? null : id(department),
                id(10));
    }

    @AfterEach
    void resetFault() {
        fault.point = null;
    }

    /**
     * No production bean replacement: these two adapters are local to the explicit internal test
     * call.
     */
    HrService service() {
        DepartmentAccessService port =
                new DepartmentAccessService(null, null, null, null, null, null, null) {
                    public Manager authenticateManager(
                            jakarta.servlet.http.HttpServletRequest r, String p) {
                        throw new UnsupportedOperationException();
                    }

                    public TrustedEventContext authenticateHr(
                            jakarta.servlet.http.HttpServletRequest r, String t) {
                        throw new UnsupportedOperationException();
                    }

                    public void verifyTrustedEvent(TrustedEventContext a, String t) {
                        if (!ACTOR.equals(a)
                                || !Set.of("HR_EMPLOYEE", "HR_TRANSFER", "HR_TERMINATION")
                                        .contains(t)) throw new BusinessException(INVALID_SERVICE);
                    }

                    public void authorizeRecovery(TrustedEventContext a, String t) {
                        throw new BusinessException(INVALID_SERVICE);
                    }

                    public Set<Long> scope(Manager a, String p, List<Department> d) {
                        throw new UnsupportedOperationException();
                    }

                    public void verifyOrganization(List<Department> ds) {
                        signatures.verify(ORGANIZATION, 0);
                        ds.forEach(d -> signatures.verify(DEPARTMENT, d.getId()));
                    }

                    public void verifyUsers(List<Map<String, Object>> us) {
                        us.forEach(
                                u ->
                                        signatures.verify(
                                                USER_IDENTITY, ((Number) u.get("id")).longValue()));
                    }

                    public void organizationChanged(Set<Long> d, List<Map<String, Object>> u) {
                        throw new UnsupportedOperationException();
                    }

                    public void signAndAudit(
                            String operation, Object actor, Map<String, Object> evidence) {
                        audits.change(
                                operation,
                                null,
                                "USER",
                                id(20),
                                evidence.get("eventId").toString(),
                                evidence);
                    }

                    @Override
                    public void prepareManagement(Manager actor) {}

                    @Override
                    public void prepareDepartmentManagement(Manager actor, long departmentId) {
                        prepareManagement(actor);
                    }

                    @Override
                    public void prepareOrganizationChange(
                            com.whatname.iam.service.event.TrustedEventContext actor,
                            String eventId) {
                        throw new com.whatname.iam.common.exception.BusinessException(
                                com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE);
                    }
                };
        AccountSecurity identity =
                new AccountSecurity(null, null) {
                    public void verifyIdentity(UserAccount u) {
                        signatures.verify(USER_IDENTITY, u.id);
                    }

                    public void signIdentity(UserAccount u) {
                        if (!protocol.equals("missing-identity"))
                            signatures.sign(USER_IDENTITY, u.id);
                    }

                    public Set<Long> authorizeManagement(UserAccount a, String p) {
                        throw new UnsupportedOperationException();
                    }

                    public void audit(String o, Long a, Long t, String reason) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public void prepareManagement(long actorId, long... targetUserIds) {}

                    @Override
                    public void prepareDepartmentManagement(long actorId, long departmentId) {
                        prepareManagement(actorId);
                    }
                };
        var events = new ExternalEventService(eventMapper, port, json, manager, null);
        return new HrService(
                events,
                org,
                new DepartmentService(org, port, events),
                port,
                users,
                identity,
                new Participant(),

                passwords,
                Clock.systemUTC());
    }

    class Participant extends HrGrantCollaboration {
        Participant() {
            super(null, null, null, null, null, null, null);
        }

        public void prepare(TrustedEventContext a, String event, String type, String employeeNo) {
            if (protocol.equals("missing-prepare")) return;
            c.prepare(a, event, type, employeeNo);
            if (protocol.equals("duplicate-prepare")) c.prepare(a, event, type, employeeNo);
        }

        public void revokePosition(TrustedEventContext a, String event, long user, long dept) {
            c.revokePosition(a, event, user, dept);
        }

        public void revokeAll(TrustedEventContext a, String event, long user) {
            if (protocol.equals("wrong-event")) event += "-wrong";
            c.revokeAll(a, event, user);
            if (protocol.equals("duplicate-revoke")) c.revokeAll(a, event, user);
        }

        public void requireRehireEligible(long user) {
            c.requireRehireEligible(user);
        }

        public void departmentChanged(long user, long old, long next) {
            c.departmentChanged(user, old, next);
        }

        public void complete(long user) {
            if (protocol.equals("missing-complete")) return;
            if (protocol.equals("suspend")) {
                var inner = new TransactionTemplate(manager);
                inner.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                assertThatThrownBy(() -> inner.executeWithoutResult(s -> c.complete(user)))
                        .isInstanceOf(BusinessException.class);
            }
            c.complete(user);
            if (ready != null) {
                ready.countDown();
                awaitRelease();
            }
            if (protocol.equals("duplicate-complete")) c.complete(user);
            if (protocol.equals("late-grant-revision")) {
                db.update("UPDATE sys_user SET grant_revision=grant_revision+1 WHERE id=?", user);
                signatures.sign(USER_IDENTITY, user);
                signatures.sign(USER_GRANTS, user);
            }
            if (protocol.equals("late-profile")) {
                db.update("UPDATE sys_user SET hr_version=hr_version+1 WHERE id=?", user);
                signatures.sign(USER_IDENTITY, user);
            }
        }
    }

    Transfer transfer() {
        return new Transfer("HR", event(), 2L, employee(), code(2), code(3), AT);
    }

    Termination termination() {
        return new Termination("HR", event(), 2L, employee(), AT);
    }

    Employee employee(Action action, String event, long version, int dept) {
        return new Employee(
                "HR",
                event,
                version,
                employee(),
                "测试员工",
                String.format("138%08d", id(20)),
                code(dept),
                action,
                EmploymentType.FORMAL,
                AT);
    }

    long scalar(String sql, Object... args) {
        return db.queryForObject(sql, Long.class, args);
    }

    String state(String event) {
        return db.queryForObject(
                "SELECT processing_status FROM sys_external_event WHERE event_id=?",
                String.class,
                event);
    }

    Map<String, Object> facts() {
        return Map.of(
                "person",
                db.queryForList(
                        "SELECT id,dept_id,status,session_version,grant_revision,hr_version FROM"
                                + " sys_user ORDER BY id"),
                "sources",
                db.queryForList(
                        "SELECT id,user_id,role_id,status,revoked_by,revoked_at FROM"
                                + " sys_user_role_source ORDER BY id"),
                "roles",
                db.queryForList(
                        "SELECT user_id,role_id FROM sys_user_role ORDER BY user_id,role_id"),
                "admins",
                db.queryForList(
                        "SELECT user_id,status,delegable_revision FROM sys_admin ORDER BY user_id"),
                "lists",
                db.queryForList(
                        "SELECT admin_user_id,role_id,configured_by FROM sys_admin_delegable_role"
                                + " ORDER BY admin_user_id,role_id"));
    }

    void verifiable() {
        tx.executeWithoutResult(
                s -> {
                    signatures.verify(USER_IDENTITY, id(20));
                    signatures.verify(USER_GRANTS, id(20));
                    for (int u : List.of(20, 21, 22)) {
                        signatures.verify(ADMIN, id(u));
                        signatures.verify(ADMIN_DELEGABLE_ROLES, id(u));
                    }
                });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void gr04ReadsActualHrSourceDeltasAfterTransferOrTerminationAndReplay(boolean termination) {
        var producer = service();
        if (termination) producer.terminate(ACTOR, termination());
        else producer.transfer(ACTOR, transfer());
        long reader = GrantHistoryQueries.bootstrap(db, signatures, manager, permissions, b, id(1));
        var first = GrantHistoryQueries.read(history, jwt, reader, id(20), null, 1, 1);
        var last =
                GrantHistoryQueries.read(
                        history, jwt, reader, id(20), null, termination ? 7 : 3, 1);
        assertThat(first.total()).isEqualTo(termination ? 7 : 3);
        assertThat(last.items()).hasSize(1);
        assertThat(last.items().getFirst().operation()).isEqualTo("REVOKE");
        assertThat(last.items().getFirst().eventId()).isEqualTo(first.items().getFirst().eventId());
        var filtered =
                GrantHistoryQueries.read(
                        history,
                        jwt,
                        reader,
                        id(20),
                        Long.toString(id(termination ? 32 : 30)),
                        1,
                        20);
        assertThat(filtered.total()).isEqualTo(termination ? 1 : 2);
        if (termination) producer.terminate(ACTOR, termination());
        else producer.transfer(ACTOR, transfer());
        assertThat(GrantHistoryQueries.read(history, jwt, reader, id(20), null, 1, 20).total())
                .isEqualTo(termination ? 7 : 3);
    }

    @Test
    void transferRevokesOnlyOldPositionSourcesAndReplaysWithoutExtraRevisionOrAudit() {
        var service = service();
        var request = transfer();
        var result = service.transfer(ACTOR, request);
        assertThat(result.get("awaitingNewRoleGrant")).isEqualTo(true);
        assertThat(
                        db.queryForList(
                                "SELECT source_ref FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1 ORDER BY source_ref",
                                String.class,
                                id(20)))
                .containsExactly("approval", "disabled", "manual", "other");
        assertThat(
                        db.queryForList(
                                "SELECT role_id FROM sys_user_role WHERE user_id=? ORDER BY"
                                        + " role_id",
                                Long.class,
                                id(20)))
                .containsExactly(id(30), id(32));
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isEqualTo(7);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                    + " status=0 AND revoked_by IS NULL AND revoked_at IS NOT NULL",
                                id(20)))
                .isEqualTo(3);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(scalar("SELECT session_version FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(scalar("SELECT dept_id FROM sys_user WHERE id=?", id(20))).isEqualTo(id(3));
        assertThat(state(event())).isEqualTo("APPLIED");
        long logs =
                scalar("SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?", event());
        assertThat(service.transfer(ACTOR, request)).isEqualTo(result);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",
                                event()))
                .isEqualTo(logs);
        assertThat(scalar("SELECT status FROM sys_admin WHERE user_id=?", id(20))).isZero();
        assertThat(scalar("SELECT status FROM sys_admin WHERE user_id=?", id(22))).isZero();
        assertThat(
                        db.queryForList(
                                "SELECT role_id FROM sys_admin_delegable_role WHERE"
                                        + " admin_user_id=?",
                                Long.class,
                                id(21)))
                .containsExactly(id(32));
        verifiable();
    }

    @Test
    void employeeDepartmentChangeUsesSamePreciseRevokeRule() {
        service().employee(ACTOR, employee(), employee(Action.UPSERT, event(), 2, 3));
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isEqualTo(4);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(state(event())).isEqualTo("APPLIED");
        verifiable();
    }

    @Test
    void terminationCleansAllQualificationsAndRehireNeverRestoresHistory() {
        var service = service();
        var applied = service.terminate(ACTOR, termination());
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(3);
        assertThat(scalar("SELECT session_version FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isZero();
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isEqualTo(7);
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id(20))).isZero();
        assertThat(scalar("SELECT status FROM sys_admin WHERE user_id=?", id(20))).isZero();
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_admin_delegable_role WHERE"
                                        + " admin_user_id=?",
                                id(20)))
                .isZero();
        assertThat(
                        db.queryForList(
                                "SELECT role_id FROM sys_admin_delegable_role WHERE"
                                        + " admin_user_id=?",
                                Long.class,
                                id(21)))
                .containsExactly(id(32));
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                permissions.currentUser(
                                                        "Bearer " + jwt.issue(id(20), 1))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(INVALID_TOKEN));
        verifiable();
        service.employee(ACTOR, employee(), employee(Action.REHIRE, event() + "-rehire", 3, 2));
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(scalar("SELECT session_version FROM sys_user WHERE id=?", id(20))).isEqualTo(3);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", id(20))).isZero();
        assertThat(scalar("SELECT status FROM sys_admin WHERE user_id=?", id(20))).isZero();
        assertThatThrownBy(
                        () ->
                                tx.execute(
                                        s ->
                                                permissions.currentUser(
                                                        "Bearer " + jwt.issue(id(20), 1))))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(INVALID_TOKEN));
        Long currentUser =
                tx.execute(s -> permissions.currentUser("Bearer " + jwt.issue(id(20), 3)));
        assertThat(currentUser).isEqualTo(id(20));
        long logs =
                scalar("SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?", event());
        assertThat(service.terminate(ACTOR, termination())).isEqualTo(applied);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",
                                event()))
                .isEqualTo(logs);
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        verifiable();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "USER_GRANTS",
                "USER_IDENTITY",
                "ADMIN_DELEGABLE_ROLES",
                "HR_GRANTS_REVOKE_ALL",
                "ADMIN_HR_REVIEW",
                "HR_TERMINATION",
                "APPLIED"
            })
    void persistenceFailureRollsBackCAndAPersonSignaturesAuditAndApplied(String point) {
        var before =
                db.queryForList(
                        "SELECT data_type,data_id,signature FROM sys_data_signature ORDER BY"
                                + " data_type,data_id");
        var beforeFacts = facts();
        fault.point = point;
        assertThatThrownBy(() -> service().terminate(ACTOR, termination()))
                .isInstanceOf(BusinessException.class);
        assertThat(fault.hit).isEqualTo(point);
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(scalar("SELECT session_version FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isEqualTo(7);
        assertThat(scalar("SELECT status FROM sys_admin WHERE user_id=?", id(20))).isEqualTo(1);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",
                                event()))
                .isZero();
        assertThat(
                        db.queryForList(
                                "SELECT data_type,data_id,signature FROM sys_data_signature ORDER"
                                        + " BY data_type,data_id"))
                .isEqualTo(before);
        assertThat(facts()).isEqualTo(beforeFacts);
        assertThat(state(event())).isEqualTo("PENDING_RECOVERY");
    }

    @Test
    void productionTrustStillRejectsConstructedContext() {
        assertThatThrownBy(() -> productionTrust.verifyTrustedEvent(ACTOR, "HR_TRANSFER"))
                .isInstanceOf(BusinessException.class);
        assertThatThrownBy(
                        () ->
                                productionTrust.authenticateHr(
                                        new org.springframework.mock.web.MockHttpServletRequest(),
                                        "HR_TRANSFER"))
                .isInstanceOf(BusinessException.class);
        assertThat(scalar("SELECT COUNT(*) FROM sys_external_event WHERE event_id=?", event()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"source", "relation", "appointment", "owned-list", "provided-list"})
    void rehireRejectsEveryResidualQualification(String residual) {
        db.update("UPDATE sys_user SET status=3 WHERE id=?", id(20));
        db.update(
                "UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE user_id=?",
                id(20));
        db.update("DELETE FROM sys_user_role WHERE user_id=?", id(20));
        db.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id(20));
        db.update(
                "DELETE FROM sys_admin_delegable_role WHERE admin_user_id=? OR configured_by=?",
                id(20),
                id(20));
        switch (residual) {
            case "source" ->
                    db.update(
                            "UPDATE sys_user_role_source SET status=1,revoked_at=NULL WHERE"
                                    + " user_id=? AND source_ref='manual'",
                            id(20));
            case "relation" ->
                    db.update(
                            "INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)",
                            id(20),
                            id(30));
            case "appointment" ->
                    db.update("UPDATE sys_admin SET status=1 WHERE user_id=?", id(20));
            case "owned-list" ->
                    db.update(
                            "INSERT INTO"
                                + " sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                                + " VALUES(?,?,?)",
                            id(20),
                            id(30),
                            id(10));
            case "provided-list" ->
                    db.update(
                            "INSERT INTO"
                                + " sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                                + " VALUES(?,?,?)",
                            id(21),
                            id(30),
                            id(20));
        }
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(USER_IDENTITY, id(20));
                    signatures.sign(USER_GRANTS, id(20));
                    for (int u : List.of(20, 21)) {
                        signatures.sign(ADMIN, id(u));
                        signatures.sign(ADMIN_DELEGABLE_ROLES, id(u));
                    }
                });
        assertThatThrownBy(
                        () ->
                                service()
                                        .employee(
                                                ACTOR,
                                                employee(),
                                                employee(Action.REHIRE, event(), 2, 2)))
                .isInstanceOf(BusinessException.class);
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(3);
        assertThat(scalar("SELECT session_version FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(state(event()))
                .isEqualTo(
                        Set.of("source", "relation").contains(residual)
                                ? "PENDING_RECOVERY"
                                : "REJECTED");
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",
                                event()))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing-signature", "tamper", "unbacked-relation", "missing-relation"})
    void corruptOrIncompleteGrantFactsNeverGetRepairedByTermination(String faultKind) {
        switch (faultKind) {
            case "missing-signature" ->
                    db.update(
                            "DELETE FROM sys_data_signature WHERE data_type='USER_GRANTS' AND"
                                    + " data_id=?",
                            id(20));
            case "tamper" ->
                    db.update(
                            "UPDATE sys_user_role_source SET source_ref='tampered' WHERE user_id=?"
                                    + " AND source_ref='manual'",
                            id(20));
            case "unbacked-relation" ->
                    db.update(
                            "UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE"
                                    + " user_id=? AND role_id=?",
                            id(20),
                            id(31));
            case "missing-relation" ->
                    db.update(
                            "DELETE FROM sys_user_role WHERE user_id=? AND role_id=?",
                            id(20),
                            id(31));
        }
        if (!Set.of("missing-signature", "tamper").contains(faultKind))
            tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
        var before =
                db.queryForList(
                        "SELECT * FROM sys_user_role_source WHERE user_id=? ORDER BY id", id(20));
        assertThatThrownBy(() -> service().terminate(ACTOR, termination()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(INTEGRITY_FAILURE));
        assertThat(
                        db.queryForList(
                                "SELECT * FROM sys_user_role_source WHERE user_id=? ORDER BY id",
                                id(20)))
                .isEqualTo(before);
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(state(event())).isEqualTo("PENDING_RECOVERY");
        if (faultKind.equals("missing-signature"))
            assertThat(
                            scalar(
                                    "SELECT COUNT(*) FROM sys_data_signature WHERE"
                                            + " data_type='USER_GRANTS' AND data_id=?",
                                    id(20)))
                    .isZero();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "missing-prepare",
                "duplicate-prepare",
                "duplicate-revoke",
                "wrong-event",
                "missing-complete",
                "duplicate-complete",
                "missing-identity",
                "late-grant-revision",
                "late-profile"
            })
    void participantProtocolCannotCommitIncompleteOrChangedFinalFacts(String kind) {
        var before = facts();
        protocol = kind;
        assertThatThrownBy(() -> service().terminate(ACTOR, termination()))
                .isInstanceOf(BusinessException.class);
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isEqualTo(7);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",
                                event()))
                .isZero();
        assertThat(state(event())).isIn("PENDING_RECOVERY", "REJECTED");
        assertThat(facts()).isEqualTo(before);
    }

    @Test
    void participantContextSuspendsResumesAndClearsAfterTransaction() {
        protocol = "suspend";
        assertThat(service().terminate(ACTOR, termination()).get("processingStatus"))
                .isEqualTo("APPLIED");
        serial.executeWithoutResult(
                s -> {
                    assertThatThrownBy(() -> c.requireRehireEligible(id(20)))
                            .isInstanceOf(BusinessException.class);
                    s.setRollbackOnly();
                });
        verifiable();
    }

    @Test
    void directParticipantRejectsMissingEventAndOrdinaryOrReadOnlyTransactions() {
        tx.executeWithoutResult(
                s -> {
                    assertThatThrownBy(
                                    () -> c.prepare(ACTOR, event(), "HR_TERMINATION", employee()))
                            .isInstanceOf(BusinessException.class);
                    s.setRollbackOnly();
                });
        serial.setReadOnly(true);
        serial.executeWithoutResult(
                s -> {
                    assertThatThrownBy(
                                    () -> c.prepare(ACTOR, event(), "HR_TERMINATION", employee()))
                            .isInstanceOf(BusinessException.class);
                    s.setRollbackOnly();
                });
        serial.setReadOnly(false);
        serial.executeWithoutResult(
                s -> {
                    assertThatThrownBy(
                                    () -> c.prepare(ACTOR, event(), "HR_TERMINATION", employee()))
                            .isInstanceOf(BusinessException.class);
                    s.setRollbackOnly();
                });
    }

    @Test
    void emptyTerminationAndPositionNoOpDoNotManufactureGrantRevisions() {
        db.update(
                "UPDATE sys_user_role_source SET status=0,revoked_at=NOW() WHERE user_id=?",
                id(20));
        db.update("DELETE FROM sys_user_role WHERE user_id=?", id(20));
        tx.executeWithoutResult(s -> signatures.sign(USER_GRANTS, id(20)));
        service().transfer(ACTOR, transfer());
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        service()
                .terminate(
                        ACTOR, new Termination("HR", event() + "-terminate", 3L, employee(), AT));
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE target_id=? AND"
                                        + " operation_type IN"
                                        + " ('HR_POSITION_REVOKE','HR_GRANTS_REVOKE_ALL')",
                                id(20)))
                .isZero();
        verifiable();
    }

    @Test
    void newEmployeeGetsSignedEmptyGrantsAndReceivingScopeRequiresReappointment() {
        var request =
                new Employee(
                        "HR",
                        event(),
                        1L,
                        "NEW" + b,
                        "测试员工",
                        String.format("138%08d", id(99)),
                        code(3),
                        Action.UPSERT,
                        EmploymentType.FORMAL,
                        AT);
        var result = service().employee(ACTOR, request.sgNo(), request);
        long user = Long.parseLong(result.get("userId").toString());
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", user))
                .isZero();
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", user)).isEqualTo(1);
        tx.executeWithoutResult(
                s -> {
                    signatures.verify(USER_IDENTITY, user);
                    signatures.verify(USER_GRANTS, user);
                });
        assertThat(scalar("SELECT status FROM sys_admin WHERE user_id=?", id(22))).isZero();
        assertThat(state(event())).isEqualTo("APPLIED");
    }

    void awaitRelease() {
        try {
            if (!release.await(15, TimeUnit.SECONDS))
                throw new AssertionError("fixture release timeout");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    void observeLockWait() {
        var root =
                new JdbcTemplate(
                        new org.springframework.jdbc.datasource.DriverManagerDataSource(
                                MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(20))
                .until(
                        () ->
                                root.queryForObject(
                                                "SELECT COUNT(*) FROM"
                                                        + " performance_schema.data_lock_waits",
                                                Long.class)
                                        > 0);
    }

    @Test
    void waitingManagementOperationRechecksQualificationAfterHrCommits() throws Exception {
        tx.executeWithoutResult(
                s -> assertThat(policy.requireAdministrator(id(20)).status()).isEqualTo(1));
        ready = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var change = pool.submit(() -> service().terminate(ACTOR, termination()));
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                var waiting =
                        pool.submit(
                                () -> {
                                    try {
                                        tx.executeWithoutResult(
                                                s -> policy.requireAdministrator(id(20)));
                                        return "ALLOWED";
                                    } catch (BusinessException e) {
                                        return e.errorCode().name();
                                    }
                                });
                observeLockWait();
                release.countDown();
                assertThat(change.get(15, TimeUnit.SECONDS).get("processingStatus"))
                        .isEqualTo("APPLIED");
                assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo("PERMISSION_DENIED");
            } finally {
                release.countDown();
            }
        }
        verifiable();
    }

    @Test
    void hrWaitingForManagementLocksUsesTheNewlyCommittedSourceSet() throws Exception {
        var locked = new CountDownLatch(1);
        release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var change =
                    pool.submit(
                            () ->
                                    tx.executeWithoutResult(
                                            s -> {
                                                policy.requireAdministrator(id(20));
                                                locked.countDown();
                                                awaitRelease();
                                                source(30, "MANUAL", "concurrent", null);
                                                db.update(
                                                        "UPDATE sys_user SET grant_revision=2 WHERE"
                                                                + " id=?",
                                                        id(20));
                                                signatures.sign(USER_GRANTS, id(20));
                                                signatures.sign(USER_IDENTITY, id(20));
                                            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var waiting = pool.submit(() -> service().terminate(ACTOR, termination()));
                observeLockWait();
                release.countDown();
                change.get(15, TimeUnit.SECONDS);
                assertThat(waiting.get(15, TimeUnit.SECONDS).get("processingStatus"))
                        .isEqualTo("APPLIED");
            } finally {
                release.countDown();
            }
        }
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " status=1",
                                id(20)))
                .isZero();
        assertThat(scalar("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", id(20)))
                .isEqualTo(8);
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(3);
        verifiable();
    }

    @Test
    void eventContentConflictAndStaleHrOrderCannotRepeatSuccessfulCleanup() {
        var service = service();
        service.transfer(ACTOR, transfer());
        var conflicting = new Transfer("HR", event(), 2L, employee(), code(2), code(4), AT);
        assertThatThrownBy(() -> service.transfer(ACTOR, conflicting))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(IDEMPOTENCY_CONFLICT));
        assertThatThrownBy(
                        () ->
                                service.terminate(
                                        ACTOR,
                                        new Termination(
                                                "HR", event() + "-stale", 2L, employee(), AT)))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(INVALID_HR_ORDER));
        assertThat(state(event())).isEqualTo("APPLIED");
        assertThat(state(event() + "-stale")).isEqualTo("REJECTED");
        assertThat(scalar("SELECT grant_revision FROM sys_user WHERE id=?", id(20))).isEqualTo(2);
        assertThat(scalar("SELECT status FROM sys_user WHERE id=?", id(20))).isEqualTo(1);
        verifiable();
    }

    @Test
    void terminationRemovesResidualListsEvenWhenOwnAppointmentWasAlreadyInactive() {
        db.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id(20));
        tx.executeWithoutResult(s -> signatures.sign(ADMIN, id(20)));
        service().terminate(ACTOR, termination());
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_admin_delegable_role WHERE"
                                        + " admin_user_id=? OR configured_by=?",
                                id(20),
                                id(20)))
                .isZero();
        assertThat(scalar("SELECT delegable_revision FROM sys_admin WHERE user_id=?", id(20)))
                .isEqualTo(2);
        assertThat(
                        db.queryForList(
                                "SELECT role_id FROM sys_admin_delegable_role WHERE"
                                        + " admin_user_id=?",
                                Long.class,
                                id(21)))
                .containsExactly(id(32));
        verifiable();
    }

    @Test
    void hrAuditRetainsSourceFactsAndServiceOriginWithVerifiableSeal() throws Exception {
        service().terminate(ACTOR, termination());
        String encoded =
                db.queryForObject(
                        "SELECT evidence FROM sys_operation_log WHERE external_event_id=? AND"
                                + " operation_type='HR_GRANTS_REVOKE_ALL'",
                        String.class,
                        event());
        var tree = json.readTree(encoded);
        assertThat(tree.at("/schema").asText()).isEqualTo("C_HR_GRANT_CHANGE_V1");
        assertThat(tree.at("/origin/sourceSystem").asText()).isEqualTo("HR");
        assertThat(tree.at("/before/sources").size()).isEqualTo(7);
        assertThat(tree.at("/after/roles").size()).isZero();
        assertThat(tree.at("/revokedSourceIds").size()).isEqualTo(7);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?"
                                        + " AND user_id IS NOT NULL",
                                event()))
                .isZero();
        assertThat(encoded).doesNotContain("13800000000", "测试员工", "password_hash", "ciphertext");
        var evidence =
                json.convertValue(
                        tree,
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, Object>>() {});
        @SuppressWarnings("unchecked")
        var seal = (Map<String, Object>) evidence.remove("seal");
        tx.executeWithoutResult(s -> assertThat(signatures.verifySeal(evidence, seal)).isTrue());
    }

    @Test
    void exhaustedGrantRevisionRefusesAllCleanupWithoutPartialWrites() {
        db.update("UPDATE sys_user SET grant_revision=9223372036854775807 WHERE id=?", id(20));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(USER_IDENTITY, id(20));
                    signatures.sign(USER_GRANTS, id(20));
                });
        var before = facts();
        assertThatThrownBy(() -> service().terminate(ACTOR, termination()))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(AUTH_STATE_UNAVAILABLE));
        assertThat(facts()).isEqualTo(before);
        assertThat(
                        scalar(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE external_event_id=?",
                                event()))
                .isZero();
        assertThat(state(event())).isEqualTo("PENDING_RECOVERY");
    }
}
