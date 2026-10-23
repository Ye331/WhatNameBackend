package com.whatname.iam.repository.grant;

import com.whatname.iam.entity.grant.GrantData.*;
import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class GrantRepositoryOperationsImpl extends JpaSqlSupport implements GrantRepositoryOperations {
    @Override
    public int insertOaSource(long userId, long roleId, String approvalId) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id,status,granted_by) VALUES(");
        sql.param(userId);
        sql.append(",");
        sql.param(roleId);
        sql.append(",'OA_APPROVAL',");
        sql.param(approvalId);
        sql.append(",NULL,1,NULL) ");
        return update(sql);
    }

    @Override
    public int insertOaEffectiveRole(long userId, long roleId) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(");
        sql.param(userId);
        sql.append(",");
        sql.param(roleId);
        sql.append(",NULL) ");
        return update(sql);
    }

    @Override
    public int revokeOaSource(long userId, long roleId, long sourceId) {
        var sql = new Sql();
        sql.append(" UPDATE sys_user_role_source SET status=0,revoked_by=NULL,revoked_at=CURRENT_TIMESTAMP WHERE id=");
        sql.param(sourceId);
        sql.append(" AND user_id=");
        sql.param(userId);
        sql.append(" AND role_id=");
        sql.param(roleId);
        sql.append(" AND source_type='OA_APPROVAL' AND status=1 ");
        return update(sql);
    }

    @Override
    public Long selectRevision(long userId, boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT grant_revision FROM sys_user WHERE id=");
        sql.param(userId);
        sql.append(" ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return one(sql, Long.class);
    }

    @Override
    public List<Source> selectSources(long userId, boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id, user_id, role_id, source_type, source_ref, source_dept_id, status, granted_by, revoked_by, revoked_at, created_at");
        sql.append(" FROM sys_user_role_source WHERE user_id=");
        sql.param(userId);
        sql.append(" ORDER BY id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, Source.class);
    }

    @Override
    public List<EffectiveRole> selectEffectiveRoles(long userId, boolean lock) {
        var sql = new Sql();
        sql.append(" SELECT id, user_id, role_id, granted_by, granted_at FROM sys_user_role WHERE user_id=");
        sql.param(userId);
        sql.append(" ORDER BY role_id ");
        if (lock) {
            sql.append("FOR UPDATE");
        }
        return list(sql, EffectiveRole.class);
    }

    @Override
    public Source selectSourceByKey(long userId, long roleId, String type, String ref) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id, user_id, role_id, source_type, source_ref, source_dept_id, status, granted_by, revoked_by, revoked_at, created_at");
        sql.append(" FROM sys_user_role_source WHERE user_id=");
        sql.param(userId);
        sql.append(" AND role_id=");
        sql.param(roleId);
        sql.append(" AND source_type=");
        sql.param(type);
        sql.append(" AND source_ref=");
        sql.param(ref);
        sql.append(" FOR UPDATE ");
        return one(sql, Source.class);
    }

    @Override
    public int insertSource(long userId, long roleId, String type, String ref, Long deptId, long actorId) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref,source_dept_id,status,granted_by) VALUES(");
        sql.param(userId);
        sql.append(",");
        sql.param(roleId);
        sql.append(",");
        sql.param(type);
        sql.append(",");
        sql.param(ref);
        sql.append(",");
        sql.param(deptId);
        sql.append(",1,");
        sql.param(actorId);
        sql.append(") ");
        return update(sql);
    }

    @Override
    public int insertEffectiveRole(long userId, long roleId, long actorId) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_user_role(user_id,role_id,granted_by) VALUES(");
        sql.param(userId);
        sql.append(",");
        sql.param(roleId);
        sql.append(",");
        sql.param(actorId);
        sql.append(") ");
        return update(sql);
    }

    @Override
    public int revokeSource(long userId, long roleId, long sourceId, long actorId) {
        var sql = new Sql();
        sql.append(" UPDATE sys_user_role_source SET status=0,revoked_by=");
        sql.param(actorId);
        sql.append(",revoked_at=CURRENT_TIMESTAMP WHERE id=");
        sql.param(sourceId);
        sql.append(" AND user_id=");
        sql.param(userId);
        sql.append(" AND role_id=");
        sql.param(roleId);
        sql.append(" AND status=1 ");
        return update(sql);
    }

    @Override
    public int revokeHrSource(long userId, long roleId, long sourceId) {
        var sql = new Sql();
        sql.append(" UPDATE sys_user_role_source SET status=0,revoked_by=NULL,revoked_at=CURRENT_TIMESTAMP WHERE id=");
        sql.param(sourceId);
        sql.append(" AND user_id=");
        sql.param(userId);
        sql.append(" AND role_id=");
        sql.param(roleId);
        sql.append(" AND status=1 ");
        return update(sql);
    }

    @Override
    public int deleteEffectiveRole(long userId, long roleId) {
        var sql = new Sql();
        sql.append(" DELETE FROM sys_user_role WHERE user_id=");
        sql.param(userId);
        sql.append(" AND role_id=");
        sql.param(roleId);
        sql.append(" ");
        return update(sql);
    }

    @Override
    public int advanceRevision(long userId, long expected) {
        var sql = new Sql();
        sql.append(" UPDATE sys_user SET grant_revision=grant_revision+1 WHERE id=");
        sql.param(userId);
        sql.append(" AND grant_revision=");
        sql.param(expected);
        sql.append(" AND grant_revision < 9223372036854775807 ");
        return update(sql);
    }

}
