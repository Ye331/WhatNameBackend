package com.whatname.iam.repository.audit;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class AuditRepositoryOperationsImpl extends JpaSqlSupport implements AuditRepositoryOperations {
    @Override
    public void insert(Map<String, Object> entry) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_operation_log(user_id,request_id,app_id,api_id,auth_at,auth_decision,auth_reason,evidence,source_system,external_event_id,operation_type,target_type,target_id,request_method,request_url,status) VALUES(");
        sql.param(property(entry, "actorId"));
        sql.append(",");
        sql.param(property(entry, "requestId"));
        sql.append(",");
        sql.param(property(entry, "appId"));
        sql.append(",");
        sql.param(property(entry, "apiId"));
        sql.append(",");
        sql.param(property(entry, "authAt"));
        sql.append(",");
        sql.param(property(entry, "allowed"));
        sql.append(",");
        sql.param(property(entry, "reason"));
        sql.append(",");
        sql.param(property(entry, "evidence"));
        sql.append(",");
        sql.param(property(entry, "source"));
        sql.append(",");
        sql.param(property(entry, "externalEventId"));
        sql.append(",");
        sql.param(property(entry, "operation"));
        sql.append(",");
        sql.param(property(entry, "targetType"));
        sql.append(",");
        sql.param(property(entry, "targetId"));
        sql.append(",");
        sql.param(property(entry, "method"));
        sql.append(",");
        sql.param(property(entry, "route"));
        sql.append(",");
        sql.param(property(entry, "status"));
        sql.append(")");
        update(sql);
    }

    @Override
    public void login(Long userId, int type, int success, String reason) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_login_log(user_id,login_type,login_status,fail_reason) VALUES(");
        sql.param(userId);
        sql.append(",");
        sql.param(type);
        sql.append(",");
        sql.param(success);
        sql.append(",");
        sql.param(reason);
        sql.append(")");
        update(sql);
    }

    @Override
    public List<Map<String, Object>> operations(Set<Long> scope, boolean global, boolean authorizations, Long userId, long offset, int limit) {
        var sql = new Sql();
        sql.append(" SELECT l.id,l.user_id,l.request_id,l.app_id,l.api_id,l.auth_decision,l.auth_reason,l.evidence, l.operation_type,l.target_type,l.target_id,l.created_at FROM sys_operation_log l ");
        sql.append(" WHERE ");
        if (authorizations) {
            sql.append("l.operation_type='AUTH_CHECK'");
        }
        else {
            sql.append("l.operation_type<>'AUTH_CHECK'");
        }
        if (userId != null) {
            sql.append("AND l.user_id=");
            sql.param(userId);
        }
        if (!global) {
            sql.append(" AND (l.user_id IS NULL OR EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.user_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept1 = true;
            for (var dept : scope) {
                if (!firstdept1) sql.append(",");
                firstdept1 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) AND ((l.target_type='USER' AND EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.target_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept2 = true;
            for (var dept : scope) {
                if (!firstdept2) sql.append(",");
                firstdept2 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) OR (l.target_type='DEPARTMENT' AND l.target_id IN ");
            sql.append("(");
            boolean firstdept3 = true;
            for (var dept : scope) {
                if (!firstdept3) sql.append(",");
                firstdept3 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) ");
        }
        sql.append(" ORDER BY l.id DESC LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        sql.append(" ");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public long operationCount(Set<Long> scope, boolean global, boolean authorizations, Long userId) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_operation_log l ");
        sql.append(" WHERE ");
        if (authorizations) {
            sql.append("l.operation_type='AUTH_CHECK'");
        }
        else {
            sql.append("l.operation_type<>'AUTH_CHECK'");
        }
        if (userId != null) {
            sql.append("AND l.user_id=");
            sql.param(userId);
        }
        if (!global) {
            sql.append(" AND (l.user_id IS NULL OR EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.user_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept4 = true;
            for (var dept : scope) {
                if (!firstdept4) sql.append(",");
                firstdept4 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) AND ((l.target_type='USER' AND EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.target_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept5 = true;
            for (var dept : scope) {
                if (!firstdept5) sql.append(",");
                firstdept5 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) OR (l.target_type='DEPARTMENT' AND l.target_id IN ");
            sql.append("(");
            boolean firstdept6 = true;
            for (var dept : scope) {
                if (!firstdept6) sql.append(",");
                firstdept6 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) ");
        }
        return one(sql, long.class);
    }

    @Override
    public List<Map<String, Object>> logins(Set<Long> scope, boolean global, Long userId, long offset, int limit) {
        var sql = new Sql();
        sql.append(" SELECT l.id,l.user_id,l.login_type,l.login_status,l.created_at FROM sys_login_log l ");
        sql.append(" WHERE 1=1 ");
        if (userId != null) {
            sql.append("AND l.user_id=");
            sql.param(userId);
        }
        if (!global) {
            sql.append("AND EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.user_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept7 = true;
            for (var dept : scope) {
                if (!firstdept7) sql.append(",");
                firstdept7 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")");
        }
        sql.append(" ORDER BY l.id DESC LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        sql.append(" ");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public long loginCount(Set<Long> scope, boolean global, Long userId) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_login_log l ");
        sql.append(" WHERE 1=1 ");
        if (userId != null) {
            sql.append("AND l.user_id=");
            sql.param(userId);
        }
        if (!global) {
            sql.append("AND EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.user_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept8 = true;
            for (var dept : scope) {
                if (!firstdept8) sql.append(",");
                firstdept8 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")");
        }
        return one(sql, long.class);
    }

    @Override
    public int visible(long id, Set<Long> scope, boolean global) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_operation_log l WHERE l.id=");
        sql.param(id);
        sql.append(" ");
        if (!global) {
            sql.append(" AND (l.user_id IS NULL OR EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.user_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept9 = true;
            for (var dept : scope) {
                if (!firstdept9) sql.append(",");
                firstdept9 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) AND ((l.target_type='USER' AND EXISTS(SELECT 1 FROM sys_user u WHERE u.id=l.target_id AND u.dept_id IN ");
            sql.append("(");
            boolean firstdept10 = true;
            for (var dept : scope) {
                if (!firstdept10) sql.append(",");
                firstdept10 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) OR (l.target_type='DEPARTMENT' AND l.target_id IN ");
            sql.append("(");
            boolean firstdept11 = true;
            for (var dept : scope) {
                if (!firstdept11) sql.append(",");
                firstdept11 = false;
                sql.param(dept);
            }
            sql.append(")");
            sql.append(")) ");
        }
        return one(sql, int.class);
    }

    @Override
    public Map<String, Object> operation(long id) {
        var sql = new Sql();
        sql.append("SELECT id,app_id,api_id,user_id,target_type,target_id,request_id,operation_type,evidence,auth_decision,auth_reason,created_at FROM sys_operation_log WHERE id=");
        sql.param(id);
        return (Map<String, Object>) (Object) one(sql, Map.class);
    }

}
