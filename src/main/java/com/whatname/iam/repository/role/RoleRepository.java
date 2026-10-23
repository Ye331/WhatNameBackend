package com.whatname.iam.repository.role;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface RoleRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.role.RoleEntity, Long>, RoleRepositoryOperations {}
