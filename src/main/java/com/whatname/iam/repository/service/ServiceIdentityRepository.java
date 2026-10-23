package com.whatname.iam.repository.service;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface ServiceIdentityRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.service.ServiceIdentityEntity, Long>, ServiceIdentityRepositoryOperations {}
