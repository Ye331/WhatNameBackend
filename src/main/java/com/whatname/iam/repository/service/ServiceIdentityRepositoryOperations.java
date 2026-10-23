package com.whatname.iam.repository.service;

import java.time.LocalDateTime;
import java.util.Map;

public interface ServiceIdentityRepositoryOperations {
    Map<String, Object> find(String clientId);

    int insertNonce(String clientId, String nonce, LocalDateTime expiresAt);

    int deleteExpiredNonces(LocalDateTime now);

}
