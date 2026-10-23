package com.whatname.iam.repository.department;

import com.whatname.iam.entity.department.Department;
import java.util.List;
import java.util.Map;

public interface OrganizationWriteRepositoryOperations {
    List<Department> lockOrganization();

    List<Department> organization();

    Long idByCode(String code);

    int insert(Department department);

    int update(Department department);

    Long manager(String sgNo);

    List<Map<String, Object>> lockUsers(Long deptId);

    int moveUsers(Long source, Long target);

    List<Map<String, Object>> users(List<Long> ids, int limit, long offset);

    long countUsers(List<Long> ids);

}
