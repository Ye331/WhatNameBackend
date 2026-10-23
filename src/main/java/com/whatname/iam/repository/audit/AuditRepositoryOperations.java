package com.whatname.iam.repository.audit;

import java.util.List;
import java.util.Map;
import java.util.Set;

public interface AuditRepositoryOperations {
    void insert(Map<String, Object> entry);

    void login(Long userId, int type, int success, String reason);

    List<Map<String, Object>> operations(Set<Long> scope, boolean global, boolean authorizations, Long userId, long offset, int limit);

    long operationCount(Set<Long> scope, boolean global, boolean authorizations, Long userId);

    List<Map<String, Object>> logins(Set<Long> scope, boolean global, Long userId, long offset, int limit);

    long loginCount(Set<Long> scope, boolean global, Long userId);

    int visible(long id, Set<Long> scope, boolean global);

    Map<String, Object> operation(long id);

}
