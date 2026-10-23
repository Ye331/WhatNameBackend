package com.whatname.iam.repository.user;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface UserAccountRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.user.UserAccount, Long>, UserAccountRepositoryOperations {}
