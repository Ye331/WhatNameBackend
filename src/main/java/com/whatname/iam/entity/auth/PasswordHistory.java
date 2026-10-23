package com.whatname.iam.entity.auth;

import java.time.LocalDateTime;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "sys_password_history")
public class PasswordHistory {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public String passwordHash;
    public LocalDateTime createdAt;

    @Override
    public String toString() {
        return "PasswordHistory[redacted]";
    }
}
