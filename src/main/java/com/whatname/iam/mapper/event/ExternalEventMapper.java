package com.whatname.iam.mapper.event;

import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.*;

public interface ExternalEventMapper {
    @Select("SELECT id,payload_digest AS digest,processing_status AS status,result_data AS result,result_summary AS summary,payload_ciphertext AS ciphertext FROM sys_external_event WHERE source_system=#{source} AND event_id=#{event} FOR UPDATE")
    Map<String,Object> lock(@Param("source") String source,@Param("event") String event);
    @Insert("INSERT INTO sys_external_event(source_system,event_id,event_type,event_version,target_type,target_ref,payload_digest,payload_ciphertext,occurred_at) VALUES(#{source},#{event},#{type},#{version},#{targetType},#{target},#{digest},#{ciphertext},#{occurredAt})")
    int insert(@Param("source") String source,@Param("event") String event,@Param("type") String type,
            @Param("version") Long version,@Param("targetType") String targetType,@Param("target") String target,
            @Param("digest") String digest,@Param("ciphertext") String ciphertext,
            @Param("occurredAt") LocalDateTime occurredAt);
    @Update("UPDATE sys_external_event SET processing_status='APPLIED',processing_stage='COMPLETED',result_data=#{result},result_summary='OK' WHERE id=#{id} AND processing_status='PROCESSING'")
    int applied(@Param("id") long id,@Param("result") String result);
    @Update("UPDATE sys_external_event SET processing_status=#{status},result_summary=#{code} WHERE id=#{id} AND processing_status='PROCESSING'")
    int failed(@Param("id") long id,@Param("status") String status,@Param("code") String code);
}
