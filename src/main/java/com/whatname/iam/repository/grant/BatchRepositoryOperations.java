package com.whatname.iam.repository.grant;

import com.whatname.iam.entity.grant.BatchData.*;
import java.util.List;

public interface BatchRepositoryOperations {
    Batch byKey(long actor, String key);

    Batch batch(long id);

    Batch lockBatch(long id);

    List<Item> pendingItems(long batch, int limit);

    List<Long> unfinished(long after, int limit);

    int reserve(long id);

    List<Item> items(long batch);

    Item lockItem(long id);

    int insertBatch(long actor, String key, String digest, String operation, long role, String reason, int total);

    int insertItems(List<Item> items);

    int applied(long id, long source, String data);

    int failed(long id, String code);

    int start(long id);

    int refresh(long id);

}
