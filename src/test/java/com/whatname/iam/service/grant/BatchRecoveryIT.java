package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;
import static com.whatname.iam.service.permission.SignedDataType.*;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.config.*;
import com.whatname.iam.common.exception.*;
import com.whatname.iam.common.security.*;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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

import java.nio.file.Path;
import java.security.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** 真实的管理员、JWT、签名审计和隔离 MySQL；SQL 拦截器只注入持久化故障。 */
@SpringBootTest(
        classes = {com.whatname.iam.IamApplication.class, BatchRecoveryIT.Application.class},
        properties = {"spring.config.import=", "iam.grant-batch.recovery.enabled=false"})
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BatchRecoveryIT {
    @TestConfiguration
    static class Application {
        @Bean
        Failure failure() {
            return new Failure();
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
                            "/docker-entrypoint-initdb.d/01_schema.sql");

    static final KeyPair RSA = keys("RSA"), ED = keys("Ed25519");

    static KeyPair keys(String algorithm) {
        try {
            var generator = KeyPairGenerator.getInstance(algorithm);
            if (algorithm.equals("RSA")) generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encoded(byte[] value) {
        return Base64.getEncoder().encodeToString(value);
    }

    @DynamicPropertySource
    static void settings(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", MYSQL::getJdbcUrl);
        r.add("spring.datasource.username", MYSQL::getUsername);
        r.add("spring.datasource.password", MYSQL::getPassword);
        r.add("iam.security.jwt-private-key", () -> encoded(RSA.getPrivate().getEncoded()));
        r.add("iam.security.jwt-public-key", () -> encoded(RSA.getPublic().getEncoded()));
        r.add("iam.security.jwt-issuer", () -> "c-stage2");
        r.add("iam.security.jwt-audience", () -> "iam");
        r.add("iam.security.jwt-key-id", () -> "test-jwt");
        r.add("iam.security.data-signing.key-id", () -> "test-sign");
        r.add("iam.security.data-signing.private-key", () -> encoded(ED.getPrivate().getEncoded()));
        r.add(
                "iam.security.data-signing.public-keys.test-sign",
                () -> encoded(ED.getPublic().getEncoded()));
    }

    @org.aspectj.lang.annotation.Aspect
    static class Failure extends com.whatname.iam.support.RepositoryProbe {
        volatile String point;
        volatile String hit;
        volatile int remaining;
        volatile int hits;
        volatile Runnable afterRollback;
        volatile Runnable afterCommit;
        volatile CountDownLatch releaseDatabaseLock;
        volatile int databaseTimeouts;

        public Object intercept(Invocation call) throws Throwable {
            String statement = call.id();
            if (releaseDatabaseLock != null && statement.endsWith("AdminRepository.selectUsers")) {
                var connection = call.connection();
                try (var sql = connection.createStatement()) {
                    sql.execute("SET SESSION innodb_lock_wait_timeout=1");
                }
                try {
                    return call.proceed();
                } catch (Throwable error) {
                    for (Throwable cause = error; cause != null; cause = cause.getCause())
                        if (cause instanceof java.sql.SQLException sql
                                && sql.getErrorCode() == 1205) {
                            databaseTimeouts++;
                            releaseDatabaseLock.countDown();
                            releaseDatabaseLock = null;
                            break;
                        }
                    throw error;
                } finally {
                    try (var sql = connection.createStatement()) {
                        sql.execute("SET SESSION innodb_lock_wait_timeout=50");
                    }
                }
            }
            Object result = call.proceed();
            if (statement.endsWith("BatchRepository.applied")
                    || ("SIGN_DB".equals(point)
                            && statement.endsWith("SignatureRepository.save")
                            && "USER_GRANTS".equals(((Map<?, ?>) call.parameters()).get("type")))
                    || ("AUDIT_DB".equals(point) && statement.endsWith("AuditRepository.insert"))) {
                if (afterCommit != null) {
                    Runnable action = afterCommit;
                    afterCommit = null;
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .registerSynchronization(
                                    new org.springframework.transaction.support
                                            .TransactionSynchronization() {
                                        public void afterCommit() {
                                            action.run();
                                        }
                                    });
                }
                if (remaining > 0) {
                    remaining--;
                    hits++;
                    hit = point;
                    if (afterRollback != null) {
                        Runnable action = afterRollback;
                        afterRollback = null;
                        org.springframework.transaction.support.TransactionSynchronizationManager
                                .registerSynchronization(
                                        new org.springframework.transaction.support
                                                .TransactionSynchronization() {
                                            public void afterCompletion(int status) {
                                                if (status != STATUS_COMMITTED) action.run();
                                            }
                                        });
                    }
                    if (!"UNKNOWN".equals(point))
                        throw new java.sql.SQLTransactionRollbackException(
                                "fixture deadlock", "40001", 1213);
                    throw new IllegalStateException("fixture nonretryable failure");
                }
            }
            return result;
        }
    }

    @Autowired JdbcTemplate db;
    @Autowired GrantService grants;
    @Autowired AdminService admins;
    @Autowired AdminOrganizationLifecycle organization;
    @Autowired com.whatname.iam.service.department.DepartmentAccessService departmentAdapter;
    @Autowired com.whatname.iam.repository.department.OrganizationWriteRepository organizationMapper;
    @Autowired com.whatname.iam.repository.event.ExternalEventRepository eventMapper;
    @Autowired AuditService auditService;
    @Autowired DataSignatureService signatures;
    @Autowired PermissionService permissions;
    @Autowired JwtTokenService jwt;
    @Autowired PlatformTransactionManager manager;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired Failure failure;
    @Autowired BatchWorker worker;
    @Autowired org.springframework.context.ApplicationContext application;

    @org.springframework.test.context.bean.override.convention.TestBean(
            name = "grantBatchExecutor",
            methodName = "queue",
            enforceOverride = true)
    java.util.concurrent.Executor dispatch;

    static class TestQueue implements java.util.concurrent.Executor {
        final java.util.Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        boolean reject;

        public void execute(Runnable command) {
            if (reject) throw new RejectedExecutionException();
            tasks.add(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.poll()) != null) task.run();
        }
    }

    static java.util.concurrent.Executor queue() {
        return new TestQueue();
    }

    @Autowired BatchService batchService;
    @Autowired GrantOperations operations;
    @Autowired com.whatname.iam.repository.grant.BatchRepository batches;
    static final AtomicLong NEXT = new AtomicLong(1000);
    long b;
    String bearer;
    TransactionTemplate tx;
    TransactionTemplate orgTx;

    long id(int offset) {
        return b + offset;
    }

    String sid(int offset) {
        return Long.toString(id(offset));
    }

    @BeforeEach
    void seed() {
        b = NEXT.getAndAdd(10000);
        failure.point = null;
        failure.hit = null;
        failure.remaining = 0;
        failure.hits = 0;
        failure.afterRollback = null;
        failure.afterCommit = null;
        tx = new TransactionTemplate(manager);
        orgTx = new TransactionTemplate(manager);
        orgTx.setIsolationLevel(
                org.springframework.transaction.TransactionDefinition.ISOLATION_SERIALIZABLE);
        db.update(
                "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                        + " VALUES(?,0,?,'Fixture','/')",
                id(1),
                "D" + b);
        db.update(
                "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path)"
                        + " VALUES(?,?,?,'Fixture','/')",
                id(2),
                id(1),
                "L" + b);
        for (int u : List.of(10, 11, 20))
            db.update(
                    "INSERT INTO"
                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                        + " VALUES(?,?, 'fixture',?,'fixture',?,3,1,1)",
                    id(u),
                    "U" + id(u),
                    String.format("138%08d", id(u)),
                    id(u == 11 ? 2 : 1));
        db.update(
                "INSERT INTO sys_admin(user_id,admin_level,dept_id,status)"
                        + " VALUES(?,1,?,1),(?,2,?,1)",
                id(10),
                id(1),
                id(11),
                id(2));
        db.update(
                "INSERT INTO sys_role(id,role_code,role_name,role_type)"
                        + " VALUES(?,?,'Business',1),(?,?,'Manager',2)",
                id(30),
                "R" + b,
                id(31),
                "M" + b);
        db.update(
                "INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                        + " VALUES(?,?,?)",
                id(11),
                id(30),
                id(10));
        db.update(
                "INSERT INTO sys_application(id,app_code,app_name) VALUES(?,?,'Fixture')",
                id(40),
                "C" + b);
        int n = 50;
        for (String code :
                List.of(
                        "IAM_GRANTS_WRITE",
                        "IAM_GRANTS_VIEW",
                        "IAM_USERS_VIEW",
                        "IAM_ADMINS_WRITE",
                        "IAM_ADMINS_VIEW",
                        "IAM_GRANTS_DELEGATE",
                        "BUSINESS_VIEW")) {
            db.update(
                    "INSERT INTO sys_menu(id,app_id,parent_id,menu_code,menu_name,menu_type)"
                            + " VALUES(?,?,0,?,'Fixture',2)",
                    id(n),
                    id(40),
                    code);
            db.update(
                    "INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)",
                    id(n == 56 ? 30 : 31),
                    id(n++));
        }
        db.update(
                "INSERT INTO"
                    + " sys_api(id,app_id,api_code,api_name,api_path,canonical_path,http_method)"
                    + " VALUES(?,?,'READ','Fixture','/records/{id}','/records/{}','GET')",
                id(60),
                id(40));
        db.update("INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?)", id(56), id(60));
        for (int u : List.of(10, 11)) {
            db.update(
                    "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                    id(u),
                    id(31),
                    id(10));
            db.update(
                    "INSERT INTO"
                        + " sys_user_role_source(user_id,role_id,source_type,source_ref,granted_by)"
                        + " VALUES(?,?,'MANUAL','bootstrap',?)",
                    id(u),
                    id(31),
                    id(10));
        }
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(ORGANIZATION, 0);
                    for (long d : db.queryForList("SELECT id FROM sys_dept", Long.class))
                        signatures.sign(DEPARTMENT, d);
                    for (long a : db.queryForList("SELECT user_id FROM sys_admin", Long.class)) {
                        signatures.sign(ADMIN, a);
                        signatures.sign(ADMIN_DELEGABLE_ROLES, a);
                    }
                    for (long r : db.queryForList("SELECT id FROM sys_role", Long.class))
                        signatures.sign(ROLE_MENUS, r);
                    for (int u : List.of(10, 11, 20)) signatures.sign(USER_IDENTITY, id(u));
                    for (int u : List.of(10, 11, 20)) signatures.sign(USER_GRANTS, id(u));
                    signatures.sign(RESOURCE_APPLICATION, id(40));
                    signatures.sign(RESOURCE_API, id(60));
                    for (int m = 50; m <= 56; m++) {
                        signatures.sign(RESOURCE_MENU, id(m));
                        signatures.sign(MENU_APIS, id(m));
                    }
                });
        org.springframework.test.util.ReflectionTestUtils.setField(
                (Object)
                        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
                                permissions),
                "managementApplication",
                "C" + b);
        bearer = "Bearer " + jwt.issue(id(10), 1);
        bind(bearer);
    }

    static void bind(String authorization) {
        var request = new MockHttpServletRequest();
        if (authorization != null) request.addHeader("Authorization", authorization);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    }

    @AfterEach
    void cleanup() {
        failure.point = null;
        ((TestQueue) dispatch).runAll();
        RequestContextHolder.resetRequestAttributes();
    }

    GrantRequest request() {
        return new GrantRequest(
                sid(20), sid(30), "MANUAL", "C2-WORK", null, "fixture reason", "v1");
    }

    long count(String table) {
        return db.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE user_id=?", Long.class, id(20));
    }

    long revision() {
        return db.queryForObject(
                "SELECT grant_revision FROM sys_user WHERE id=?", Long.class, id(20));
    }

    long audits() {
        return db.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE target_id=? AND operation_type IN"
                        + " ('USER_ROLE_GRANT','USER_ROLE_REVOKE')",
                Long.class,
                id(20));
    }

    void unchanged() {
        assertThat(count("sys_user_role_source")).isZero();
        assertThat(count("sys_user_role")).isZero();
        assertThat(revision()).isEqualTo(1);
        assertThat(audits()).isZero();
    }

    boolean allowed() {
        return (boolean)
                permissions
                        .check("Bearer " + jwt.issue(id(20), 1), id(40), "GET", "/records/{id}")
                        .get("allowed");
    }

    long submit(int... users) {
        var targets =
                Arrays.stream(users)
                        .mapToObj(
                                u ->
                                        new com.whatname.iam.dto.grant.BatchDtos.Target(
                                                sid(u), "MANUAL", "BATCH", null, null))
                        .toList();
        var submission =
                batchService.submit(
                        "K" + b,
                        new com.whatname.iam.dto.grant.BatchDtos.Request(
                                "GRANT", sid(30), targets, "fixture", false));
        return Long.parseLong(
                ((com.whatname.iam.dto.grant.BatchDtos.Accepted) submission.data()).batchId());
    }

    int attempts(long batch) {
        return db.queryForObject(
                "SELECT attempts FROM sys_grant_batch_item WHERE batch_id=? ORDER BY user_id DESC"
                        + " LIMIT 1",
                Integer.class,
                batch);
    }

    String status(long batch) {
        return db.queryForObject(
                "SELECT processing_status FROM sys_grant_batch WHERE id=?", String.class, batch);
    }

    void fault(String kind, int times) {
        failure.point = kind;
        failure.remaining = times;
    }

    void scan() {
        Object recovery = application.getBean("batchRecoveryService");
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(recovery, "scan");
    }

    @Test
    void transientFailureAfterAppliedRollsBackThenRetriesToSingleSealedSuccess() {
        long batch = submit(20);
        fault("TRANSIENT", 2);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("COMPLETED");
        assertThat(attempts(batch)).isEqualTo(3);
        assertThat(failure.hits).isEqualTo(2);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
        assertThat(count("sys_user_role_source")).isEqualTo(1);
        tx.executeWithoutResult(
                s -> {
                    signatures.verify(USER_IDENTITY, id(20));
                    signatures.verify(USER_GRANTS, id(20));
                });
        worker.run(batch);
        assertThat(attempts(batch)).isEqualTo(3);
        assertThat(audits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIGN_DB", "AUDIT_DB"})
    void transientDatabaseFailureInsideDStillRetriesWithoutTreatingEvery503AsTransient(
            String point) {
        long batch = submit(20);
        fault(point, 1);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("COMPLETED");
        assertThat(attempts(batch)).isEqualTo(2);
        assertThat(failure.hits).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
    }

    @Test
    void transientExhaustionIsTerminalAfterInitialAndThreeRetries() {
        long batch = submit(20);
        fault("TRANSIENT", 10);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("FAILED");
        assertThat(attempts(batch)).isEqualTo(4);
        assertThat(failure.hits).isEqualTo(4);
        unchanged();
        worker.run(batch);
        assertThat(failure.hits).isEqualTo(4);
    }

    @Test
    void actualMysqlLockTimeoutRollsBackAndRetriesAfterTheBlockerCommits() throws Exception {
        long batch = submit(20);
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        failure.releaseDatabaseLock = release;
        failure.databaseTimeouts = 0;
        try (var pool = Executors.newSingleThreadExecutor()) {
            var blocker =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(manager)
                                            .executeWithoutResult(
                                                    s -> {
                                                        db.queryForObject(
                                                                "SELECT id FROM sys_user WHERE id=?"
                                                                        + " FOR UPDATE",
                                                                Long.class,
                                                                id(20));
                                                        locked.countDown();
                                                        try {
                                                            assertThat(
                                                                            release.await(
                                                                                    15,
                                                                                    TimeUnit
                                                                                            .SECONDS))
                                                                    .isTrue();
                                                        } catch (InterruptedException e) {
                                                            throw new AssertionError(e);
                                                        }
                                                    }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                worker.run(batch);
            } finally {
                release.countDown();
                failure.releaseDatabaseLock = null;
            }
            blocker.get(10, TimeUnit.SECONDS);
        }
        assertThat(failure.databaseTimeouts).isEqualTo(1);
        assertThat(attempts(batch)).isEqualTo(2);
        assertThat(status(batch)).isEqualTo("COMPLETED");
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
        System.out.println("C2_05_MYSQL_1205_COUNT=1 ATTEMPTS=2");
    }

    @Test
    void nonretryablePersistenceFailureBecomesFailedWithoutRetry() {
        long batch = submit(20);
        fault("UNKNOWN", 10);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("FAILED");
        assertThat(attempts(batch)).isEqualTo(1);
        assertThat(failure.hits).isEqualTo(1);
        unchanged();
    }

    @Test
    void lostCommitConfirmationChecksAppliedInsteadOfRepeatingBusiness() {
        long batch = submit(20);
        failure.afterCommit =
                () -> {
                    throw new org.springframework.dao.DataAccessResourceFailureException(
                            "fixture lost commit confirmation",
                            new java.sql.SQLTransientConnectionException("fixture", "08006"));
                };
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("COMPLETED");
        assertThat(attempts(batch)).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
    }

    @Test
    void fourthReservedAttemptInterruptedBeforeExecutionCannotRunAFifthTime() {
        long batch = submit(20);
        db.update("UPDATE sys_grant_batch_item SET attempts=4 WHERE batch_id=?", batch);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("FAILED");
        assertThat(attempts(batch)).isEqualTo(4);
        unchanged();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "APPOINTMENT",
                "FUNCTION",
                "DELEGATION",
                "TARGET",
                "ACCOUNT",
                "ROLE",
                "RESOURCE"
            })
    void everyRetryRechecksCurrentSignedAuthority(String change) {
        bind("Bearer " + jwt.issue(id(11), 1));
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
        tx.executeWithoutResult(s -> signatures.sign(USER_IDENTITY, id(20)));
        long batch = submit(20);
        fault("TRANSIENT", 1);
        failure.afterRollback = () -> changeAuthority(change);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("FAILED");
        assertThat(attempts(batch)).isEqualTo(2);
        assertThat(failure.hits).isEqualTo(1);
        unchanged();
    }

    void changeAuthority(String change) {
        changeAuthority(change, 20);
    }

    void changeAuthority(String change, int target) {
        var changeTx = new TransactionTemplate(manager);
        changeTx.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        changeTx.executeWithoutResult(
                s -> {
                    switch (change) {
                        case "APPOINTMENT" -> {
                            db.update("UPDATE sys_admin SET status=0 WHERE user_id=?", id(11));
                            signatures.sign(ADMIN, id(11));
                        }
                        case "FUNCTION" -> {
                            db.update(
                                    "DELETE FROM sys_role_permission WHERE role_id=? AND menu_id=?",
                                    id(31),
                                    id(50));
                            signatures.sign(ROLE_MENUS, id(31));
                        }
                        case "DELEGATION" -> {
                            db.update(
                                    "DELETE FROM sys_admin_delegable_role WHERE admin_user_id=?",
                                    id(11));
                            signatures.sign(ADMIN_DELEGABLE_ROLES, id(11));
                        }
                        case "TARGET" -> {
                            db.update(
                                    "UPDATE sys_user SET dept_id=? WHERE id=?", id(1), id(target));
                            signatures.sign(USER_IDENTITY, id(target));
                        }
                        case "ACCOUNT" -> {
                            db.update("UPDATE sys_user SET status=2 WHERE id=?", id(11));
                            signatures.sign(USER_IDENTITY, id(11));
                        }
                        case "ROLE" -> {
                            db.update("UPDATE sys_role SET status=0 WHERE id=?", id(30));
                            signatures.sign(ROLE_MENUS, id(30));
                        }
                        case "RESOURCE" -> {
                            db.update("UPDATE sys_menu SET status=0 WHERE id=?", id(50));
                            signatures.sign(RESOURCE_MENU, id(50));
                        }
                        default -> throw new AssertionError(change);
                    }
                });
    }

    @Test
    void concurrentWorkersCannotConsumeOrApplyTheSameAttemptTwice() throws Exception {
        long batch = submit(20, 11);
        var committed = new CountDownLatch(1);
        var proceed = new CountDownLatch(1);
        failure.afterCommit =
                () -> {
                    committed.countDown();
                    try {
                        assertThat(proceed.await(20, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        throw new AssertionError(e);
                    }
                };
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> worker.run(batch));
            try {
                assertThat(committed.await(20, TimeUnit.SECONDS)).isTrue();
                assertThat(batchService.result(Long.toString(batch), 1, 20).processingStatus())
                        .isEqualTo("PROCESSING");
                pool.submit(() -> worker.run(batch)).get(5, TimeUnit.SECONDS);
            } finally {
                proceed.countDown();
            }
            first.get(20, TimeUnit.SECONDS);
        }
        assertThat(status(batch)).isEqualTo("COMPLETED");
        assertThat(attempts(batch)).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
    }

    @Test
    void recoveryFindsRejectedDispatchAndSchedulesOnlyOnceLocally() {
        var queue = (TestQueue) dispatch;
        queue.reject = true;
        long batch;
        try {
            batch = submit(20);
        } finally {
            queue.reject = false;
        }
        assertThat(status(batch)).isEqualTo("PENDING");
        scan();
        scan();
        assertThat(queue.tasks).hasSize(1);
        queue.runAll();
        assertThat(status(batch)).isEqualTo("COMPLETED");
        assertThat(attempts(batch)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "APPOINTMENT",
                "FUNCTION",
                "DELEGATION",
                "TARGET",
                "ACCOUNT",
                "ROLE",
                "RESOURCE"
            })
    void authorityChangesBetweenItemsKeepCommittedSuccessAndRejectLaterItem(String change) {
        bind("Bearer " + jwt.issue(id(11), 1));
        db.update("UPDATE sys_user SET dept_id=? WHERE id=?", id(2), id(20));
        db.update(
                "INSERT INTO"
                    + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                    + " VALUES(?,?,'fixture',?,'fixture',?,3,1,1)",
                id(21),
                "U" + id(21),
                String.format("138%08d", id(21)),
                id(2));
        tx.executeWithoutResult(
                s -> {
                    signatures.sign(USER_IDENTITY, id(20));
                    signatures.sign(USER_IDENTITY, id(21));
                    signatures.sign(USER_GRANTS, id(21));
                });
        long batch = submit(20, 21);
        failure.afterCommit = () -> changeAuthority(change, 21);
        worker.run(batch);
        assertThat(status(batch)).isEqualTo("PARTIAL_SUCCESS");
        assertThat(attempts(batch)).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?",
                                Integer.class,
                                id(21)))
                .isZero();
        assertThat(
                        db.queryForObject(
                                "SELECT grant_revision FROM sys_user WHERE id=?",
                                Long.class,
                                id(21)))
                .isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE target_id=? AND"
                                        + " operation_type='USER_ROLE_GRANT'",
                                Integer.class,
                                id(21)))
                .isZero();
    }

    @Test
    void boundedScanAdvancesPastOneHundredTasksThenWraps() {
        for (int n = 0; n < 101; n++) {
            batches.insertBatch(
                    id(10), "SCAN-" + b + "-" + n, "13800000000", "GRANT", id(30), "fixture", 1);
            var batch = batches.byKey(id(10), "SCAN-" + b + "-" + n);
            batches.insertItems(
                    List.of(
                            new com.whatname.iam.entity.grant.BatchData.Item(
                                    null,
                                    batch.id(),
                                    id(20),
                                    "MANUAL",
                                    "SCAN",
                                    null,
                                    null,
                                    "PENDING",
                                    null,
                                    null,
                                    0)));
        }
        // Reset the cursor to a known scan cycle without depending on earlier test order.
        Object recovery = application.getBean("batchRecoveryService");
        org.springframework.test.util.ReflectionTestUtils.setField(recovery, "cursor", 0L);
        var queue = (TestQueue) dispatch;
        scan();
        scan();
        assertThat(queue.tasks).hasSize(100);
        queue.runAll();
        scan();
        scan();
        queue.runAll();
        scan();
        scan();
        queue.runAll();
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_grant_batch WHERE actor_user_id=? AND"
                                        + " processing_status='COMPLETED'",
                                Integer.class,
                                id(10)))
                .isEqualTo(101);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPLIED", "RESERVE", "ROLLBACK"})
    void abruptProcessExitResumesFromPersistedItemsAndBudgetInFreshJvm(String point)
            throws Exception {
        long batch = submit(20, 11);
        if ("RESERVE".equals(point))
            db.update(
                    "UPDATE sys_grant_batch_item SET attempts=3 WHERE batch_id=? AND user_id=?",
                    batch,
                    id(11));
        if ("ROLLBACK".equals(point))
            db.update(
                    "UPDATE sys_grant_batch_item SET attempts=2 WHERE batch_id=? AND user_id=?",
                    batch,
                    id(11));
        runChild(batch, point, 23);
        assertThat(status(batch)).isEqualTo("PENDING");
        int before =
                db.queryForObject(
                        "SELECT attempts FROM sys_grant_batch_item WHERE batch_id=? AND user_id=?",
                        Integer.class,
                        batch,
                        id(11));
        assertThat(before)
                .isEqualTo("RESERVE".equals(point) ? 4 : "ROLLBACK".equals(point) ? 3 : 1);
        runChild(batch, "NONE", 0);
        assertThat(status(batch))
                .isEqualTo("RESERVE".equals(point) ? "PARTIAL_SUCCESS" : "COMPLETED");
        assertThat(attempts(batch)).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
        assertThat(
                        db.queryForObject(
                                "SELECT attempts FROM sys_grant_batch_item WHERE batch_id=? AND"
                                        + " user_id=?",
                                Integer.class,
                                batch,
                                id(11)))
                .isEqualTo("APPLIED".equals(point) ? 1 : 4);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " role_id=?",
                                Integer.class,
                                id(11),
                                id(30)))
                .isEqualTo("RESERVE".equals(point) ? 0 : 1);
    }

    void runChild(long batch, String point, int exit) throws Exception {
        var properties = new LinkedHashMap<String, Object>();
        properties.put("spring.datasource.url", MYSQL.getJdbcUrl());
        properties.put("spring.datasource.username", MYSQL.getUsername());
        properties.put("spring.datasource.password", MYSQL.getPassword());
        properties.put("iam.security.jwt-public-key", encoded(RSA.getPublic().getEncoded()));
        properties.put("iam.security.jwt-private-key", encoded(RSA.getPrivate().getEncoded()));
        properties.put("iam.security.jwt-key-id", "test-jwt");
        properties.put("iam.security.jwt-issuer", "c-stage2");
        properties.put("iam.security.jwt-audience", "iam");
        properties.put("iam.security.data-signing.key-id", "test-sign");
        properties.put(
                "iam.security.data-signing.private-key", encoded(ED.getPrivate().getEncoded()));
        properties.put(
                "iam.security.data-signing.public-keys.test-sign",
                encoded(ED.getPublic().getEncoded()));
        properties.put("iam.permission.management-app-code", "C" + b);
        properties.put("iam.grant-batch.recovery.enabled", true);
        properties.put("iam.grant-batch.recovery.delay-ms", 200);
        var output = Path.of("target/c2-05-evidence/child-" + batch + "-" + point + ".log");
        java.nio.file.Files.createDirectories(output.getParent());
        var process =
                new ProcessBuilder(
                                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                                "-cp",
                                System.getProperty(
                                        "surefire.test.class.path",
                                        System.getProperty("java.class.path")),
                                BatchRecoveryProcess.class.getName(),
                                Long.toString(batch),
                                point)
                        .redirectErrorStream(true)
                        .redirectOutput(output.toFile())
                        .start();
        try {
            json.writeValue(process.getOutputStream(), properties);
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("child timed out; see %s", output)
                    .isTrue();
            assertThat(process.exitValue()).as("child exit; see %s", output).isEqualTo(exit);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Order(1)
    @Tag("batch-scale")
    void realSignedBatchOf2001TargetsCompletesAndDuplicateRunDoesNotChangeFacts() {
        List<Object[]> users = new ArrayList<>();
        int[] offsets = new int[2001];
        for (int n = 0; n < 2001; n++) {
            offsets[n] = 100 + n;
            long user = id(offsets[n]);
            users.add(new Object[] {user, "SCALE" + user, String.format("138%08d", user), id(1)});
        }
        db.batchUpdate(
                "INSERT INTO"
                    + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status,password_initialized)"
                    + " VALUES(?,?,'fixture',?,'fixture',?,3,1,1)",
                users);
        tx.executeWithoutResult(
                s -> {
                    for (int u : offsets) {
                        signatures.sign(USER_IDENTITY, id(u));
                        signatures.sign(USER_GRANTS, id(u));
                    }
                });
        long started = System.nanoTime();
        long batch = submit(offsets);
        RequestContextHolder.resetRequestAttributes();
        worker.run(batch);
        bind(bearer);
        var result = batchService.result(Long.toString(batch), 5, 500);
        assertThat(result.processingStatus()).isEqualTo("COMPLETED");
        assertThat(result.total()).isEqualTo(2001);
        assertThat(result.succeeded()).isEqualTo(2001);
        assertThat(result.failed()).isZero();
        assertThat(result.pending()).isZero();
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().userId()).isEqualTo(sid(2100));
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(attempts) FROM sys_grant_batch_item WHERE batch_id=?",
                                Long.class,
                                batch))
                .isEqualTo(2001);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_user WHERE id BETWEEN ? AND ? AND"
                                        + " grant_revision=2",
                                Integer.class,
                                id(100),
                                id(2100)))
                .isEqualTo(2001);
        assertThat(
                        db.queryForObject(
                                "SELECT COUNT(*) FROM sys_operation_log WHERE target_id BETWEEN ?"
                                        + " AND ? AND operation_type='USER_ROLE_GRANT'",
                                Integer.class,
                                id(100),
                                id(2100)))
                .isEqualTo(2001);
        worker.run(batch);
        assertThat(
                        db.queryForObject(
                                "SELECT SUM(attempts) FROM sys_grant_batch_item WHERE batch_id=?",
                                Long.class,
                                batch))
                .isEqualTo(2001);
        tx.executeWithoutResult(
                s -> {
                    signatures.verify(USER_IDENTITY, id(2100));
                    signatures.verify(USER_GRANTS, id(2100));
                });
        System.out.printf(
                "C2_05_REAL_D_2001_ELAPSED_MS=%d ATTEMPTS=2001 SUCCEEDED=2001%n",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }
}
