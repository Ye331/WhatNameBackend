package com.whatname.iam.repository.permission;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface PermissionRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.user.UserAccount, Long>, PermissionRepositoryOperations {}
