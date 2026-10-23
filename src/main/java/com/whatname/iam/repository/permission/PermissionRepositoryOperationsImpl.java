package com.whatname.iam.repository.permission;

import java.util.List;
import java.util.Map;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class PermissionRepositoryOperationsImpl extends JpaSqlSupport implements PermissionRepositoryOperations {
    @Override
    public Long applicationId(String code) {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_application WHERE app_code=");
        sql.param(code);
        return one(sql, Long.class);
    }

    @Override
    public List<Map<String, Object>> menus(long id) {
        var sql = new Sql();
        sql.append("SELECT id,app_id,parent_id,menu_code,menu_name,menu_type,path,icon,sort_order,status,api_revision FROM sys_menu WHERE app_id=");
        sql.param(id);
        sql.append(" ORDER BY sort_order,id");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public List<Map<String, Object>> apis(long id) {
        var sql = new Sql();
        sql.append("SELECT id,app_id,api_code,api_path,http_method,status FROM sys_api WHERE app_id=");
        sql.param(id);
        sql.append(" ORDER BY id");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public int invalidated(String jti) {
        var sql = new Sql();
        sql.append("SELECT COUNT(*) FROM sys_session_invalidation WHERE jti=");
        sql.param(jti);
        return one(sql, int.class);
    }

}
