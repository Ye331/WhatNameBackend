package com.whatname.iam.mapper.oa;

import org.apache.ibatis.annotations.*;

import java.util.Map;

@Mapper
public interface OaScopeMapper {
    @Select(
            "SELECT"
                + " id,app_id,client_id,allowed_scope,allowed_role_ids,allowed_dept_ids,allowed_operations,status"
                + " FROM sys_service_identity WHERE client_id=#{clientId} FOR UPDATE")
    Map<String, Object> lock(String clientId);
}
