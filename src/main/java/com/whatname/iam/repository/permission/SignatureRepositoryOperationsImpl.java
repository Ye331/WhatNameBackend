package com.whatname.iam.repository.permission;

import com.whatname.iam.service.permission.SignedDataType;
import java.util.List;
import java.util.Map;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class SignatureRepositoryOperationsImpl extends JpaSqlSupport implements SignatureRepositoryOperations {
    @Override
    public Map<String, Object> record(SignedDataType type, long id) {
        var sql = new Sql();
        sql.append("SELECT " + type.fields + " FROM " + type.table + " WHERE " + type.idColumn);
        sql.append("=");
        sql.param(id);
        return (Map<String, Object>) (Object) one(sql, Map.class);
    }

    @Override
    public List<Map<String, Object>> records(SignedDataType type, List<Long> ids) {
        var sql = new Sql();
        sql.append("SELECT " + type.fields + " FROM " + type.table + " WHERE " + type.idColumn);
        if (ids.isEmpty()) return List.of();
        sql.append(" IN (");
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) sql.append(",");
            sql.param(ids.get(i));
        }
        sql.append(") ORDER BY " + type.idColumn);
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public List<Map<String, Object>> signatures(String type, List<Long> ids) {
        var sql = new Sql();
        sql.append("SELECT data_id,signature,sign_version,key_id FROM sys_data_signature WHERE data_type=");
        sql.param(type);
        sql.append(" AND data_id IN ");
        sql.append("(");
        boolean firstid1 = true;
        for (var id : ids) {
            if (!firstid1) sql.append(",");
            firstid1 = false;
            sql.param(id);
        }
        sql.append(")");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public List<Map<String, Object>> menuApiMembers(List<Long> ids) {
        var sql = new Sql();
        sql.append("SELECT menu_id,api_id FROM sys_menu_api WHERE menu_id IN ");
        sql.append("(");
        boolean firstid2 = true;
        for (var id : ids) {
            if (!firstid2) sql.append(",");
            firstid2 = false;
            sql.param(id);
        }
        sql.append(")");
        sql.append(" ORDER BY menu_id,api_id");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public List<Long> userRoles(long id) {
        var sql = new Sql();
        sql.append("SELECT role_id FROM sys_user_role WHERE user_id=");
        sql.param(id);
        sql.append(" ORDER BY role_id");
        return list(sql, Long.class);
    }

    @Override
    public List<Map<String, Object>> sources(long id) {
        var sql = new Sql();
        sql.append("SELECT id,role_id,source_type,source_ref,source_dept_id,status FROM sys_user_role_source WHERE user_id=");
        sql.param(id);
        sql.append(" AND status=1 ORDER BY id");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public List<Long> roleMenus(long id) {
        var sql = new Sql();
        sql.append("SELECT menu_id FROM sys_role_permission WHERE role_id=");
        sql.param(id);
        sql.append(" ORDER BY menu_id");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> menuApis(long id) {
        var sql = new Sql();
        sql.append("SELECT api_id FROM sys_menu_api WHERE menu_id=");
        sql.param(id);
        sql.append(" ORDER BY api_id");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> delegableRoles(long id) {
        var sql = new Sql();
        sql.append("SELECT role_id FROM sys_admin_delegable_role WHERE admin_user_id=");
        sql.param(id);
        sql.append(" ORDER BY role_id");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> applicationMenus(long id) {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_menu WHERE app_id=");
        sql.param(id);
        sql.append(" ORDER BY id");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> applicationApis(long id) {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_api WHERE app_id=");
        sql.param(id);
        sql.append(" ORDER BY id");
        return list(sql, Long.class);
    }

    @Override
    public List<Map<String, Object>> organization() {
        var sql = new Sql();
        sql.append("SELECT id,parent_id,dept_code,dept_name,dept_path,status,merged_into_dept_id,manager_id,hr_version FROM sys_dept ORDER BY id");
        return (List<Map<String, Object>>) (Object) list(sql, Map.class);
    }

    @Override
    public Map<String, Object> signature(String type, long id) {
        var sql = new Sql();
        sql.append("SELECT signature,sign_version,key_id FROM sys_data_signature WHERE data_type=");
        sql.param(type);
        sql.append(" AND data_id=");
        sql.param(id);
        return (Map<String, Object>) (Object) one(sql, Map.class);
    }

    @Override
    public void save(String type, long id, String signature, String keyId) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_data_signature(data_type,data_id,signature,sign_version,key_id) VALUES(");
        sql.param(type);
        sql.append(",");
        sql.param(id);
        sql.append(",");
        sql.param(signature);
        sql.append(",1,");
        sql.param(keyId);
        sql.append(") ON DUPLICATE KEY UPDATE signature=");
        sql.param(signature);
        sql.append(",sign_version=1,key_id=");
        sql.param(keyId);
        update(sql);
    }

}
