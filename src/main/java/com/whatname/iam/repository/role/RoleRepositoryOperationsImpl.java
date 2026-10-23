package com.whatname.iam.repository.role;

import com.whatname.iam.entity.resource.MenuEntity;
import com.whatname.iam.entity.role.RoleEntity;
import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class RoleRepositoryOperationsImpl extends JpaSqlSupport implements RoleRepositoryOperations {
    @Override
    public int countRoles() {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_role ");
        return one(sql, int.class);
    }

    @Override
    public List<RoleEntity> listRoles(long offset, int limit) {
        var sql = new Sql();
        sql.append(" SELECT id, role_code, role_name, role_desc, role_type, menu_revision, status FROM sys_role ORDER BY id DESC LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        sql.append(" ");
        return list(sql, RoleEntity.class);
    }

    @Override
    public int countRoleCode(String roleCode, Long excludeId) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_role WHERE role_code = ");
        sql.param(roleCode);
        sql.append(" ");
        if (excludeId != null) {
            sql.append(" AND id != ");
            sql.param(excludeId);
            sql.append(" ");
        }
        return one(sql, int.class);
    }

    @Override
    public int insertRole(RoleEntity entity) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_role(role_code, role_name, role_desc, role_type, menu_revision, status) VALUES(");
        sql.param(property(entity, "roleCode"));
        sql.append(", ");
        sql.param(property(entity, "roleName"));
        sql.append(", ");
        sql.param(property(entity, "roleDesc"));
        sql.append(", ");
        sql.param(property(entity, "roleType"));
        sql.append(", ");
        sql.param(property(entity, "menuRevision"));
        sql.append(", ");
        sql.param(property(entity, "status"));
        sql.append(") ");
        int changed = update(sql);
        setProperty(entity, "id", lastInsertId());
        return changed;
    }

    @Override
    public RoleEntity findRoleById(long id) {
        var sql = new Sql();
        sql.append(" SELECT id, role_code, role_name, role_desc, role_type, menu_revision, status FROM sys_role WHERE id = ");
        sql.param(id);
        sql.append(" ");
        return one(sql, RoleEntity.class);
    }

    @Override
    public int updateRole(RoleEntity entity) {
        var sql = new Sql();
        sql.append(" UPDATE sys_role ");
        sql.append(" SET ");
        if (property(entity, "roleName") != null) {
            sql.append("role_name = ");
            sql.param(property(entity, "roleName"));
            sql.append(",");
        }
        if (property(entity, "status") != null) {
            sql.append("status = ");
            sql.param(property(entity, "status"));
            sql.append(",");
        }
        sql.trimComma();
        sql.append(" WHERE id = ");
        sql.param(property(entity, "id"));
        sql.append(" ");
        return update(sql);
    }

    @Override
    public List<Long> listMenuIdsByRoleId(long roleId) {
        var sql = new Sql();
        sql.append(" SELECT menu_id FROM sys_role_permission WHERE role_id = ");
        sql.param(roleId);
        sql.append(" ORDER BY id ASC ");
        return list(sql, Long.class);
    }

    @Override
    public int deleteRoleMenus(long roleId) {
        var sql = new Sql();
        sql.append(" DELETE FROM sys_role_permission WHERE role_id = ");
        sql.param(roleId);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public int insertRoleMenus(long roleId, List<Long> menuIds) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_role_permission(role_id, menu_id) VALUES ");
        sql.append("");
        boolean firstmenuId1 = true;
        for (var menuId : menuIds) {
            if (!firstmenuId1) sql.append(",");
            firstmenuId1 = false;
            sql.append(" (");
            sql.param(roleId);
            sql.append(", ");
            sql.param(menuId);
            sql.append(") ");
        }
        sql.append("");
        return update(sql);
    }

    @Override
    public int bumpRoleMenuRevision(long roleId, int expectedRevision) {
        var sql = new Sql();
        sql.append(" UPDATE sys_role SET menu_revision = menu_revision + 1 WHERE id = ");
        sql.param(roleId);
        sql.append(" AND menu_revision = ");
        sql.param(expectedRevision);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public List<MenuEntity> listMenusByIds(List<Long> menuIds) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, parent_id, menu_code, menu_name, menu_type, path, api_revision, status FROM sys_menu WHERE id IN ");
        sql.append("(");
        boolean firstmenuId2 = true;
        for (var menuId : menuIds) {
            if (!firstmenuId2) sql.append(",");
            firstmenuId2 = false;
            sql.append(" ");
            sql.param(menuId);
            sql.append(" ");
        }
        sql.append(")");
        return list(sql, MenuEntity.class);
    }

}
