package com.whatname.iam.repository.department;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.entity.department.Department;
import com.whatname.iam.support.TestDatabase;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 临时 MySQL 中验证真实 JPA Repository，不依赖个人数据库和预先导入的数据。 */
@ExtendWith(TestDatabase.class)
class DepartmentRepositoryTest {
    private static org.springframework.context.ConfigurableApplicationContext app;

    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    @org.springframework.boot.autoconfigure.domain.EntityScan("com.whatname.iam.entity")
    @org.springframework.data.jpa.repository.config.EnableJpaRepositories({"com.whatname.iam.repository.department", "com.whatname.iam.repository.event"})
    static class TestApplication {}

    @org.junit.jupiter.api.AfterAll
    static void close() { if (app != null) app.close(); }

    @BeforeAll
    static void createFactory() throws Exception {
        var application = new org.springframework.boot.SpringApplication(TestApplication.class);
        application.setWebApplicationType(org.springframework.boot.WebApplicationType.NONE);
        app = application.run(TestDatabase.arguments());
        var dataSource = app.getBean(javax.sql.DataSource.class);
        // 四个明确的节点足以验证父子、排序和子树隔离，不依赖 Excel 开发数据。
        try (var connection = dataSource.getConnection();
                var sql = connection.createStatement()) {
            sql.executeUpdate(
                    "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path,sort_order)"
                        + " VALUES (11,0,'TEST-ROOT','总公司','/11/',0),"
                        + "(12,11,'TEST-BRANCH','道路工程分公司','/11/12/',1),"
                        + "(13,12,'TEST-CHILD','道路工程分公司-综合办公室','/11/12/13/',2),"
                        + "(14,11,'TEST-OUTSIDE','其他分公司','/11/14/',3)");
        }
    }

    @Test
    void mapsRootBranchAndChildUsingRealInternalIds() {
        {
            var repository = app.getBean(DepartmentRepository.class);
            Department root = required(repository, "TEST-ROOT", "总公司");
            Department branch = required(repository, "TEST-BRANCH", "道路工程分公司");
            Department child = required(repository, "TEST-CHILD", "道路工程分公司-综合办公室");
            assertEquals(0L, root.getParentId());
            assertEquals("/" + root.getId() + "/", root.getDeptPath());
            assertEquals(root.getId(), branch.getParentId());
            assertEquals(root.getDeptPath() + branch.getId() + "/", branch.getDeptPath());
            assertEquals(branch.getId(), child.getParentId());
            assertEquals(branch.getDeptPath() + child.getId() + "/", child.getDeptPath());
            for (Department department : List.of(root, branch, child)) {
                Department byId = repository.findById(department.getId());
                assertEquals(department.getDeptCode(), byId.getDeptCode());
                assertEquals(department.getDeptPath(), byId.getDeptPath());
                assertNull(byId.getManagerId());
                assertEquals(1, byId.getStatus());
                assertEquals(0L, byId.getHrVersion());
                assertNotNull(byId.getSortOrder());
                assertNotNull(byId.getCreatedAt());
                assertNotNull(byId.getUpdatedAt());
            }
        }
    }

    @Test
    void readsChildrenAndOnlyTheRequestedSubtree() {
        {
            var repository = app.getBean(DepartmentRepository.class);
            Department branch = repository.findByCode("TEST-BRANCH");
            Department child = repository.findByCode("TEST-CHILD");
            List<Department> children = repository.findChildrenByParentId(branch.getId());
            assertTrue(children.stream().anyMatch(d -> d.getId().equals(child.getId())));
            assertTrue(children.stream().allMatch(d -> d.getParentId().equals(branch.getId())));
            assertEquals(
                    List.of("TEST-CHILD"), children.stream().map(Department::getDeptCode).toList());
            List<Department> subtree = repository.findSubtreeById(branch.getId());
            assertTrue(subtree.stream().anyMatch(d -> d.getId().equals(branch.getId())));
            assertTrue(subtree.stream().anyMatch(d -> d.getId().equals(child.getId())));
            assertTrue(
                    subtree.stream()
                            .allMatch(d -> d.getDeptPath().startsWith(branch.getDeptPath())));
            assertFalse(subtree.stream().anyMatch(d -> d.getDeptCode().equals("TEST-ROOT")));
            assertTrue(
                    repository.findSubtreeById(child.getId()).stream()
                            .allMatch(d -> d.getDeptPath().startsWith(child.getDeptPath())));
            Department root = repository.findByCode("TEST-ROOT");
            Set<String> rootCodes =
                    repository.findSubtreeById(root.getId()).stream()
                            .map(Department::getDeptCode)
                            .collect(Collectors.toSet());
            assertEquals(
                    Set.of("TEST-ROOT", "TEST-BRANCH", "TEST-CHILD", "TEST-OUTSIDE"), rootCodes);
        }
    }

    @Test
    void missingIdentifiersAndSqlLikeInputReturnNoRows() {
        {
            var repository = app.getBean(DepartmentRepository.class);
            assertNull(repository.findById(-1L));
            assertNull(repository.findByCode("DEV-DEPT-NOT-PRESENT"));
            assertNull(repository.findByCode("' OR 1=1 --"));
            assertNull(repository.findByCode("DEV-DEPT-%"));
            assertTrue(repository.findSubtreeById(-1L).isEmpty());
            assertTrue(repository.findChildrenByParentId(-1L).isEmpty());
        }
    }

    private static Department required(DepartmentRepository repository, String code, String name) {
        Department department = repository.findByCode(code);
        assertNotNull(department, "Fixture department must exist");
        assertEquals(name, department.getDeptName());
        return department;
    }

    @Test
    void nativeEventProjectionPreservesDigestAliasesAndMissingRows() {
        var events = app.getBean(com.whatname.iam.repository.event.ExternalEventRepository.class);
        String event = "projection-contract";
        assertTrue(events.lock("HR", event).isEmpty());
        String digest = "0123456789abcdef".repeat(4);
        events.insert("HR", event, "DEPARTMENT_UPSERT", 1L, "DEPT", "D1", digest, "ciphertext", null);
        var row = events.lock("HR", event);
        assertEquals(digest, row.get("digest"), () -> "keys=" + row.keySet() + ", digest type=" + (row.get("digest") == null ? "null" : row.get("digest").getClass()));
        assertEquals("DEPARTMENT_UPSERT", row.get("eventType"));
        assertEquals("DEPT", row.get("targetType"));
    }

    @Test
    void nativeWritesEvictManagedStateAndShareRollbackWithJdbc() {
        var manager = app.getBean(org.springframework.transaction.PlatformTransactionManager.class);
        assertInstanceOf(org.springframework.orm.jpa.JpaTransactionManager.class, manager);
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        var reads = app.getBean(DepartmentRepository.class);
        var writes = app.getBean(OrganizationWriteRepository.class);
        var jdbc = app.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
        String original = reads.findById(11L).getDeptName();

        assertThrows(IllegalStateException.class, () -> transaction.execute(status -> {
            // Prime the persistence context through JPQL, then write through native JPA SQL.
            var managed = reads.findById(11L);
            var snapshot = writes.organization().stream().filter(d -> d.getId() == 11L).findFirst().orElseThrow();
            snapshot.setDeptName("native change");
            assertEquals(1, writes.update(snapshot));
            var refreshed = reads.findById(11L);
            assertNotSame(managed, refreshed);
            assertEquals("native change", refreshed.getDeptName());
            jdbc.update("UPDATE sys_dept SET sort_order=99 WHERE id=11");
            assertEquals(99, jdbc.queryForObject("SELECT sort_order FROM sys_dept WHERE id=11", Integer.class));
            throw new IllegalStateException("rollback both persistence APIs");
        }));
        assertEquals(original, reads.findById(11L).getDeptName());
        assertEquals(0, jdbc.queryForObject("SELECT sort_order FROM sys_dept WHERE id=11", Integer.class));
    }
}
