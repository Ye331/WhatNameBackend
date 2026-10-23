package com.whatname.iam.repository.department;

import com.whatname.iam.entity.department.Department;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

/** Organization reads only; caller must apply management scope. */
@Transactional(readOnly = true)
public interface DepartmentRepository extends Repository<Department, Long> {
    @Query("select d from Department d where d.id = :id")
    Department findById(@Param("id") Long id);

    @Query("select d from Department d where d.deptCode = :deptCode")
    Department findByCode(@Param("deptCode") String deptCode);

    @Query("select d from Department d where d.parentId = :parentId order by d.sortOrder, d.id")
    List<Department> findChildrenByParentId(@Param("parentId") Long parentId);

    @Query("select d from Department d, Department root where root.id = :id "
            + "and d.deptPath like concat(root.deptPath, '%') order by d.sortOrder, d.id")
    List<Department> findSubtreeById(@Param("id") Long id);
}
