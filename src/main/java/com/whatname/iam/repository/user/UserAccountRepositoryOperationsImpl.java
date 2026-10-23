package com.whatname.iam.repository.user;

import com.whatname.iam.entity.user.UserAccount;
import java.util.List;
import java.util.Set;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class UserAccountRepositoryOperationsImpl extends JpaSqlSupport implements UserAccountRepositoryOperations {
    @Override
    public UserAccount findById(long id) {
        var sql = new Sql();
        sql.append("SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(" FROM sys_user WHERE id=");
        sql.param(id);
        return one(sql, UserAccount.class);
    }

    @Override
    public UserAccount lockById(long id) {
        var sql = new Sql();
        sql.append("SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(" FROM sys_user WHERE id=");
        sql.param(id);
        sql.append(" FOR UPDATE");
        return one(sql, UserAccount.class);
    }

    @Override
    public UserAccount lockByUsername(String username) {
        var sql = new Sql();
        sql.append("SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(" FROM sys_user WHERE username=");
        sql.param(username);
        sql.append(" FOR UPDATE");
        return one(sql, UserAccount.class);
    }

    @Override
    public UserAccount lockByPhone(String phoneLookup) {
        var sql = new Sql();
        sql.append("SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(" FROM sys_user WHERE phone=");
        sql.param(phoneLookup);
        sql.append(" FOR UPDATE");
        return one(sql, UserAccount.class);
    }

    @Override
    public UserAccount lockByEmployeeNo(String sgNo) {
        var sql = new Sql();
        sql.append("SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(" FROM sys_user WHERE employee_no=");
        sql.param(sgNo);
        sql.append(" AND BINARY employee_no=BINARY ");
        sql.param(sgNo);
        sql.append(" FOR UPDATE");
        return one(sql, UserAccount.class);
    }

    @Override
    public int insertHr(UserAccount user) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_user(username,real_name,phone,password_hash,employee_no,dept_id, source,employment_type,status,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count) VALUES(");
        sql.param(property(user, "username"));
        sql.append(",");
        sql.param(property(user, "realName"));
        sql.append(",");
        sql.param(property(user, "phone"));
        sql.append(",");
        sql.param(property(user, "passwordHash"));
        sql.append(",");
        sql.param(property(user, "employeeNo"));
        sql.append(",");
        sql.param(property(user, "deptId"));
        sql.append(", 1,");
        sql.param(property(user, "employmentType"));
        sql.append(",1,0,1,1,");
        sql.param(property(user, "hrVersion"));
        sql.append(",0) ");
        int changed = update(sql);
        setProperty(user, "id", lastInsertId());
        return changed;
    }

    @Override
    public int updateHr(UserAccount user) {
        var sql = new Sql();
        sql.append(" UPDATE sys_user SET real_name=");
        sql.param(property(user, "realName"));
        sql.append(",phone=");
        sql.param(property(user, "phone"));
        sql.append(", dept_id=");
        sql.param(property(user, "deptId"));
        sql.append(",employment_type=");
        sql.param(property(user, "employmentType"));
        sql.append(",status=");
        sql.param(property(user, "status"));
        sql.append(",session_version=");
        sql.param(property(user, "sessionVersion"));
        sql.append(", hr_version=");
        sql.param(property(user, "hrVersion"));
        sql.append(" WHERE id=");
        sql.param(property(user, "id"));
        sql.append(" AND source=1 AND BINARY employee_no=BINARY ");
        sql.param(property(user, "employeeNo"));
        sql.append(" ");
        return update(sql);
    }

    @Override
    public int insertTemporary(UserAccount user) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_user(username,real_name,phone,password_hash,dept_id,source,status, password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,created_by,temp_reason) VALUES(");
        sql.param(property(user, "username"));
        sql.append(",");
        sql.param(property(user, "realName"));
        sql.append(",");
        sql.param(property(user, "phone"));
        sql.append(",");
        sql.param(property(user, "passwordHash"));
        sql.append(",");
        sql.param(property(user, "deptId"));
        sql.append(",2,1,0,1,1,0,0,");
        sql.param(property(user, "createdBy"));
        sql.append(",");
        sql.param(property(user, "tempReason"));
        sql.append(") ");
        int changed = update(sql);
        setProperty(user, "id", lastInsertId());
        return changed;
    }

    @Override
    public int updatePassword(UserAccount user) {
        var sql = new Sql();
        sql.append("UPDATE sys_user SET password_hash=");
        sql.param(property(user, "passwordHash"));
        sql.append(",password_initialized=");
        sql.param(property(user, "passwordInitialized"));
        sql.append(", session_version=");
        sql.param(property(user, "sessionVersion"));
        sql.append(",pwd_failed_count=0,pwd_locked_until=NULL WHERE id=");
        sql.param(property(user, "id"));
        return update(sql);
    }

    @Override
    public int updateLoginState(UserAccount user) {
        var sql = new Sql();
        sql.append("UPDATE sys_user SET pwd_failed_count=");
        sql.param(property(user, "pwdFailedCount"));
        sql.append(",pwd_locked_until=");
        sql.param(property(user, "pwdLockedUntil"));
        sql.append(",last_login_at=");
        sql.param(property(user, "lastLoginAt"));
        sql.append(" WHERE id=");
        sql.param(property(user, "id"));
        return update(sql);
    }

    @Override
    public int updateStatus(UserAccount user) {
        var sql = new Sql();
        sql.append("UPDATE sys_user SET status=");
        sql.param(property(user, "status"));
        sql.append(",session_version=");
        sql.param(property(user, "sessionVersion"));
        sql.append(" WHERE id=");
        sql.param(property(user, "id"));
        sql.append(" AND source=2");
        return update(sql);
    }

    @Override
    public Long lockEligibleDepartment(long id) {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_dept WHERE id=");
        sql.param(id);
        sql.append(" AND merged_into_dept_id IS NULL FOR UPDATE");
        return one(sql, Long.class);
    }

    @Override
    public List<UserAccount> list(Set<Long> scope, Long deptId, String keyword, String phoneLookup, Integer accountStatus, Integer source, long offset, int limit) {
        var sql = new Sql();
        sql.append("SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(" FROM sys_user ");
        sql.append(" WHERE ");
        if (scope != null && !scope.isEmpty()) {
            sql.append(" dept_id IN ");
            sql.append("(");
            boolean firstdept1 = true;
            for (var dept : scope) {
                if (!firstdept1) sql.append(",");
                firstdept1 = false;
                sql.param(dept);
            }
            sql.append(")");
        }
        else {
            sql.append("1=0");
        }
        if (deptId != null) {
            sql.append("AND dept_id=");
            sql.param(deptId);
        }
        if (accountStatus != null) {
            sql.append("AND status=");
            sql.param(accountStatus);
        }
        if (source != null) {
            sql.append("AND source=");
            sql.param(source);
        }
        if (keyword != null) {
            sql.append("AND ");
            sql.append("(LOCATE(");
            sql.param(keyword);
            sql.append(",username)>0 OR COALESCE(LOCATE(");
            sql.param(keyword);
            sql.append(",employee_no),0)>0 ");
            if (phoneLookup != null) {
                sql.append("OR phone=");
                sql.param(phoneLookup);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY id LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        return list(sql, UserAccount.class);
    }

    @Override
    public int count(Set<Long> scope, Long deptId, String keyword, String phoneLookup, Integer accountStatus, Integer source) {
        var sql = new Sql();
        sql.append("SELECT COUNT(*) FROM sys_user ");
        sql.append(" WHERE ");
        if (scope != null && !scope.isEmpty()) {
            sql.append(" dept_id IN ");
            sql.append("(");
            boolean firstdept2 = true;
            for (var dept : scope) {
                if (!firstdept2) sql.append(",");
                firstdept2 = false;
                sql.param(dept);
            }
            sql.append(")");
        }
        else {
            sql.append("1=0");
        }
        if (deptId != null) {
            sql.append("AND dept_id=");
            sql.param(deptId);
        }
        if (accountStatus != null) {
            sql.append("AND status=");
            sql.param(accountStatus);
        }
        if (source != null) {
            sql.append("AND source=");
            sql.param(source);
        }
        if (keyword != null) {
            sql.append("AND ");
            sql.append("(LOCATE(");
            sql.param(keyword);
            sql.append(",username)>0 OR COALESCE(LOCATE(");
            sql.param(keyword);
            sql.append(",employee_no),0)>0 ");
            if (phoneLookup != null) {
                sql.append("OR phone=");
                sql.param(phoneLookup);
            }
            sql.append(")");
        }
        return one(sql, int.class);
    }

    @Override
    public List<UserAccount> searchCandidates(Set<Long> scope, Long deptId, String keyword, String phoneLookup, Integer accountStatus, Integer source, long afterId, int limit) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,username,real_name,phone,password_hash,employee_no,dept_id,status,source,employment_type,password_initialized,session_version,grant_revision,hr_version,pwd_failed_count,pwd_locked_until,created_by,temp_reason,last_login_at,created_at,updated_at");
        sql.append(",");
        sql.append("(LOCATE(");
        sql.param(keyword);
        sql.append(",username)>0 OR COALESCE(LOCATE(");
        sql.param(keyword);
        sql.append(",employee_no),0)>0 ");
        if (phoneLookup != null) {
            sql.append("OR phone=");
            sql.param(phoneLookup);
        }
        sql.append(")");
        sql.append(" AS identifier_matched FROM sys_user ");
        sql.append(" WHERE ");
        if (scope != null && !scope.isEmpty()) {
            sql.append(" dept_id IN ");
            sql.append("(");
            boolean firstdept3 = true;
            for (var dept : scope) {
                if (!firstdept3) sql.append(",");
                firstdept3 = false;
                sql.param(dept);
            }
            sql.append(")");
        }
        else {
            sql.append("1=0");
        }
        if (deptId != null) {
            sql.append("AND dept_id=");
            sql.param(deptId);
        }
        if (accountStatus != null) {
            sql.append("AND status=");
            sql.param(accountStatus);
        }
        if (source != null) {
            sql.append("AND source=");
            sql.param(source);
        }
        sql.append(" AND id>");
        sql.param(afterId);
        sql.append(" ORDER BY id LIMIT ");
        sql.param(limit);
        sql.append(" ");
        return list(sql, UserAccount.class);
    }

}
