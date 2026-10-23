package com.whatname.iam.repository.system;

import com.whatname.iam.entity.department.Department;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface SystemRepository extends Repository<Department, Long> {
    @Query(value = "SELECT 1", nativeQuery = true)
    int ping();
}
