package com.whatname.iam.mapper.event;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface ExternalEventMapper {
    @Select("SELECT id,payload_digest AS digest,processing_status AS status,result_data AS result,result_summary AS summary,claim_token AS token,payload_ciphertext AS ciphertext FROM sys_external_event WHERE source_system=#{source} AND event_id=#{event} FOR UPDATE")
    Map<String,Object> lock(@Param("source") String source,@Param("event") String event);
    @Insert("INSERT INTO sys_external_event(source_system,event_id,event_type,event_version,target_type,target_ref,payload_digest,payload_ciphertext,occurred_at,claim_token) VALUES(#{source},#{event},#{type},#{version},#{targetType},#{target},#{digest},#{ciphertext},#{occurredAt},#{token})")
    int insert(@Param("source") String source,@Param("event") String event,@Param("type") String type,
            @Param("version") Long version,@Param("targetType") String targetType,@Param("target") String target,
            @Param("digest") String digest,@Param("ciphertext") String ciphertext,
            @Param("occurredAt") LocalDateTime occurredAt,@Param("token") String token);
    @Update("UPDATE sys_external_event SET processing_status='APPLIED',processing_stage='COMPLETED',result_data=#{result},result_summary='OK' WHERE id=#{id} AND claim_token=#{token} AND processing_status='PROCESSING'")
    int applied(@Param("id") long id,@Param("token") String token,@Param("result") String result);
    @Update("UPDATE sys_external_event SET processing_status=#{status},result_summary=#{code} WHERE id=#{id} AND claim_token=#{token} AND processing_status='PROCESSING'")
    int failed(@Param("id") long id,@Param("token") String token,@Param("status") String status,@Param("code") String code);
    @Update("UPDATE sys_external_event SET processing_status='PROCESSING',processing_stage='RECEIVED',claim_token=#{token},result_summary='CONTROLLED_RECOVERY' WHERE id=#{id} AND processing_status IN ('PROCESSING','PENDING_RECOVERY')")
    int recover(@Param("id") long id,@Param("token") String token);
}
