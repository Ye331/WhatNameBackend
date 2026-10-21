package com.whatname.iam.mapper.department;

import com.whatname.iam.entity.department.Department;

import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Internal reads only. These methods do not enforce M5 scope or DP01 authorization. A management
 * Service must check permissions and filter the returned organization data.
 */
public interface DepartmentMapper {
    Department findById(@Param("id") Long id);

    Department findByCode(@Param("deptCode") String deptCode);

    /** Immediate children, ordered by sort_order and internal ID; parentId=0 reads roots. */
    List<Department> findChildrenByParentId(@Param("parentId") Long parentId);

    /** Includes the node itself; uses its stored /id/.../id/ path, never a caller path. */
    List<Department> findSubtreeById(@Param("id") Long id);
}
