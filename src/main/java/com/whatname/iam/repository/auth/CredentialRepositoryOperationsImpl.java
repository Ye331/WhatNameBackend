package com.whatname.iam.repository.auth;

import com.whatname.iam.entity.auth.*;
import java.time.LocalDateTime;
import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class CredentialRepositoryOperationsImpl extends JpaSqlSupport implements CredentialRepositoryOperations {
    @Override
    public SmsCode lockLatestCode(String phoneLookup, String purpose) {
        var sql = new Sql();
        sql.append("SELECT id,phone,purpose,code_hash,expires_at,attempts,consumed_at,created_at FROM sys_sms_code WHERE phone=");
        sql.param(phoneLookup);
        sql.append(" AND purpose=");
        sql.param(purpose);
        sql.append(" ORDER BY id DESC LIMIT 1 FOR UPDATE");
        return one(sql, SmsCode.class);
    }

    @Override
    public int invalidateCodes(String phoneLookup, String purpose, LocalDateTime now) {
        var sql = new Sql();
        sql.append("UPDATE sys_sms_code SET consumed_at=");
        sql.param(now);
        sql.append(" WHERE phone=");
        sql.param(phoneLookup);
        sql.append(" AND purpose=");
        sql.param(purpose);
        sql.append(" AND consumed_at IS NULL");
        return update(sql);
    }

    @Override
    public int insertCode(SmsCode code) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_sms_code(phone,purpose,code_hash,expires_at,attempts,created_at) VALUES(");
        sql.param(property(code, "phone"));
        sql.append(",");
        sql.param(property(code, "purpose"));
        sql.append(",");
        sql.param(property(code, "codeHash"));
        sql.append(",");
        sql.param(property(code, "expiresAt"));
        sql.append(",0,");
        sql.param(property(code, "createdAt"));
        sql.append(")");
        int changed = update(sql);
        setProperty(code, "id", lastInsertId());
        return changed;
    }

    @Override
    public int failCode(long id, LocalDateTime now) {
        var sql = new Sql();
        sql.append("UPDATE sys_sms_code SET consumed_at=CASE WHEN attempts+1>=5 THEN ");
        sql.param(now);
        sql.append(" ELSE consumed_at END,attempts=attempts+1 WHERE id=");
        sql.param(id);
        sql.append(" AND attempts<5 AND consumed_at IS NULL");
        return update(sql);
    }

    @Override
    public int consumeCode(long id, LocalDateTime now) {
        var sql = new Sql();
        sql.append("UPDATE sys_sms_code SET consumed_at=");
        sql.param(now);
        sql.append(" WHERE id=");
        sql.param(id);
        sql.append(" AND consumed_at IS NULL AND attempts<5 AND expires_at>");
        sql.param(now);
        return update(sql);
    }

    @Override
    public List<PasswordHistory> recentHistory(long userId) {
        var sql = new Sql();
        sql.append("SELECT id,user_id,password_hash,created_at FROM sys_password_history WHERE user_id=");
        sql.param(userId);
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT 5");
        return list(sql, PasswordHistory.class);
    }

    @Override
    public int insertHistory(PasswordHistory history) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_password_history(user_id,password_hash) VALUES(");
        sql.param(property(history, "userId"));
        sql.append(",");
        sql.param(property(history, "passwordHash"));
        sql.append(")");
        int changed = update(sql);
        setProperty(history, "id", lastInsertId());
        return changed;
    }

    @Override
    public int trimHistory(long userId) {
        var sql = new Sql();
        sql.append("DELETE FROM sys_password_history WHERE user_id=");
        sql.param(userId);
        sql.append(" AND id NOT IN (SELECT id FROM (SELECT id FROM sys_password_history WHERE user_id=");
        sql.param(userId);
        sql.append(" ORDER BY created_at DESC,id DESC LIMIT 5) latest)");
        return update(sql);
    }

    @Override
    public SessionInvalidation findInvalidation(String jti) {
        var sql = new Sql();
        sql.append("SELECT id,user_id,jti,reason,expires_at,created_at FROM sys_session_invalidation WHERE jti=");
        sql.param(jti);
        return one(sql, SessionInvalidation.class);
    }

    @Override
    public int insertInvalidation(SessionInvalidation invalidation) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_session_invalidation(user_id,jti,reason,expires_at) VALUES(");
        sql.param(property(invalidation, "userId"));
        sql.append(",");
        sql.param(property(invalidation, "jti"));
        sql.append(",'LOGOUT',");
        sql.param(property(invalidation, "expiresAt"));
        sql.append(")");
        int changed = update(sql);
        setProperty(invalidation, "id", lastInsertId());
        return changed;
    }

    @Override
    public int insertLoginLog(LoginLog log) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_login_log(user_id,username,login_type,login_ip,login_device,login_status,fail_reason) VALUES(");
        sql.param(property(log, "userId"));
        sql.append(",");
        sql.param(property(log, "username"));
        sql.append(",");
        sql.param(property(log, "loginType"));
        sql.append(",");
        sql.param(property(log, "loginIp"));
        sql.append(",");
        sql.param(property(log, "loginDevice"));
        sql.append(",");
        sql.param(property(log, "loginStatus"));
        sql.append(",");
        sql.param(property(log, "failReason"));
        sql.append(")");
        int changed = update(sql);
        setProperty(log, "id", lastInsertId());
        return changed;
    }

}
