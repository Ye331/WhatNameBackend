package com.whatname.iam.mapper.grant;

import com.whatname.iam.entity.grant.BatchData.*;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BatchMapper {
    Batch byKey(@Param("actor") long actor, @Param("key") String key);

    Batch batch(@Param("id") long id);

    Batch lockBatch(@Param("id") long id);

    List<Item> pendingItems(@Param("batch") long batch, @Param("limit") int limit);

    List<Long> unfinished(@Param("after") long after, @Param("limit") int limit);

    int reserve(@Param("id") long id);

    List<Item> items(@Param("batch") long batch);

    Item lockItem(@Param("id") long id);

    int insertBatch(
            @Param("actor") long actor,
            @Param("key") String key,
            @Param("digest") String digest,
            @Param("operation") String operation,
            @Param("role") long role,
            @Param("reason") String reason,
            @Param("total") int total);

    int insertItems(@Param("items") List<Item> items);

    int applied(@Param("id") long id, @Param("source") long source, @Param("data") String data);

    int failed(@Param("id") long id, @Param("code") String code);

    int start(@Param("id") long id);

    int refresh(@Param("id") long id);
}
