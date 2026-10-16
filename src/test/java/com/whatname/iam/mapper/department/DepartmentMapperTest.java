package com.whatname.iam.mapper.department;

import com.whatname.iam.entity.department.Department;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in reads against the personal seeded MySQL; no Spring/auth context or writes. */
@EnabledIfEnvironmentVariable(named = "DEPARTMENT_DB_TEST", matches = "true")
class DepartmentMapperTest {
    private static SqlSessionFactory factory;

    @BeforeAll
    static void createFactory() throws Exception {
        String password = System.getenv("DEPARTMENT_DB_PASSWORD");
        assertNotNull(password, "Set DEPARTMENT_DB_PASSWORD privately for this opt-in test");
        var dataSource = new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                System.getenv().getOrDefault("DEPARTMENT_DB_URL",
                        "jdbc:mysql://127.0.0.1:3307/unified_permission?characterEncoding=UTF-8&connectionTimeZone=Asia/Taipei"),
                System.getenv().getOrDefault("DEPARTMENT_DB_USERNAME", "iam_dev"), password);
        var configuration = new Configuration(new Environment("department-dev-read",
                new JdbcTransactionFactory(), dataSource));
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        String resource = "mapper/department/DepartmentMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void mapsRootBranchAndChildUsingRealInternalIds() {
        try (SqlSession session = factory.openSession()) {
            var mapper = session.getMapper(DepartmentMapper.class);
            Department root = required(mapper, "DEV-DEPT-R0002", "总公司");
            Department branch = required(mapper, "DEV-DEPT-R0014", "道路工程分公司");
            Department child = required(mapper, "DEV-DEPT-R0015", "道路工程分公司-综合办公室");
            assertEquals(0L, root.getParentId());
            assertEquals("/" + root.getId() + "/", root.getDeptPath());
            assertEquals(root.getId(), branch.getParentId());
            assertEquals(root.getDeptPath() + branch.getId() + "/", branch.getDeptPath());
            assertEquals(branch.getId(), child.getParentId());
            assertEquals(branch.getDeptPath() + child.getId() + "/", child.getDeptPath());
            for (Department department : List.of(root, branch, child)) {
                Department byId = mapper.findById(department.getId());
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
        try (SqlSession session = factory.openSession()) {
            var mapper = session.getMapper(DepartmentMapper.class);
            Department branch = mapper.findByCode("DEV-DEPT-R0014");
            Department child = mapper.findByCode("DEV-DEPT-R0015");
            List<Department> children = mapper.findChildrenByParentId(branch.getId());
            assertTrue(children.stream().anyMatch(d -> d.getId().equals(child.getId())));
            assertTrue(children.stream().allMatch(d -> d.getParentId().equals(branch.getId())));
            List<String> seededChildren = children.stream().map(Department::getDeptCode)
                    .filter(code -> code.startsWith("DEV-DEPT-R")).toList();
            assertEquals(List.of("DEV-DEPT-R0015", "DEV-DEPT-R0016", "DEV-DEPT-R0017",
                    "DEV-DEPT-R0018", "DEV-DEPT-R0019", "DEV-DEPT-R0020", "DEV-DEPT-R0021",
                    "DEV-DEPT-R0022", "DEV-DEPT-R0023", "DEV-DEPT-R0024"), seededChildren);
            List<Department> subtree = mapper.findSubtreeById(branch.getId());
            assertTrue(subtree.stream().anyMatch(d -> d.getId().equals(branch.getId())));
            assertTrue(subtree.stream().anyMatch(d -> d.getId().equals(child.getId())));
            assertTrue(subtree.stream().allMatch(d -> d.getDeptPath().startsWith(branch.getDeptPath())));
            assertFalse(subtree.stream().anyMatch(d -> d.getDeptCode().equals("DEV-DEPT-R0002")));
            assertTrue(mapper.findSubtreeById(child.getId()).stream()
                    .allMatch(d -> d.getDeptPath().startsWith(child.getDeptPath())));
            Department root = mapper.findByCode("DEV-DEPT-R0002");
            Set<String> rootCodes = mapper.findSubtreeById(root.getId()).stream()
                    .map(Department::getDeptCode).collect(Collectors.toSet());
            // Assert the controlled codes, not the total number of rows in the database.
            for (int sourceRow = 2; sourceRow <= 112; sourceRow++) {
                assertTrue(rootCodes.contains("DEV-DEPT-R%04d".formatted(sourceRow)));
            }
        }
    }

    @Test
    void missingIdentifiersAndSqlLikeInputReturnNoRows() {
        try (SqlSession session = factory.openSession()) {
            var mapper = session.getMapper(DepartmentMapper.class);
            assertNull(mapper.findById(-1L));
            assertNull(mapper.findByCode("DEV-DEPT-NOT-PRESENT"));
            assertNull(mapper.findByCode("' OR 1=1 --"));
            assertNull(mapper.findByCode("DEV-DEPT-%"));
            assertTrue(mapper.findSubtreeById(-1L).isEmpty());
            assertTrue(mapper.findChildrenByParentId(-1L).isEmpty());
        }
    }

    private static Department required(DepartmentMapper mapper, String code, String name) {
        Department department = mapper.findByCode(code);
        assertNotNull(department, "Seed the controlled local development data before running reads");
        assertEquals(name, department.getDeptName());
        return department;
    }
}
