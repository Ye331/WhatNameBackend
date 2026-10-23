package com.whatname.iam.repository.admin;

import com.whatname.iam.entity.admin.AdminData.*;
import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class AdminRepositoryOperationsImpl extends JpaSqlSupport implements AdminRepositoryOperations {
    @Override
    public List<Appointment> selectAppointments(boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT user_id, admin_level, dept_id, status, delegable_revision, appointed_by FROM sys_admin ORDER BY user_id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, Appointment.class);
    }

    @Override
    public List<Department> selectDepartments(boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT id, parent_id, status, merged_into_dept_id FROM sys_dept ORDER BY id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, Department.class);
    }

    @Override
    public List<Role> selectRoles(boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT id, role_type, status FROM sys_role ORDER BY id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, Role.class);
    }

    @Override
    public List<Delegation> selectDelegations(boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT admin_user_id, role_id, configured_by FROM sys_admin_delegable_role ORDER BY admin_user_id, role_id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, Delegation.class);
    }

    @Override
    public List<User> selectUsers(List<Long> ids, boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT id, dept_id, status FROM sys_user WHERE id IN ");
        sql.append("(");
        boolean firstid1 = true;
        for (var id : ids) {
            if (!firstid1) sql.append(",");
            firstid1 = false;
            sql.param(id);
        }
        sql.append(")");
        sql.append(" ORDER BY id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, User.class);
    }

    @Override
    public List<User> selectAllUsers() {
        var sql = new Sql();
        sql.append(" SELECT id, dept_id, status FROM sys_user FORCE INDEX(PRIMARY) ORDER BY id FOR UPDATE ");
        return list(sql, User.class);
    }

    @Override
    public int insertAppointment(long userId, int level, long deptId, long actorId) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_admin(user_id, admin_level, dept_id, appointed_by, status, delegable_revision) VALUES(");
        sql.param(userId);
        sql.append(", ");
        sql.param(level);
        sql.append(", ");
        sql.param(deptId);
        sql.append(", ");
        sql.param(actorId);
        sql.append(", 1, 1) ");
        return update(sql);
    }

    @Override
    public int reactivate(long userId, int level, long deptId, long actorId) {
        var sql = new Sql();
        sql.append(" UPDATE sys_admin SET admin_level=");
        sql.param(level);
        sql.append(", dept_id=");
        sql.param(deptId);
        sql.append(", appointed_by=");
        sql.param(actorId);
        sql.append(", appointed_at=CURRENT_TIMESTAMP, status=1 WHERE user_id=");
        sql.param(userId);
        sql.append(" AND status=0 ");
        return update(sql);
    }

    @Override
    public int revoke(long userId) {
        var sql = new Sql();
        sql.append(" UPDATE sys_admin SET status=0 WHERE user_id=");
        sql.param(userId);
        sql.append(" AND status=1 ");
        return update(sql);
    }

    @Override
    public int deleteDelegations(long userId) {
        var sql = new Sql();
        sql.append(" DELETE FROM sys_admin_delegable_role WHERE admin_user_id=");
        sql.param(userId);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public int deleteDelegation(long userId, long roleId) {
        var sql = new Sql();
        sql.append(" DELETE FROM sys_admin_delegable_role WHERE admin_user_id=");
        sql.param(userId);
        sql.append(" AND role_id=");
        sql.param(roleId);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public int insertDelegation(long userId, long roleId, long actorId) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_admin_delegable_role(admin_user_id, role_id, configured_by) VALUES(");
        sql.param(userId);
        sql.append(", ");
        sql.param(roleId);
        sql.append(", ");
        sql.param(actorId);
        sql.append(") ");
        return update(sql);
    }

    @Override
    public int advanceRevision(long userId, int expected) {
        var sql = new Sql();
        sql.append(" UPDATE sys_admin SET delegable_revision=delegable_revision+1 WHERE user_id=");
        sql.param(userId);
        sql.append(" AND delegable_revision=");
        sql.param(expected);
        sql.append(" AND delegable_revision < 2147483647 ");
        return update(sql);
    }

}
