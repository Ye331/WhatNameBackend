package com.whatname.iam.repository.grant;

import com.whatname.iam.entity.grant.BatchData.*;
import java.util.List;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class BatchRepositoryOperationsImpl extends JpaSqlSupport implements BatchRepositoryOperations {
    @Override
    public Batch byKey(long actor, String key) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,actor_user_id,idempotency_key,payload_digest,operation,role_id,reason,processing_status,total_count");
        sql.append(" FROM sys_grant_batch WHERE actor_user_id=");
        sql.param(actor);
        sql.append(" AND idempotency_key=");
        sql.param(key);
        sql.append(" ");
        return one(sql, Batch.class);
    }

    @Override
    public Batch batch(long id) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,actor_user_id,idempotency_key,payload_digest,operation,role_id,reason,processing_status,total_count");
        sql.append(" FROM sys_grant_batch WHERE id=");
        sql.param(id);
        sql.append(" ");
        return one(sql, Batch.class);
    }

    @Override
    public Batch lockBatch(long id) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,actor_user_id,idempotency_key,payload_digest,operation,role_id,reason,processing_status,total_count");
        sql.append(" FROM sys_grant_batch WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status IN ('PENDING','PROCESSING') FOR UPDATE NOWAIT ");
        return one(sql, Batch.class);
    }

    @Override
    public List<Item> pendingItems(long batch, int limit) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,batch_id,user_id,source_type,source_ref,source_dept_id,grant_source_id,processing_status,result_code,result_data,attempts");
        sql.append(" FROM sys_grant_batch_item WHERE batch_id=");
        sql.param(batch);
        sql.append(" AND processing_status='PENDING' ORDER BY user_id LIMIT ");
        sql.param(limit);
        sql.append(" ");
        return list(sql, Item.class);
    }

    @Override
    public List<Long> unfinished(long after, int limit) {
        var sql = new Sql();
        sql.append(" SELECT id FROM sys_grant_batch WHERE id>");
        sql.param(after);
        sql.append(" AND processing_status IN ('PENDING','PROCESSING') ORDER BY id LIMIT ");
        sql.param(limit);
        sql.append(" ");
        return list(sql, Long.class);
    }

    @Override
    public int reserve(long id) {
        var sql = new Sql();
        sql.append(" UPDATE sys_grant_batch_item SET attempts=attempts+1 WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status='PENDING' AND attempts<4 ");
        return update(sql);
    }

    @Override
    public List<Item> items(long batch) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,batch_id,user_id,source_type,source_ref,source_dept_id,grant_source_id,processing_status,result_code,result_data,attempts");
        sql.append(" FROM sys_grant_batch_item WHERE batch_id=");
        sql.param(batch);
        sql.append(" ORDER BY user_id ");
        return list(sql, Item.class);
    }

    @Override
    public Item lockItem(long id) {
        var sql = new Sql();
        sql.append(" SELECT ");
        sql.append("id,batch_id,user_id,source_type,source_ref,source_dept_id,grant_source_id,processing_status,result_code,result_data,attempts");
        sql.append(" FROM sys_grant_batch_item WHERE id=");
        sql.param(id);
        sql.append(" FOR UPDATE ");
        return one(sql, Item.class);
    }

    @Override
    public int insertBatch(long actor, String key, String digest, String operation, long role, String reason, int total) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_grant_batch(actor_user_id,idempotency_key,payload_digest,operation,role_id,reason,total_count) VALUES(");
        sql.param(actor);
        sql.append(",");
        sql.param(key);
        sql.append(",");
        sql.param(digest);
        sql.append(",");
        sql.param(operation);
        sql.append(",");
        sql.param(role);
        sql.append(",");
        sql.param(reason);
        sql.append(",");
        sql.param(total);
        sql.append(") ");
        return update(sql);
    }

    @Override
    public int insertItems(List<Item> items) {
        var sql = new Sql();
        sql.append(" INSERT INTO sys_grant_batch_item(batch_id,user_id,source_type,source_ref,source_dept_id,grant_source_id) VALUES ");
        sql.append("");
        boolean firsti1 = true;
        for (var i : items) {
            if (!firsti1) sql.append(",");
            firsti1 = false;
            sql.append(" (");
            sql.param(property(i, "batchId"));
            sql.append(",");
            sql.param(property(i, "userId"));
            sql.append(",");
            sql.param(property(i, "sourceType"));
            sql.append(",");
            sql.param(property(i, "sourceRef"));
            sql.append(",");
            sql.param(property(i, "sourceDeptId"));
            sql.append(",");
            sql.param(property(i, "grantSourceId"));
            sql.append(") ");
        }
        sql.append("");
        return update(sql);
    }

    @Override
    public int applied(long id, long source, String data) {
        var sql = new Sql();
        sql.append(" UPDATE sys_grant_batch_item SET processing_status='APPLIED',grant_source_id=");
        sql.param(source);
        sql.append(",result_code='OK', result_data=");
        sql.param(data);
        sql.append(" WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status='PENDING' ");
        return update(sql);
    }

    @Override
    public int failed(long id, String code) {
        var sql = new Sql();
        sql.append(" UPDATE sys_grant_batch_item SET processing_status='FAILED',result_code=");
        sql.param(code);
        sql.append(" WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status='PENDING' ");
        return update(sql);
    }

    @Override
    public int start(long id) {
        var sql = new Sql();
        sql.append(" UPDATE sys_grant_batch SET processing_status='PROCESSING' WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status='PENDING' ");
        return update(sql);
    }

    @Override
    public int refresh(long id) {
        var sql = new Sql();
        sql.append(" UPDATE sys_grant_batch b JOIN ( SELECT batch_id, SUM(processing_status='PENDING') pending, SUM(processing_status='APPLIED') succeeded, SUM(processing_status='FAILED') failed FROM sys_grant_batch_item WHERE batch_id=");
        sql.param(id);
        sql.append(" GROUP BY batch_id ) i ON b.id=i.batch_id SET b.processing_status=CASE WHEN i.pending>0 THEN 'PROCESSING' WHEN i.failed=0 THEN 'COMPLETED' WHEN i.succeeded=0 THEN 'FAILED' ELSE 'PARTIAL_SUCCESS' END WHERE b.id=");
        sql.param(id);
        sql.append(" ");
        return update(sql);
    }

}
