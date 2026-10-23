package com.whatname.iam.repository.admin;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface ResourceLockRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.resource.ApplicationEntity, Long>, ResourceLockRepositoryOperations {}
