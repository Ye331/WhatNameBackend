package com.whatname.iam.repository.grant;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface GrantHistoryRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.audit.AuditEntry, Long>, GrantHistoryRepositoryOperations {}
