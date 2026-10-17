package com.whatname.iam.mapper.user;

import java.util.*;
import org.apache.ibatis.annotations.Param;

public interface NameSearchMapper {
    int deleteTokens(@Param("id") long id);
    int insertTokens(@Param("id") long id,@Param("tokens") Set<String> tokens);
    int putState(@Param("id") long id,@Param("generation") String generation,@Param("ciphertext") String ciphertext,@Param("count") int count);
    List<Long> batchIds(@Param("after") long after,@Param("through") long through,@Param("limit") int limit);
    List<String> storedTokens(@Param("id") long id);
}
