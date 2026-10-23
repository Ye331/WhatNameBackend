package com.whatname.iam.repository.auth;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface CredentialRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.auth.SmsCode, Long>, CredentialRepositoryOperations {}
