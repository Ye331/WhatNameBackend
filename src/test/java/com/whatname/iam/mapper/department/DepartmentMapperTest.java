package com.whatname.iam.mapper.department;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.entity.department.Department;
import com.whatname.iam.support.TestDatabase;

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
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.InputStream;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** 临时 MySQL 中验证真实 Mapper，不依赖个人数据库和预先导入的数据。 */
@ExtendWith(TestDatabase.class)
class DepartmentMapperTest {
    private static SqlSessionFactory factory;

    @BeforeAll
    static void createFactory() throws Exception {
        var dataSource =
                new UnpooledDataSource(
                        "com.mysql.cj.jdbc.Driver",
                        TestDatabase.url(),
                        TestDatabase.username(),
                        TestDatabase.password());
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
        var configuration =
                new Configuration(
                        new Environment(
                                "department-dev-read", new JdbcTransactionFactory(), dataSource));
        configuration.setCacheEnabled(false);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        String resource = "mapper/department/DepartmentMapper.xml";
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments())
                    .parse();
        }
        factory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void mapsRootBranchAndChildUsingRealInternalIds() {
        try (SqlSession session = factory.openSession()) {
            var mapper = session.getMapper(DepartmentMapper.class);
            Department root = required(mapper, "TEST-ROOT", "总公司");
            Department branch = required(mapper, "TEST-BRANCH", "道路工程分公司");
            Department child = required(mapper, "TEST-CHILD", "道路工程分公司-综合办公室");
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
            Department branch = mapper.findByCode("TEST-BRANCH");
            Department child = mapper.findByCode("TEST-CHILD");
            List<Department> children = mapper.findChildrenByParentId(branch.getId());
            assertTrue(children.stream().anyMatch(d -> d.getId().equals(child.getId())));
            assertTrue(children.stream().allMatch(d -> d.getParentId().equals(branch.getId())));
            assertEquals(
                    List.of("TEST-CHILD"), children.stream().map(Department::getDeptCode).toList());
            List<Department> subtree = mapper.findSubtreeById(branch.getId());
            assertTrue(subtree.stream().anyMatch(d -> d.getId().equals(branch.getId())));
            assertTrue(subtree.stream().anyMatch(d -> d.getId().equals(child.getId())));
            assertTrue(
                    subtree.stream()
                            .allMatch(d -> d.getDeptPath().startsWith(branch.getDeptPath())));
            assertFalse(subtree.stream().anyMatch(d -> d.getDeptCode().equals("TEST-ROOT")));
            assertTrue(
                    mapper.findSubtreeById(child.getId()).stream()
                            .allMatch(d -> d.getDeptPath().startsWith(child.getDeptPath())));
            Department root = mapper.findByCode("TEST-ROOT");
            Set<String> rootCodes =
                    mapper.findSubtreeById(root.getId()).stream()
                            .map(Department::getDeptCode)
                            .collect(Collectors.toSet());
            assertEquals(
                    Set.of("TEST-ROOT", "TEST-BRANCH", "TEST-CHILD", "TEST-OUTSIDE"), rootCodes);
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
        assertNotNull(department, "Fixture department must exist");
        assertEquals(name, department.getDeptName());
        return department;
    }
}
