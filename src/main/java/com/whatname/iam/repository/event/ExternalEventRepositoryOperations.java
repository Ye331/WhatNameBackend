package com.whatname.iam.repository.event;

import java.time.LocalDateTime;
import java.util.Map;

public interface ExternalEventRepositoryOperations {
    Map<String, Object> lock(String source, String event);

    int insert(String source, String event, String type, Long version, String targetType, String target, String digest, String payloadJson, LocalDateTime occurredAt);

    int applied(long id, String result);

    int failed(long id, String status, String code);

}
