package com.whatname.iam.repository.resource;

import com.whatname.iam.entity.resource.ApiEntity;
import com.whatname.iam.entity.resource.ApplicationEntity;
import com.whatname.iam.entity.resource.MenuEntity;
import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class ResourceRepositoryOperationsImpl extends JpaSqlSupport implements ResourceRepositoryOperations {
    @Override
    public int countApplications() {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_application ");
        return one(sql, int.class);
    }

    @Override
    public List<ApplicationEntity> listApplications(long offset, int limit) {
        var sql = new Sql();
        sql.append(" SELECT id, app_code, app_name, app_desc, status FROM sys_application ORDER BY id DESC LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        sql.append(" ");
        return list(sql, ApplicationEntity.class);
    }

    @Override
    public int countApplicationCode(String appCode, Long excludeId) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_application WHERE app_code = ");
        sql.param(appCode);
        sql.append(" ");
        if (excludeId != null) {
            sql.append(" AND id != ");
            sql.param(excludeId);
            sql.append(" ");
        }
        return one(sql, int.class);
    }

    @Override
    public int insertApplication(ApplicationEntity entity) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_application(app_code, app_name, app_desc, status) VALUES(");
        sql.param(property(entity, "appCode"));
        sql.append(", ");
        sql.param(property(entity, "appName"));
        sql.append(", ");
        sql.param(property(entity, "appDesc"));
        sql.append(", ");
        sql.param(property(entity, "status"));
        sql.append(") ");
        int changed = update(sql);
        setProperty(entity, "id", lastInsertId());
        return changed;
    }

    @Override
    public ApplicationEntity findApplicationById(long id) {
        var sql = new Sql();
        sql.append(" SELECT id, app_code, app_name, app_desc, status FROM sys_application WHERE id = ");
        sql.param(id);
        sql.append(" ");
        return one(sql, ApplicationEntity.class);
    }

    @Override
    public ApplicationEntity lockApplicationById(long id) {
        var sql = new Sql();
        sql.append(" SELECT id, app_code, app_name, app_desc, status FROM sys_application WHERE id = ");
        sql.param(id);
        sql.append(" FOR UPDATE ");
        return one(sql, ApplicationEntity.class);
    }

    @Override
    public int updateApplication(ApplicationEntity entity) {
        var sql = new Sql();
        sql.append(" UPDATE sys_application ");
        sql.append(" SET ");
        if (property(entity, "appName") != null) {
            sql.append("app_name = ");
            sql.param(property(entity, "appName"));
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
    public List<MenuEntity> listMenusByAppId(long appId) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, parent_id, menu_code, menu_name, menu_type, path, api_revision, status FROM sys_menu WHERE app_id = ");
        sql.param(appId);
        sql.append(" ORDER BY sort_order ASC, id ASC ");
        return list(sql, MenuEntity.class);
    }

    @Override
    public int countMenuCode(long appId, String menuCode, Long excludeId) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_menu WHERE app_id = ");
        sql.param(appId);
        sql.append(" AND menu_code = ");
        sql.param(menuCode);
        sql.append(" ");
        if (excludeId != null) {
            sql.append(" AND id != ");
            sql.param(excludeId);
            sql.append(" ");
        }
        return one(sql, int.class);
    }

    @Override
    public int insertMenu(MenuEntity entity) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_menu(app_id, parent_id, menu_code, menu_name, menu_type, path, api_revision, status) VALUES(");
        sql.param(property(entity, "appId"));
        sql.append(", ");
        sql.param(property(entity, "parentId"));
        sql.append(", ");
        sql.param(property(entity, "menuCode"));
        sql.append(", ");
        sql.param(property(entity, "menuName"));
        sql.append(", ");
        sql.param(property(entity, "menuType"));
        sql.append(", ");
        sql.param(property(entity, "path"));
        sql.append(", ");
        sql.param(property(entity, "apiRevision"));
        sql.append(", ");
        sql.param(property(entity, "status"));
        sql.append(") ");
        int changed = update(sql);
        setProperty(entity, "id", lastInsertId());
        return changed;
    }

    @Override
    public MenuEntity findMenuById(long id) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, parent_id, menu_code, menu_name, menu_type, path, api_revision, status FROM sys_menu WHERE id = ");
        sql.param(id);
        sql.append(" ");
        return one(sql, MenuEntity.class);
    }

    @Override
    public int updateMenu(MenuEntity entity) {
        var sql = new Sql();
        sql.append(" UPDATE sys_menu ");
        sql.append(" SET ");
        if (property(entity, "parentId") != null) {
            sql.append("parent_id = ");
            sql.param(property(entity, "parentId"));
            sql.append(",");
        }
        if (property(entity, "status") != null) {
            sql.append("status = ");
            sql.param(property(entity, "status"));
            sql.append(",");
        }
        if (property(entity, "menuName") != null) {
            sql.append("menu_name = ");
            sql.param(property(entity, "menuName"));
            sql.append(",");
        }
        sql.trimComma();
        sql.append(" WHERE id = ");
        sql.param(property(entity, "id"));
        sql.append(" ");
        return update(sql);
    }

    @Override
    public long countApisByApp(long appId, String httpMethod) {
        var sql = new Sql();
        sql.append(" SELECT COUNT(*) FROM sys_api WHERE app_id = ");
        sql.param(appId);
        sql.append(" ");
        if (httpMethod != null) {
            sql.append(" AND http_method = ");
            sql.param(httpMethod);
            sql.append(" ");
        }
        return one(sql, long.class);
    }

    @Override
    public List<ApiEntity> listApisByApp(long appId, String httpMethod, long offset, int limit) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, api_code, api_name, api_path, http_method, status FROM sys_api WHERE app_id = ");
        sql.param(appId);
        sql.append(" ");
        if (httpMethod != null) {
            sql.append(" AND http_method = ");
            sql.param(httpMethod);
            sql.append(" ");
        }
        sql.append(" ORDER BY id DESC LIMIT ");
        sql.param(limit);
        sql.append(" OFFSET ");
        sql.param(offset);
        sql.append(" ");
        return list(sql, ApiEntity.class);
    }

    @Override
    public int insertApi(ApiEntity entity) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_api(app_id, api_code, api_name, api_path, canonical_path, http_method, status) VALUES(");
        sql.param(property(entity, "appId"));
        sql.append(", ");
        sql.param(property(entity, "apiCode"));
        sql.append(", ");
        sql.param(property(entity, "apiName"));
        sql.append(", ");
        sql.param(property(entity, "apiPath"));
        sql.append(", ");
        sql.param(property(entity, "canonicalPath"));
        sql.append(", ");
        sql.param(property(entity, "httpMethod"));
        sql.append(", ");
        sql.param(property(entity, "status"));
        sql.append(") ");
        int changed = update(sql);
        setProperty(entity, "id", lastInsertId());
        return changed;
    }

    @Override
    public ApiEntity findApiById(long id) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, api_code, api_name, api_path, http_method, canonical_path, status FROM sys_api WHERE id = ");
        sql.param(id);
        sql.append(" ");
        return one(sql, ApiEntity.class);
    }

    @Override
    public int updateApi(ApiEntity entity) {
        var sql = new Sql();
        sql.append(" UPDATE sys_api ");
        sql.append(" SET ");
        if (property(entity, "apiName") != null) {
            sql.append("api_name = ");
            sql.param(property(entity, "apiName"));
            sql.append(",");
        }
        if (property(entity, "status") != null) {
            sql.append("status = ");
            sql.param(property(entity, "status"));
            sql.append(",");
        }
        if (property(entity, "apiPath") != null) {
            sql.append("api_path = ");
            sql.param(property(entity, "apiPath"));
            sql.append(",");
        }
        if (property(entity, "httpMethod") != null) {
            sql.append("http_method = ");
            sql.param(property(entity, "httpMethod"));
            sql.append(",");
        }
        sql.trimComma();
        sql.append(" WHERE id = ");
        sql.param(property(entity, "id"));
        sql.append(" ");
        return update(sql);
    }

    @Override
    public List<ApiEntity> listApisByAppAndMethod(long appId, String httpMethod) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, api_code, api_name, api_path, http_method, canonical_path, status FROM sys_api WHERE app_id = ");
        sql.param(appId);
        sql.append(" AND http_method = ");
        sql.param(httpMethod);
        sql.append(" ");
        return list(sql, ApiEntity.class);
    }

    @Override
    public List<Long> listApiIdsByMenuId(long menuId) {
        var sql = new Sql();
        sql.append(" SELECT api_id FROM sys_menu_api WHERE menu_id = ");
        sql.param(menuId);
        sql.append(" ORDER BY id ASC ");
        return list(sql, Long.class);
    }

    @Override
    public int deleteMenuApis(long menuId) {
        var sql = new Sql();
        sql.append(" DELETE FROM sys_menu_api WHERE menu_id = ");
        sql.param(menuId);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public int insertMenuApis(long menuId, List<Long> apiIds) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_menu_api(menu_id, api_id) VALUES ");
        sql.append("");
        boolean firstapiId1 = true;
        for (var apiId : apiIds) {
            if (!firstapiId1) sql.append(",");
            firstapiId1 = false;
            sql.append(" (");
            sql.param(menuId);
            sql.append(", ");
            sql.param(apiId);
            sql.append(") ");
        }
        sql.append("");
        return update(sql);
    }

    @Override
    public int bumpMenuApiRevision(long menuId, int expectedRevision) {
        var sql = new Sql();
        sql.append(" UPDATE sys_menu SET api_revision = api_revision + 1 WHERE id = ");
        sql.param(menuId);
        sql.append(" AND api_revision = ");
        sql.param(expectedRevision);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public List<MenuEntity> listMenusByIds(List<Long> menuIds) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, parent_id, menu_code, menu_name, menu_type, path, api_revision, status FROM sys_menu WHERE id IN ");
        sql.append("(");
        boolean firstmenuId1 = true;
        for (var menuId : menuIds) {
            if (!firstmenuId1) sql.append(",");
            firstmenuId1 = false;
            sql.append(" ");
            sql.param(menuId);
            sql.append(" ");
        }
        sql.append(")");
        return list(sql, MenuEntity.class);
    }

    @Override
    public List<ApiEntity> listApisByIds(List<Long> apiIds) {
        var sql = new Sql();
        sql.append(" SELECT id, app_id, api_code, api_name, api_path, http_method, canonical_path, status FROM sys_api WHERE id IN ");
        sql.append("(");
        boolean firstapiId2 = true;
        for (var apiId : apiIds) {
            if (!firstapiId2) sql.append(",");
            firstapiId2 = false;
            sql.append(" ");
            sql.param(apiId);
            sql.append(" ");
        }
        sql.append(")");
        return list(sql, ApiEntity.class);
    }

}
