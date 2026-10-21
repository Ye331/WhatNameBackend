package com.whatname.iam.mapper.permission;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface PermissionMapper {
    @Select("SELECT id FROM sys_application WHERE app_code=#{code}")
    Long applicationId(String code);

    @Select(
            "SELECT"
                + " id,app_id,parent_id,menu_code,menu_name,menu_type,path,icon,sort_order,status,api_revision"
                + " FROM sys_menu WHERE app_id=#{id} ORDER BY sort_order,id")
    List<Map<String, Object>> menus(long id);

    @Select(
            "SELECT id,app_id,api_code,api_path,http_method,status FROM sys_api WHERE app_id=#{id}"
                + " ORDER BY id")
    List<Map<String, Object>> apis(long id);

    @Select("SELECT COUNT(*) FROM sys_session_invalidation WHERE jti=#{jti}")
    int invalidated(String jti);
}
