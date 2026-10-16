package com.whatname.iam.mapper.department;

import com.whatname.iam.entity.department.Department;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** All organization writers must take lockOrganization first in a SERIALIZABLE transaction.
 * Full PRIMARY scan locks existing rows and insertion gaps; concurrent writes cannot invalidate
 * cycle/path checks. This deliberately favors correctness over parallel organization throughput.
 */
public interface OrganizationWriteMapper {
    @Select("SELECT * FROM sys_dept FORCE INDEX(PRIMARY) ORDER BY id FOR UPDATE")
    List<Department> lockOrganization();
    @Select("SELECT * FROM sys_dept ORDER BY sort_order,id")
    List<Department> organization();
    @Select("SELECT id FROM sys_dept WHERE dept_code=#{code}")
    Long idByCode(@Param("code") String code);
    @Insert("INSERT INTO sys_dept(parent_id,dept_code,dept_name,dept_path,manager_id,hr_version) VALUES(#{parentId},#{deptCode},#{deptName},#{deptPath},#{managerId},#{hrVersion})")
    @Options(useGeneratedKeys=true,keyProperty="id")
    int insert(Department department);
    @Update("UPDATE sys_dept SET parent_id=#{parentId},dept_name=#{deptName},dept_path=#{deptPath},manager_id=#{managerId},status=#{status},merged_into_dept_id=#{mergedIntoDeptId},hr_version=#{hrVersion} WHERE id=#{id}")
    int update(Department department);
    @Select("SELECT id FROM sys_user WHERE BINARY employee_no=BINARY #{sgNo} AND source=1 AND status=1")
    Long manager(@Param("sgNo") String sgNo);
    @Select("SELECT id,dept_id AS deptId,employee_no AS sgNo,status,session_version AS sessionVersion,grant_revision AS grantRevision,hr_version AS hrVersion FROM sys_user WHERE dept_id=#{deptId} ORDER BY id FOR UPDATE")
    List<Map<String,Object>> lockUsers(@Param("deptId") Long deptId);
    @Update("UPDATE sys_user SET dept_id=#{target} WHERE dept_id=#{source}")
    int moveUsers(@Param("source") Long source,@Param("target") Long target);
    @Select("<script>SELECT id AS userId,employee_no AS sgNo,dept_id AS deptId,status FROM sys_user WHERE dept_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id LIMIT #{limit} OFFSET #{offset}</script>")
    List<Map<String,Object>> users(@Param("ids") List<Long> ids,@Param("limit") int limit,@Param("offset") long offset);
    @Select("<script>SELECT COUNT(*) FROM sys_user WHERE dept_id IN <foreach item='id' collection='ids' open='(' separator=',' close=')'>#{id}</foreach></script>")
    long countUsers(@Param("ids") List<Long> ids);
}
