package com.whatname.iam.repository.oa;

import java.util.Map;

public interface OaScopeRepositoryOperations {
    Map<String, Object> lock(String clientId);

}
