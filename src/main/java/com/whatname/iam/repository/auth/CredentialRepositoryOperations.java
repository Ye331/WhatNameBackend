package com.whatname.iam.repository.auth;

import com.whatname.iam.entity.auth.*;
import java.time.LocalDateTime;
import java.util.List;

public interface CredentialRepositoryOperations {
    SmsCode lockLatestCode(String phoneLookup, String purpose);

    int invalidateCodes(String phoneLookup, String purpose, LocalDateTime now);

    int insertCode(SmsCode code);

    int failCode(long id, LocalDateTime now);

    int consumeCode(long id, LocalDateTime now);

    List<PasswordHistory> recentHistory(long userId);

    int insertHistory(PasswordHistory history);

    int trimHistory(long userId);

    SessionInvalidation findInvalidation(String jti);

    int insertInvalidation(SessionInvalidation invalidation);

    int insertLoginLog(LoginLog log);

}
