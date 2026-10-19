package com.whatname.iam.mapper.grant;

import org.apache.ibatis.annotations.*;
import java.util.*;

@Mapper
public interface GrantHistoryMapper {
    List<Map<String,Object>> candidates(@Param("user") Long user, @Param("before") Long before, @Param("limit") int limit);
}
