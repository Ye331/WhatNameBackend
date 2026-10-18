package com.whatname.iam.entity.auth;
import java.time.LocalDateTime;
public class PasswordHistory {
    public Long id,userId;
    public String passwordHash;
    public LocalDateTime createdAt;
    @Override public String toString(){return "PasswordHistory[redacted]";}
}
