package com.whatname.iam.entity.auth;
import java.time.LocalDateTime;
public class SmsCode {
    public Long id;
    public String phoneHash,purpose,codeHash;
    public LocalDateTime expiresAt,consumedAt,createdAt;
    public Integer attempts;
    @Override public String toString(){return "SmsCode[redacted]";}
}
