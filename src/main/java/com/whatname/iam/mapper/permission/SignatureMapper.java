package com.whatname.iam.mapper.permission;

import com.whatname.iam.service.permission.SignedDataType;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

@Mapper
public interface SignatureMapper {
    @SelectProvider(type = Sql.class, method = "record")
    Map<String, Object> record(@Param("type") SignedDataType type, @Param("id") long id);

    @SelectProvider(type = Sql.class, method = "records")
    List<Map<String, Object>> records(
            @Param("type") SignedDataType type, @Param("ids") List<Long> ids);

    @Select(
            "<script>SELECT data_id,signature,sign_version,key_id FROM sys_data_signature WHERE"
                + " data_type=#{type} AND data_id IN <foreach collection='ids' item='id' open='('"
                + " separator=',' close=')'>#{id}</foreach></script>")
    List<Map<String, Object>> signatures(@Param("type") String type, @Param("ids") List<Long> ids);

    @Select(
            "<script>SELECT menu_id,api_id FROM sys_menu_api WHERE menu_id IN <foreach"
                + " collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
                + " ORDER BY menu_id,api_id</script>")
    List<Map<String, Object>> menuApiMembers(@Param("ids") List<Long> ids);

    @Select("SELECT role_id FROM sys_user_role WHERE user_id=#{id} ORDER BY role_id")
    List<Long> userRoles(long id);

    @Select(
            "SELECT id,role_id,source_type,source_ref,source_dept_id,status FROM"
                + " sys_user_role_source WHERE user_id=#{id} AND status=1 ORDER BY id")
    List<Map<String, Object>> sources(long id);

    @Select("SELECT menu_id FROM sys_role_permission WHERE role_id=#{id} ORDER BY menu_id")
    List<Long> roleMenus(long id);

    @Select("SELECT api_id FROM sys_menu_api WHERE menu_id=#{id} ORDER BY api_id")
    List<Long> menuApis(long id);

    @Select(
            "SELECT role_id FROM sys_admin_delegable_role WHERE admin_user_id=#{id} ORDER BY"
                + " role_id")
    List<Long> delegableRoles(long id);

    @Select("SELECT id FROM sys_menu WHERE app_id=#{id} ORDER BY id")
    List<Long> applicationMenus(long id);

    @Select("SELECT id FROM sys_api WHERE app_id=#{id} ORDER BY id")
    List<Long> applicationApis(long id);

    @Select(
            "SELECT"
                + " id,parent_id,dept_code,dept_name,dept_path,status,merged_into_dept_id,manager_id,hr_version"
                + " FROM sys_dept ORDER BY id")
    List<Map<String, Object>> organization();

    @Select(
            "SELECT signature,sign_version,key_id FROM sys_data_signature WHERE data_type=#{type}"
                + " AND data_id=#{id}")
    Map<String, Object> signature(@Param("type") String type, @Param("id") long id);

    @Insert(
            "INSERT INTO sys_data_signature(data_type,data_id,signature,sign_version,key_id)"
                + " VALUES(#{type},#{id},#{signature},1,#{keyId}) ON DUPLICATE KEY UPDATE"
                + " signature=#{signature},sign_version=1,key_id=#{keyId}")
    void save(
            @Param("type") String type,
            @Param("id") long id,
            @Param("signature") String signature,
            @Param("keyId") String keyId);

    class Sql {
        public static String record(Map<String, Object> parameters) {
            var type = (SignedDataType) parameters.get("type");
            return "SELECT "
                    + type.fields
                    + " FROM "
                    + type.table
                    + " WHERE "
                    + type.idColumn
                    + "=#{id}";
        }

        public static String records(Map<String, Object> parameters) {
            var type = (SignedDataType) parameters.get("type");
            return "<script>SELECT "
                    + type.fields
                    + " FROM "
                    + type.table
                    + " WHERE "
                    + type.idColumn
                    + " IN <foreach collection='ids' item='id' open='(' separator=','"
                    + " close=')'>#{id}</foreach> ORDER BY "
                    + type.idColumn
                    + "</script>";
        }
    }
}
