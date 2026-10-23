package com.whatname.iam.repository.oa;

import java.util.Map;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class OaScopeRepositoryOperationsImpl extends JpaSqlSupport implements OaScopeRepositoryOperations {
    @Override
    public Map<String, Object> lock(String clientId) {
        var sql = new Sql();
        sql.append("SELECT id,app_id,client_id,allowed_scope,allowed_role_ids,allowed_dept_ids,allowed_operations,status FROM sys_service_identity WHERE client_id=");
        sql.param(clientId);
        sql.append(" FOR UPDATE");
        return (Map<String, Object>) (Object) one(sql, Map.class);
    }

}
