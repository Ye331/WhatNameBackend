package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;

import static org.assertj.core.api.Assertions.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.admin.AdminDtos;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.admin.*;
import com.whatname.iam.support.Fakes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@SpringBootTest(properties = "spring.config.import=")
@Import(GrantIT.Integrations.class)
@Testcontainers
class GrantIT {
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
                            MountableFile.forHostPath(Path.of("db", "schema.sql").toAbsolutePath()),
                            "/docker-entrypoint-initdb.d/01_schema.sql");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired GrantService service;
    @Autowired BatchService batchService;
    @Autowired AdminService admins;
    @Autowired AdminPolicyService policy;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestState state;
    @Autowired PlatformTransactionManager transactions;
    private static final AtomicLong NEXT = new AtomicLong(1000);
    private long base;

    private long id(long offset) {
        return base + offset;
    }

    private String sid(long offset) {
        return Long.toString(id(offset));
    }

    @Test
    void synchronousBatchCommitsValidUserAndReportsUnknownUserWithoutCreatingTask() {
        var request = new com.whatname.iam.dto.grant.BatchDtos.Request("GRANT", sid(100),
                List.of(new com.whatname.iam.dto.grant.BatchDtos.Target(sid(6), "MANUAL", "sync-demo", null, null),
                        new com.whatname.iam.dto.grant.BatchDtos.Target(sid(999), "MANUAL", "sync-demo", null, null)),
                "class demo", false);
        long tasks = jdbc.queryForObject("SELECT COUNT(*) FROM sys_grant_batch", Long.class);
        var result = batchService.submit(null, request);
        assertThat(result.processingStatus()).isEqualTo("PARTIAL_SUCCESS");
        assertThat(result.succeeded()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?", Integer.class, id(6), id(100))).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_grant_batch", Long.class)).isEqualTo(tasks);
        var replay = batchService.submit(null, request);
        assertThat(replay.succeeded()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND role_id=? AND source_ref='sync-demo'", Integer.class, id(6), id(100))).isEqualTo(1);
    }

    @BeforeEach
    void seed() {
        base = NEXT.getAndAdd(1000);
        state.actor.set(id(1));
        state.failure = null;
        var data = new AdminTestFixture();
        for (var department : data.departments.values()) {
            List<String> path = new ArrayList<>();
            for (long current = department.id();
                    current != 0;
                    current = data.departments.get(current).parentId()) {
                path.addFirst(Long.toString(id(current)));
            }
            jdbc.update(
                    "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path,status)"
                            + " VALUES(?,?,?,?,?,?)",
                    id(department.id()),
                    department.parentId() == 0 ? 0 : id(department.parentId()),
                    "D" + id(department.id()),
                    "grant test department",
                    "/" + String.join("/", path) + "/",
                    department.status());
        }
        for (var user : data.users.values()) {
            jdbc.update(
                    "INSERT INTO"
                        + " sys_user(id,username,real_name,phone,password_hash,dept_id,source,status)"
                        + " VALUES(?,?,?,?,?,?,?,?)",
                    id(user.id()),
                    "test_grant_user_" + id(user.id()),
                    "test-only-ciphertext",
                    String.format("138%08d", id(user.id())),
                    "test-only-unusable-hash",
                    id(user.deptId()),
                    3,
                    user.status());
        }
        for (var role : data.roles.values()) {
            jdbc.update(
                    "INSERT INTO sys_role(id,role_code,role_name,role_type,status)"
                            + " VALUES(?,?,?,?,?)",
                    id(role.id()),
                    "R" + id(role.id()),
                    "grant test role",
                    role.roleType(),
                    role.status());
        }
        for (var admin : data.appointments.values()) {
            jdbc.update(
                    "INSERT INTO"
                        + " sys_admin(user_id,admin_level,dept_id,appointed_by,status,delegable_revision)"
                        + " VALUES(?,?,?,?,?,?)",
                    id(admin.userId()),
                    admin.adminLevel(),
                    id(admin.deptId()),
                    admin.appointedBy() == null ? null : id(admin.appointedBy()),
                    admin.status(),
                    admin.delegableRevision());
        }
        for (var delegation : data.delegations) {
            jdbc.update(
                    "INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by)"
                            + " VALUES(?,?,?)",
                    id(delegation.adminUserId()),
                    id(delegation.roleId()),
                    id(delegation.configuredBy()));
        }
    }

    private GrantRequest request(String ref, String revision) {
        return new GrantRequest(
                sid(6), sid(100), "MANUAL", ref, null, "grant integration test", revision);
    }

    private RevokeRequest revoke(String source, String revision) {
        return new RevokeRequest(sid(6), sid(100), source, "revoke integration test", revision);
    }

    private long revision() {
        return jdbc.queryForObject(
                "SELECT grant_revision FROM sys_user WHERE id=?", Long.class, id(6));
    }

    private int relations() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role WHERE user_id=?", Integer.class, id(6));
    }

    private int sources() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=?", Integer.class, id(6));
    }

    private int audits() {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM sys_operation_log WHERE target_type='USER_GRANT' AND"
                        + " target_id=?",
                Integer.class,
                id(6));
    }

    private String signatureRevision() {
        return jdbc.queryForObject(
                "SELECT key_id FROM sys_data_signature WHERE data_type='TEST_GRANT' AND data_id=?",
                String.class,
                id(6));
    }

    @Test
    void realSqlRoundTripRetainsHistoryAndSignsEmptyMembership() {
        assertThat(service.roles(sid(6)).grantRevision()).isEqualTo("v1");
        var first = service.grant(request("WORK", "v1"));
        var second =
                service.grant(
                        new GrantRequest(
                                sid(6), sid(100), "DEPT_POSITION", "POST", sid(21), "test", "v2"));
        assertThat(first.grantSourceId()).isNotEqualTo(second.grantSourceId());
        assertThat(service.roles(sid(6)).roles()).containsExactly(new RoleView(sid(100), true, 2));
        assertThat(service.sources(sid(6), sid(100)).sources())
                .extracting(SourceView::sourceDeptId)
                .containsExactlyInAnyOrder(sid(21), null);
        assertThat(service.revoke(revoke(first.grantSourceId(), "v3")).remainingActiveSources())
                .isEqualTo(1);
        assertThat(relations()).isEqualTo(1);
        assertThat(service.revoke(revoke(second.grantSourceId(), "v4")).roleEffective()).isFalse();
        assertThat(relations()).isZero();
        assertThat(sources()).isEqualTo(2);
        assertThat(revision()).isEqualTo(5);
        assertThat(service.sources(sid(6), sid(100)).sources()).allMatch(s -> !s.active());
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_user_role_source WHERE user_id=? AND"
                                        + " revoked_by=? AND revoked_at IS NOT NULL",
                                Integer.class,
                                id(6),
                                id(1)))
                .isEqualTo(2);
        assertThat(signatureRevision()).isEqualTo("test-v5");
        assertThat(audits()).isEqualTo(4);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT evidence->>'$.remaining' FROM sys_operation_log WHERE"
                                    + " target_type='USER_GRANT' AND target_id=? ORDER BY id DESC"
                                    + " LIMIT 1",
                                String.class,
                                id(6)))
                .isEqualTo("0");
    }

    @Test
    void stableKeyUsesMysqlCollationAndRetriesNeverResurrectHistory() {
        var first = service.grant(request("WORK", "v1"));
        assertThatThrownBy(() -> service.grant(request("WORK", "v1")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(VERSION_CONFLICT));
        assertThat(service.grant(request("work", "v2")).grantSourceId())
                .isEqualTo(first.grantSourceId());
        assertThat(sources()).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
        service.revoke(revoke(first.grantSourceId(), "v2"));
        service.revoke(revoke(first.grantSourceId(), "v3"));
        assertThat(revision()).isEqualTo(3);
        assertThat(audits()).isEqualTo(2);
        assertThatThrownBy(() -> service.grant(request("WORK", "v3")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(DUPLICATE_RESOURCE));
        assertThat(service.grant(request("NEW-WORK", "v3")).grantSourceId())
                .isNotEqualTo(first.grantSourceId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIGN", "AUDIT"})
    void failedGrantRollsBackFactsRevisionSignatureAndAudit(String failure) {
        state.failure = failure;
        assertThatThrownBy(() -> service.grant(request("FAIL", "v1")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e ->
                                assertThat(e.errorCode())
                                        .isEqualTo(
                                                failure.equals("SIGN")
                                                        ? INTEGRITY_FAILURE
                                                        : AUDIT_UNAVAILABLE));
        assertThat(sources()).isZero();
        assertThat(relations()).isZero();
        assertThat(revision()).isEqualTo(1);
        assertThat(audits()).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT COUNT(*) FROM sys_data_signature WHERE"
                                        + " data_type='TEST_GRANT' AND data_id=?",
                                Integer.class,
                                id(6)))
                .isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"SIGN", "AUDIT"})
    void failedLastRevokeRollsBackDeletedRelationAndExistingSignatureUpdate(String failure) {
        var first = service.grant(request("WORK", "v1"));
        state.failure = failure;
        assertThatThrownBy(() -> service.revoke(revoke(first.grantSourceId(), "v2")))
                .isInstanceOf(BusinessException.class);
        assertThat(relations()).isEqualTo(1);
        assertThat(revision()).isEqualTo(2);
        assertThat(audits()).isEqualTo(1);
        assertThat(signatureRevision()).isEqualTo("test-v2");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM sys_user_role_source WHERE id=?",
                                Integer.class,
                                first.grantSourceId()))
                .isEqualTo(1);
    }

    @Test
    void manualRevokeLeavesSeededOaSource() {
        var first = service.grant(request("WORK", "v1"));
        jdbc.update(
                "INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,status)"
                        + " VALUES(?,?,'OA_APPROVAL','OA-TEST',1)",
                id(6),
                id(100));
        state.actor.set(id(2));
        var result = service.revoke(revoke(first.grantSourceId(), "v2"));
        assertThat(result.remainingActiveSources()).isEqualTo(1);
        assertThat(result.roleEffective()).isTrue();
        assertThat(service.roles(sid(6)).roles()).containsExactly(new RoleView(sid(100), true, 1));
    }

    @Test
    void revokingOriginalGrantorDoesNotInvalidateExistingUserRole() {
        state.actor.set(id(2));
        service.grant(request("WORK", "v1"));
        state.actor.set(id(1));
        admins.revoke(sid(2), new AdminDtos.RevokeRequest("test revoke original grantor"));
        assertThat(service.roles(sid(6)).roles()).containsExactly(new RoleView(sid(100), true, 1));
        assertThat(service.sources(sid(6), sid(100)).sources()).allMatch(SourceView::active);
        assertThat(revision()).isEqualTo(2);
    }

    @Test
    void unexplainedExistingRelationshipIsRejectedWithoutRepair() {
        jdbc.update(
                "INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)",
                id(6),
                id(100),
                id(1));
        assertThatThrownBy(() -> service.grant(request("WORK", "v1")))
                .isInstanceOfSatisfying(
                        BusinessException.class,
                        e -> assertThat(e.errorCode()).isEqualTo(INTEGRITY_FAILURE));
        assertThat(relations()).isEqualTo(1);
        assertThat(sources()).isZero();
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void concurrentSameVersionHasExactlyOneWinnerAndNoDuplicateRelationship() throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first =
                    pool.submit(
                            () ->
                                    asActor(
                                            1,
                                            () -> {
                                                await(start);
                                                service.grant(request("FIRST", "v1"));
                                            }));
            var second =
                    pool.submit(
                            () ->
                                    asActor(
                                            1,
                                            () -> {
                                                await(start);
                                                service.grant(request("SECOND", "v1"));
                                            }));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        }
        assertThat(revision()).isEqualTo(2);
        assertThat(sources()).isEqualTo(1);
        assertThat(relations()).isEqualTo(1);
        assertThat(audits()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"APPOINTMENT", "DELEGATION"})
    void waitingGrantRechecksCommittedAuthorityChanges(String change) throws Exception {
        var changed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var started = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var mutation =
                    pool.submit(
                            () ->
                                    asActor(
                                            1,
                                            () ->
                                                    new TransactionTemplate(transactions)
                                                            .executeWithoutResult(
                                                                    tx -> {
                                                                        if (change.equals(
                                                                                "APPOINTMENT"))
                                                                            admins.revoke(
                                                                                    sid(2),
                                                                                    new AdminDtos
                                                                                            .RevokeRequest(
                                                                                            "concurrent"
                                                                                                + " revoke"));
                                                                        else
                                                                            admins
                                                                                    .replaceDelegableRoles(
                                                                                            sid(2),
                                                                                            new AdminDtos
                                                                                                    .DelegationRequest(
                                                                                                    List
                                                                                                            .of(),
                                                                                                    "v1",
                                                                                                    "concurrent"
                                                                                                        + " shrink"));
                                                                        changed.countDown();
                                                                        await(release);
                                                                    })));
            try {
                assertThat(changed.await(10, TimeUnit.SECONDS)).isTrue();
                var pending =
                        pool.submit(
                                () ->
                                        asActor(
                                                2,
                                                () -> {
                                                    started.countDown();
                                                    service.grant(request("WAIT", "v1"));
                                                }));
                assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> pending.get(250, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(mutation.get(20, TimeUnit.SECONDS)).isEqualTo("OK");
                assertThat(pending.get(20, TimeUnit.SECONDS))
                        .isEqualTo(
                                change.equals("APPOINTMENT")
                                        ? "PERMISSION_DENIED"
                                        : "ROLE_NOT_DELEGABLE");
            } finally {
                release.countDown();
            }
        }
        assertThat(sources()).isZero();
        assertThat(revision()).isEqualTo(1);
    }

    @Test
    void combinedPolicyRequiresTransactionAndLocksActorAndTargetTogether() {
        assertThatThrownBy(() -> policy.requireGrantContext(id(2), id(6), id(100), true, true))
                .isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions)
                .executeWithoutResult(
                        tx ->
                                assertThat(
                                                policy.requireGrantContext(
                                                                id(2), id(6), id(100), true, true)
                                                        .users())
                                        .containsKeys(id(2), id(6)));
    }

    private String asActor(long relative, Runnable action) {
        state.actor.set(id(relative));
        try {
            action.run();
            return "OK";
        } catch (BusinessException exception) {
            return exception.errorCode().name();
        } finally {
            state.actor.remove();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS))
                throw new AssertionError("test coordination timeout");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError(exception);
        }
    }

    static class TestState {
        final ThreadLocal<Long> actor = ThreadLocal.withInitial(() -> 0L);
        volatile String failure;
        volatile String deniedPermission;
        volatile java.util.function.LongConsumer afterGrantCommit;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Integrations {
        @Bean
        TestState grantTestState() {
            return new TestState();
        }

        @Bean
        @Primary
        RequestSecurity security(TestState state) {
            return Fakes.security(
                    state.actor::get,
                    (actor, permission) -> {
                        assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                                .isTrue();
                        if (permission.equals(state.deniedPermission))
                            throw new BusinessException(PERMISSION_DENIED);
                    });
        }

        @Bean
        @Primary
        AdminIntegrity testAdminIntegrity() {
            return new AdminIntegrity(null, null) {
                public void verify(AdminState facts) {}

                public void sign(AdminState facts, Set<Long> changed) {}
            };
        }

        @Bean
        @Primary
        AdminAudit testAdminAudit() {
            return Fakes.adminAudit(change -> {});
        }

        @Bean
        @Primary
        GrantIntegrity testGrantIntegrity(TestState state, JdbcTemplate jdbc) {
            return new GrantIntegrity(null) {
                public void verify(GrantState facts) {
                    assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                            .isTrue();
                    if ("VERIFY".equals(state.failure))
                        throw new BusinessException(INTEGRITY_FAILURE);
                }

                public void sign(GrantState facts) {
                    // 仅用于测试的非密码学签名替身，不会打入应用产物。
                    jdbc.update(
                            "INSERT INTO sys_data_signature(data_type,data_id,signature,key_id)"
                                    + " VALUES('TEST_GRANT',?,?,?) ON DUPLICATE KEY UPDATE"
                                    + " signature=?,key_id=?",
                            facts.userId(),
                            "x".repeat(88),
                            "test-v" + facts.grantRevision(),
                            "x".repeat(88),
                            "test-v" + facts.grantRevision());
                    if ("SIGN".equals(state.failure))
                        throw new BusinessException(INTEGRITY_FAILURE);
                }
            };
        }

        @Bean
        @Primary
        GrantAudit testGrantAudit(TestState state, JdbcTemplate jdbc) {
            return Fakes.grantAudit(
                    change -> {
                        jdbc.update(
                                "INSERT INTO"
                                    + " sys_operation_log(user_id,request_id,operation_type,operation_desc,target_type,target_id,status,evidence)"
                                    + " VALUES(?,?,?,?,'USER_GRANT',?,1,JSON_OBJECT('sourceId',?,'beforeRevision',?,'afterRevision',?,'remaining',?))",
                                change.actorId(),
                                UUID.randomUUID().toString(),
                                change.operation(),
                                change.reason(),
                                change.userId(),
                                change.sourceId(),
                                change.before().grantRevision(),
                                change.after().grantRevision(),
                                change.after().sources().stream()
                                        .filter(s -> s.status() == 1)
                                        .count());
                        if ("AUDIT".equals(state.failure))
                            throw new BusinessException(AUDIT_UNAVAILABLE);
                        var afterCommit = state.afterGrantCommit;
                        if (afterCommit != null) {
                            TransactionSynchronizationManager.registerSynchronization(
                                    new TransactionSynchronization() {
                                        @Override
                                        public void afterCommit() {
                                            afterCommit.accept(change.userId());
                                        }
                                    });
                        }
                    });
        }
    }
}
