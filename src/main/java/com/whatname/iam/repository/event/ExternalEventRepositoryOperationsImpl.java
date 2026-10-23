package com.whatname.iam.repository.event;

import java.time.LocalDateTime;
import java.util.Map;
import com.whatname.iam.repository.support.JpaSqlSupport;
import org.springframework.transaction.annotation.Transactional;

/** Explicit SQL preserves MySQL locking, revision checks and database defaults. */
@Transactional
public class ExternalEventRepositoryOperationsImpl extends JpaSqlSupport implements ExternalEventRepositoryOperations {
    @Override
    public Map<String, Object> lock(String source, String event) {
        var sql = new Sql();
        sql.append("SELECT id,event_type AS eventType,target_type AS targetType,target_ref AS targetRef,payload_digest AS digest,processing_status AS status,result_data AS result,result_summary AS summary,payload_json AS payloadJson FROM sys_external_event WHERE source_system=");
        sql.param(source);
        sql.append(" AND event_id=");
        sql.param(event);
        sql.append(" FOR UPDATE");
        return (Map<String, Object>) (Object) one(sql, Map.class);
    }

    @Override
    public int insert(String source, String event, String type, Long version, String targetType, String target, String digest, String payloadJson, LocalDateTime occurredAt) {
        var sql = new Sql();
        sql.append("INSERT INTO sys_external_event(source_system,event_id,event_type,event_version,target_type,target_ref,payload_digest,payload_json,occurred_at) VALUES(");
        sql.param(source);
        sql.append(",");
        sql.param(event);
        sql.append(",");
        sql.param(type);
        sql.append(",");
        sql.param(version);
        sql.append(",");
        sql.param(targetType);
        sql.append(",");
        sql.param(target);
        sql.append(",");
        sql.param(digest);
        sql.append(",");
        sql.param(payloadJson);
        sql.append(",");
        sql.param(occurredAt);
        sql.append(")");
        return update(sql);
    }

    @Override
    public int applied(long id, String result) {
        var sql = new Sql();
        sql.append("UPDATE sys_external_event SET processing_status='APPLIED',processing_stage='COMPLETED',result_data=");
        sql.param(result);
        sql.append(",result_summary='OK' WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status='PROCESSING'");
        return update(sql);
    }

    @Override
    public int failed(long id, String status, String code) {
        var sql = new Sql();
        sql.append("UPDATE sys_external_event SET processing_status=");
        sql.param(status);
        sql.append(",result_summary=");
        sql.param(code);
        sql.append(" WHERE id=");
        sql.param(id);
        sql.append(" AND processing_status='PROCESSING'");
        return update(sql);
    }

}
