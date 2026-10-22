package com.whatname.iam.entity.auth;

import java.time.LocalDateTime;

public class SessionInvalidation {
    public Long id, userId;
    public String jti, reason;
    public LocalDateTime expiresAt, createdAt;

    @Override
    public String toString() {
        return "SessionInvalidation[redacted]";
    }
}
