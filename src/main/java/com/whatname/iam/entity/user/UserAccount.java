package com.whatname.iam.entity.user;

import java.time.LocalDateTime;

/** Persistence only: never use as an HTTP response or log value. */
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "sys_user")
public class UserAccount {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = jakarta.persistence.GenerationType.IDENTITY)
    public Long id;
    public String username, realName, phone, passwordHash, employeeNo;
    public Long deptId;
    public Integer status, source, employmentType, passwordInitialized;
    public Long sessionVersion, grantRevision, hrVersion;
    public Integer pwdFailedCount;
    public LocalDateTime pwdLockedUntil;
    public Long createdBy;
    public String tempReason;
    public LocalDateTime lastLoginAt, createdAt, updatedAt;

    /** Query-only projection, never persisted or part of an HTTP account view. */
    @jakarta.persistence.Transient
    public Boolean identifierMatched;

    @Override
    public String toString() {
        return "UserAccount[redacted]";
    }
}
