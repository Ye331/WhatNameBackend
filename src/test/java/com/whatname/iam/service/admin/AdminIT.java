package com.whatname.iam.service.admin;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.admin.AdminDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static com.whatname.iam.common.api.ErrorCode.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(AdminIT.TestIntegrations.class)
@Testcontainers
class AdminIT {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("unified_permission")
            .withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--default-time-zone=+08:00")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db", "01_schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schema.sql");

    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired AdminService service;
    @Autowired AdminPolicyService policy;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestState testState;
    @Autowired PlatformTransactionManager transactions;
    private static final AtomicLong NEXT = new AtomicLong(1000);
    private long base;
    private long id(long relative) { return base + relative; }
    private String sid(long relative) { return Long.toString(id(relative)); }

    @BeforeEach void seed() {
        base = NEXT.getAndAdd(1000);
        testState.actor.set(id(1));
        testState.failure = null;
        var data = new AdminTestFixture();
        for (var department : data.departments.values()) {
            List<String> path = new ArrayList<>();
            for (long current = department.id(); current != 0; current = data.departments.get(current).parentId()) {
                path.addFirst(Long.toString(id(current)));
            }
            jdbc.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path,status) VALUES(?,?,?,?,?,?)",
                    id(department.id()), department.parentId() == 0 ? 0 : id(department.parentId()),
                    "D" + id(department.id()), "test department", "/" + String.join("/", path) + "/", department.status());
        }
        for (var user : data.users.values()) {
            jdbc.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status) VALUES(?,?,?,?,?,?,?,?,?)",
                    id(user.id()), "test_user_" + id(user.id()), "test-only-ciphertext", "test-only-ciphertext",
                    String.format("%064x", id(user.id())), "test-only-unusable-hash", id(user.deptId()), 3, user.status());
        }
        for (var role : data.roles.values()) {
            jdbc.update("INSERT INTO sys_role(id,role_code,role_name,role_type,status) VALUES(?,?,?,?,?)",
                    id(role.id()), "R" + id(role.id()), "test role", role.roleType(), role.status());
        }
        for (var admin : data.appointments.values()) {
            jdbc.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,appointed_by,status,delegable_revision) VALUES(?,?,?,?,?,?)",
                    id(admin.userId()), admin.adminLevel(), id(admin.deptId()),
                    admin.appointedBy() == null ? null : id(admin.appointedBy()), admin.status(), admin.delegableRevision());
        }
        for (var item : data.delegations) {
            jdbc.update("INSERT INTO sys_admin_delegable_role(admin_user_id,role_id,configured_by) VALUES(?,?,?)",
                    id(item.adminUserId()), id(item.roleId()), id(item.configuredBy()));
        }
        jdbc.update("INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(?,?,?)", id(6), id(100), id(2));
    }

    @Test void appointmentRevokeAndQueriesUseRealMappings() {
        assertThat(service.myScope().appointed()).isTrue();
        var result = service.appoint(new AppointmentRequest(sid(6), 3, sid(21), "local test"));
        assertThat(result.userId()).isEqualTo(sid(6));
        assertThat(service.list(sid(21), 1, 20).items()).extracting(Summary::userId)
                .containsExactly(sid(3), sid(4), sid(6));
        assertThat(service.revoke(sid(6), new RevokeRequest("local test")).status()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM sys_admin WHERE user_id=?", Integer.class, id(6))).isZero();
        assertThat(auditCount(6)).isEqualTo(2);
    }

    @Test void sharedWritePolicyRequiresAndJoinsCallersTransaction() {
        assertThatThrownBy(() -> policy.requireLevelOne(id(1))).isInstanceOf(IllegalTransactionStateException.class);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            policy.requireLevelOne(id(1));
            policy.requireUserScope(id(2), id(6));
            policy.requireDepartmentScope(id(2), id(22));
            policy.requireDelegableRole(id(2), id(100));
        });
    }

    @Test void recursiveShrinkUpdatesRevisionsAndPreservesExistingUserGrant() {
        var result = service.replaceDelegableRoles(sid(2), request(List.of(sid(101)), "v1"));
        assertThat(result.delegationVersion()).isEqualTo("v2");
        assertThat(service.delegableRoles(sid(3)).roleIds()).containsExactly(sid(101));
        assertThat(service.delegableRoles(sid(4)).roleIds()).isEmpty();
        assertThat(service.delegableRoles(sid(5)).roleIds()).containsExactly(sid(100));
        assertThat(revision(2)).isEqualTo(2);
        assertThat(revision(3)).isEqualTo(2);
        assertThat(revision(4)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_user_role WHERE user_id=? AND role_id=?",
                Integer.class, id(6), id(100))).isEqualTo(1);
    }

    @Test void auditFailureRollsBackListDescendantsVersionsAndAuditInsert() {
        testState.failure = "AUDIT";
        assertThatThrownBy(() -> service.replaceDelegableRoles(sid(2), request(List.of(), "v1")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(AUDIT_UNAVAILABLE));
        assertThat(revision(2)).isEqualTo(1);
        assertThat(revision(3)).isEqualTo(1);
        assertThat(revision(4)).isEqualTo(1);
        assertThat(service.delegableRoles(sid(2)).roleIds()).containsExactly(sid(100), sid(101));
        assertThat(service.delegableRoles(sid(4)).roleIds()).containsExactly(sid(100));
        assertThat(auditCount(2)).isZero();
    }

    @Test void signingFailureRollsBackAppointment() {
        testState.failure = "SIGN";
        assertThatThrownBy(() -> service.appoint(new AppointmentRequest(sid(6), 3, sid(21), "test failure")))
                .isInstanceOfSatisfying(BusinessException.class, ex -> assertThat(ex.errorCode()).isEqualTo(INTEGRITY_FAILURE));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_admin WHERE user_id=?", Integer.class, id(6))).isZero();
        assertThat(auditCount(6)).isZero();
    }

    @Test void reappointmentClearsAndCascadesInTheSameTransaction() {
        service.revoke(sid(2), new RevokeRequest("test"));
        service.appoint(new AppointmentRequest(sid(2), 2, sid(20), "test reappoint"));
        assertThat(service.delegableRoles(sid(2)).roleIds()).isEmpty();
        assertThat(service.delegableRoles(sid(3)).roleIds()).containsExactly(sid(101));
        assertThat(service.delegableRoles(sid(4)).roleIds()).isEmpty();
        assertThat(revision(2)).isEqualTo(2);
    }

    @Test void concurrentReplacementWithSameVersionHasExactlyOneWinner() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> replaceConcurrently(start, sid(100)));
            var second = executor.submit(() -> replaceConcurrently(start, sid(101)));
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder("OK", "VERSION_CONFLICT");
        }
        assertThat(revision(2)).isEqualTo(2);
        assertThat(service.delegableRoles(sid(2)).roleIds()).hasSize(1);
        assertThat(auditCount(2)).isEqualTo(1);
    }

    @Test void waitingDelegationRechecksAppointmentAfterConcurrentRevocationCommits() throws Exception {
        var revoked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var delegationStarted = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var revocation = executor.submit(() -> {
                testState.actor.set(id(1));
                try {
                    new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                        service.revoke(sid(2), new RevokeRequest("concurrent revoke"));
                        revoked.countDown();
                        await(release);
                    });
                } finally { testState.actor.remove(); }
            });
            try {
                assertThat(revoked.await(10, TimeUnit.SECONDS)).isTrue();
                var delegation = executor.submit(() -> {
                    testState.actor.set(id(2));
                    delegationStarted.countDown();
                    try {
                        service.replaceDelegableRoles(sid(3), request(List.of(sid(101)), "v1"));
                        return "OK";
                    } catch (BusinessException error) { return error.errorCode().name(); }
                    finally { testState.actor.remove(); }
                });
                assertThat(delegationStarted.await(10, TimeUnit.SECONDS)).isTrue();
                release.countDown();
                revocation.get(20, TimeUnit.SECONDS);
                assertThat(delegation.get(20, TimeUnit.SECONDS)).isEqualTo("PERMISSION_DENIED");
            } finally { release.countDown(); }
        }
        assertThat(revision(3)).isEqualTo(1);
    }

    private String replaceConcurrently(CountDownLatch start, String roleId) {
        testState.actor.set(id(1));
        try {
            await(start);
            service.replaceDelegableRoles(sid(2), request(List.of(roleId), "v1"));
            return "OK";
        } catch (BusinessException error) { return error.errorCode().name(); }
        finally { testState.actor.remove(); }
    }
    private static void await(CountDownLatch latch) {
        try { if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("test coordination timeout"); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
    }
    private static DelegationRequest request(List<String> roles, String version) {
        return new DelegationRequest(roles, version, "integration test");
    }
    private int revision(long relative) {
        return jdbc.queryForObject("SELECT delegable_revision FROM sys_admin WHERE user_id=?", Integer.class, id(relative));
    }
    private int auditCount(long relative) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=?", Integer.class, id(relative));
    }

    static class TestState {
        final ThreadLocal<Long> actor = ThreadLocal.withInitial(() -> 0L);
        volatile String failure;
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class TestIntegrations {
        @Bean TestState testState() { return new TestState(); }
        @Bean @Primary AdminPorts.Identity testIdentity(TestState state) { return state.actor::get; }
        @Bean @Primary AdminPorts.Functions testFunctions() { return (actor, permission) -> {}; }
        @Bean @Primary AdminPorts.Integrity testIntegrity(TestState state) {
            return new AdminPorts.Integrity() {
                public void verify(AdminState facts) {}
                public void sign(AdminState facts, Set<Long> changed) {
                    if ("SIGN".equals(state.failure)) throw new BusinessException(INTEGRITY_FAILURE);
                }
            };
        }
        @Bean @Primary AdminPorts.Audit testAudit(TestState state, JdbcTemplate jdbc) {
            return change -> {
                jdbc.update("INSERT INTO sys_operation_log(user_id,request_id,operation_type,operation_desc,target_type,target_id,status) VALUES(?,?,?,?,?,?,1)",
                        change.actorId(), UUID.randomUUID().toString(), change.operation(), change.reason(), "ADMIN", change.targetId());
                if ("AUDIT".equals(state.failure)) throw new BusinessException(AUDIT_UNAVAILABLE);
            };
        }
    }
}
