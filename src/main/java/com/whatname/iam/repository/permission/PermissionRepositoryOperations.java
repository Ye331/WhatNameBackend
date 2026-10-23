package com.whatname.iam.repository.permission;

import java.util.List;
import java.util.Map;

public interface PermissionRepositoryOperations {
    Long applicationId(String code);

    List<Map<String, Object>> menus(long id);

    List<Map<String, Object>> apis(long id);

    int invalidated(String jti);

}
