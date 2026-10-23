package com.whatname.iam.repository.grant;

import java.util.*;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class GrantHistoryRepositoryOperationsImpl extends JpaSqlSupport implements GrantHistoryRepositoryOperations {
    @Override
    public List<Map<String, Object>> candidates(Long user, Long before, int limit) {
        var sql = new Sql();
        sql.append(" SELECT id,user_id,request_id,app_id,api_id,auth_decision,auth_reason,operation_type,target_type,target_id, source_system,external_event_id,status,evidence,created_at FROM sys_operation_log WHERE target_type='USER' AND target_id IS NOT NULL AND operation_type IN ('USER_ROLE_GRANT','USER_ROLE_REVOKE','HR_POSITION_REVOKE','HR_GRANTS_REVOKE_ALL','OA_DECISION') ");
        if (user != null) {
            sql.append("AND target_id=");
            sql.param(user);
        }
        if (before != null) {
            sql.append("AND id<");
            sql.param(before);
        }
        sql.append(" ORDER BY id DESC LIMIT ");
        sql.param(limit);
        sql.append(" ");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

}
