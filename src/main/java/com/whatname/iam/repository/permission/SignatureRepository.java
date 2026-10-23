package com.whatname.iam.repository.permission;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface SignatureRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.permission.DataSignatureEntity, Long>, SignatureRepositoryOperations {}
