package com.whatname.iam.mapper.audit;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Mapper
public interface AuditMapper {
    @Insert(
            "INSERT INTO"
                + " sys_operation_log(user_id,request_id,app_id,api_id,auth_at,auth_decision,auth_reason,evidence,source_system,external_event_id,operation_type,target_type,target_id,request_method,request_url,status)"
                + " VALUES(#{actorId},#{requestId},#{appId},#{apiId},#{authAt},#{allowed},#{reason},#{evidence},#{source},#{externalEventId},#{operation},#{targetType},#{targetId},#{method},#{route},#{status})")
    void insert(Map<String, Object> entry);

    @Insert(
            "INSERT INTO sys_login_log(user_id,login_type,login_status,fail_reason)"
                + " VALUES(#{userId},#{type},#{success},#{reason})")
    void login(
            @Param("userId") Long userId,
            @Param("type") int type,
            @Param("success") int success,
            @Param("reason") String reason);

    List<Map<String, Object>> operations(
            @Param("scope") Set<Long> scope,
            @Param("global") boolean global,
            @Param("authorizations") boolean authorizations,
            @Param("userId") Long userId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long operationCount(
            @Param("scope") Set<Long> scope,
            @Param("global") boolean global,
            @Param("authorizations") boolean authorizations,
            @Param("userId") Long userId);

    List<Map<String, Object>> logins(
            @Param("scope") Set<Long> scope,
            @Param("global") boolean global,
            @Param("userId") Long userId,
            @Param("offset") long offset,
            @Param("limit") int limit);

    long loginCount(
            @Param("scope") Set<Long> scope,
            @Param("global") boolean global,
            @Param("userId") Long userId);

    int visible(
            @Param("id") long id, @Param("scope") Set<Long> scope, @Param("global") boolean global);

    @Select(
            "SELECT"
                + " id,app_id,api_id,user_id,target_type,target_id,request_id,operation_type,evidence,auth_decision,auth_reason,created_at"
                + " FROM sys_operation_log WHERE id=#{id}")
    Map<String, Object> operation(long id);
}
