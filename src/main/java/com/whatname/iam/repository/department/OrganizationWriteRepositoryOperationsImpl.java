package com.whatname.iam.repository.department;

import com.whatname.iam.entity.department.Department;
import java.util.List;
import java.util.Map;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class OrganizationWriteRepositoryOperationsImpl extends JpaSqlSupport implements OrganizationWriteRepositoryOperations {
    @Override
    public List<Department> lockOrganization() {
        var sql = new Sql();
        sql.append("SELECT * FROM sys_dept FORCE INDEX(PRIMARY) ORDER BY id FOR UPDATE");
        return list(sql, Department.class);
    }

    @Override
    public List<Department> organization() {
        var sql = new Sql();
        sql.append("SELECT * FROM sys_dept ORDER BY sort_order,id");
        return list(sql, Department.class);
    }

    @Override
    public Long idByCode(String code) {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_dept WHERE dept_code=");
        sql.param(code);
        return one(sql, Long.class);
    }

    @Override
    public int insert(Department department) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path,manager_id,hr_version) VALUES(");
        sql.param(property(department, "parentId"));
        sql.append(",");
        sql.param(property(department, "deptCode"));
        sql.append(",");
        sql.param(property(department, "deptName"));
        sql.append(",");
        sql.param(property(department, "deptPath"));
        sql.append(",");
        sql.param(property(department, "managerId"));
        sql.append(",");
        sql.param(property(department, "hrVersion"));
        sql.append(")");
        int changed = update(sql);
        setProperty(department, "id", lastInsertId());
        return changed;
    }

    @Override
    public int update(Department department) {
        var sql = new Sql();
        sql.append("UPDATE sys_dept SET parent_id=");
        sql.param(property(department, "parentId"));
        sql.append(",dept_name=");
        sql.param(property(department, "deptName"));
        sql.append(",dept_path=");
        sql.param(property(department, "deptPath"));
        sql.append(",manager_id=");
        sql.param(property(department, "managerId"));
        sql.append(",status=");
        sql.param(property(department, "status"));
        sql.append(",merged_into_dept_id=");
        sql.param(property(department, "mergedIntoDeptId"));
        sql.append(",hr_version=");
        sql.param(property(department, "hrVersion"));
        sql.append(" WHERE id=");
        sql.param(property(department, "id"));
        return update(sql);
    }

    @Override
    public Long manager(String sgNo) {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_user WHERE BINARY employee_no=BINARY ");
        sql.param(sgNo);
        sql.append(" AND source=1 AND status=1");
        return one(sql, Long.class);
    }

    @Override
    public List<Map<String, Object>> lockUsers(Long deptId) {
        var sql = new Sql();
        sql.append("SELECT id,dept_id AS deptId,employee_no AS sgNo,status,session_version AS sessionVersion,grant_revision AS grantRevision,hr_version AS hrVersion FROM sys_user WHERE dept_id=");
        sql.param(deptId);
        sql.append(" ORDER BY id FOR UPDATE");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public int moveUsers(Long source, Long target) {
        var sql = new Sql();
        sql.append("UPDATE sys_user SET dept_id=");
        sql.param(target);
        sql.append(" WHERE dept_id=");
        sql.param(source);
        return update(sql);
    }

    @Override
    public List<Map<String, Object>> users(List<Long> ids, int limit, long offset) {
        var sql = new Sql();
        sql.append("SELECT id AS userId,employee_no AS sgNo,dept_id AS deptId,status FROM sys_user WHERE dept_id IN ");
        sql.append("(");
        boolean firstid1 = true;
        for (var id : ids) {
            if (!firstid1) sql.append(",");
            firstid1 = false;
            sql.param(id);
        }
        sql.append(")");
        sql.append(" ORDER BY id LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public long countUsers(List<Long> ids) {
        var sql = new Sql();
        sql.append("SELECT COUNT(*) FROM sys_user WHERE dept_id IN ");
        sql.append("(");
        boolean firstid2 = true;
        for (var id : ids) {
            if (!firstid2) sql.append(",");
            firstid2 = false;
            sql.param(id);
        }
        sql.append(")");
        return one(sql, long.class);
    }

}
