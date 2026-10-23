package com.whatname.iam.repository.grant;

import java.util.*;

public interface GrantHistoryRepositoryOperations {
    List<Map<String, Object>> candidates(Long user, Long before, int limit);

}
