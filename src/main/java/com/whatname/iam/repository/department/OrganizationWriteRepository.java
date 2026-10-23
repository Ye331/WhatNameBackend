package com.whatname.iam.repository.department;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface OrganizationWriteRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.department.Department, Long>, OrganizationWriteRepositoryOperations {}
