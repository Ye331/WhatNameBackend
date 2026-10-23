package com.whatname.iam.entity.auth;

import java.time.LocalDateTime;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "sys_session_invalidation")
public class SessionInvalidation {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public String jti, reason;
    public LocalDateTime expiresAt, createdAt;

    @Override
    public String toString() {
        return "SessionInvalidation[redacted]";
    }
}
