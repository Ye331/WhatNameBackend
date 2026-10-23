package com.whatname.iam.repository.admin;

import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class ResourceLockRepositoryOperationsImpl extends JpaSqlSupport implements ResourceLockRepositoryOperations {
    @Override
    public List<Long> applications() {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_application ORDER BY id FOR UPDATE");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> menus() {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_menu ORDER BY id FOR UPDATE");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> apis() {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_api ORDER BY id FOR UPDATE");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> roleMenus() {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_role_permission ORDER BY id FOR UPDATE");
        return list(sql, Long.class);
    }

    @Override
    public List<Long> menuApis() {
        var sql = new Sql();
        sql.append("SELECT id FROM sys_menu_api ORDER BY id FOR UPDATE");
        return list(sql, Long.class);
    }

}
