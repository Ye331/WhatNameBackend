package com.whatname.iam.repository.service;

import java.time.LocalDateTime;
import java.util.Map;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class ServiceIdentityRepositoryOperationsImpl extends JpaSqlSupport implements ServiceIdentityRepositoryOperations {
    @Override
    public Map<String, Object> find(String clientId) {
        var sql = new Sql();
        sql.append("SELECT id, app_id, client_id, credential_hash, public_key, key_id, allowed_role_ids, allowed_dept_ids, allowed_operations, allowed_scope, status FROM sys_service_identity WHERE client_id = ");
        sql.param(clientId);
        return (Map<String, Object>) (Object) one(sql, Map.class);
    }

    @Override
    public int insertNonce(String clientId, String nonce, LocalDateTime expiresAt) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_service_nonce (client_id, nonce, expires_at) VALUES (");
        sql.param(clientId);
        sql.append(", ");
        sql.param(nonce);
        sql.append(", ");
        sql.param(expiresAt);
        sql.append(")");
        return update(sql);
    }

    @Override
    public int deleteExpiredNonces(LocalDateTime now) {
        var sql = new Sql();
        sql.append("DELETE FROM sys_service_nonce WHERE expires_at < ");
        sql.param(now);
        return update(sql);
    }

}
