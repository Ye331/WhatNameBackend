package com.whatname.iam.repository.resource;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface ResourceRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.resource.ApplicationEntity, Long>, ResourceRepositoryOperations {}
