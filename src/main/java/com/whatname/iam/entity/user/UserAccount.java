package com.whatname.iam.entity.user;

import java.time.LocalDateTime;

/** Persistence only: never use as an HTTP response or log value. */
public class UserAccount {
    public Long id;
    public String username,realNameCiphertext,phone,phoneHash,passwordHash,employeeNo;
    public Long deptId;
    public Integer status,source,employmentType,passwordInitialized;
    public Long sessionVersion,grantRevision,hrVersion;
    public Integer pwdFailedCount;
    public LocalDateTime pwdLockedUntil;
    public Long createdBy;
    public String tempReason;
    public LocalDateTime lastLoginAt,createdAt,updatedAt;
    /** Query-only projection, never persisted or part of an HTTP account view. */
    public Boolean nameSearchMatched;
    @Override public String toString(){return "UserAccount[redacted]";}
}
