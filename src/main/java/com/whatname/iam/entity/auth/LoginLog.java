package com.whatname.iam.entity.auth;

import java.time.LocalDateTime;

@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "sys_login_log")
public class LoginLog {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    public Long id;
    public Long userId;
    public String username, loginIp, loginDevice, failReason;
    public Integer loginType, loginStatus;
    public LocalDateTime createdAt;
}
