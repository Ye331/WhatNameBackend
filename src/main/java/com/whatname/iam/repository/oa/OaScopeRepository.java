package com.whatname.iam.repository.oa;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface OaScopeRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.service.ServiceIdentityEntity, Long>, OaScopeRepositoryOperations {}
