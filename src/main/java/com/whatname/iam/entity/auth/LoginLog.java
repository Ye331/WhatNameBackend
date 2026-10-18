package com.whatname.iam.entity.auth;
import java.time.LocalDateTime;
public class LoginLog {
    public Long id,userId;
    public String username,loginIp,loginDevice,failReason;
    public Integer loginType,loginStatus;
    public LocalDateTime createdAt;
}
