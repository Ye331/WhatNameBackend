package com.whatname.iam.repository.audit;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface AuditRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.audit.AuditEntry, Long>, AuditRepositoryOperations {}
