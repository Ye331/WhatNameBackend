package com.whatname.iam.repository.event;

/** Spring Data JPA repository; business rules remain in the service layer. */
public interface ExternalEventRepository extends org.springframework.data.repository.Repository<com.whatname.iam.entity.event.ExternalEventEntity, Long>, ExternalEventRepositoryOperations {}
