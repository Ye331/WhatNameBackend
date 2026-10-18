package com.whatname.iam.mapper.auth;

import com.whatname.iam.entity.auth.*;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface CredentialMapper {
    SmsCode lockLatestCode(@Param("phoneHash") String phoneHash,@Param("purpose") String purpose);
    int invalidateCodes(@Param("phoneHash") String phoneHash,@Param("purpose") String purpose,@Param("now") LocalDateTime now);
    int insertCode(SmsCode code);
    int failCode(@Param("id") long id,@Param("now") LocalDateTime now);
    int consumeCode(@Param("id") long id,@Param("now") LocalDateTime now);
    List<PasswordHistory> recentHistory(@Param("userId") long userId);
    int insertHistory(PasswordHistory history);
    int trimHistory(@Param("userId") long userId);
    SessionInvalidation findInvalidation(@Param("jti") String jti);
    int insertInvalidation(SessionInvalidation invalidation);
    int insertLoginLog(LoginLog log);
}
