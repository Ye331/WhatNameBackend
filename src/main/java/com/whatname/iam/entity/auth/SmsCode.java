package com.whatname.iam.entity.auth;

import java.time.LocalDateTime;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "sys_sms_code")
public class SmsCode {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    public Long id;
    public String phone, purpose, codeHash;
    public LocalDateTime expiresAt, consumedAt, createdAt;
    public Integer attempts;

    @Override
    public String toString() {
        return "SmsCode[redacted]";
    }
}
