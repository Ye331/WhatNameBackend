package com.whatname.iam.mapper.grant;

import com.whatname.iam.entity.grant.GrantData.*;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface GrantMapper {
    Long selectRevision(@Param("userId") long userId, @Param("lock") boolean lock);
    List<Source> selectSources(@Param("userId") long userId, @Param("lock") boolean lock);
    List<EffectiveRole> selectEffectiveRoles(@Param("userId") long userId, @Param("lock") boolean lock);
    Source selectSourceByKey(@Param("userId") long userId, @Param("roleId") long roleId,
                            @Param("type") String type, @Param("ref") String ref);
    int insertSource(@Param("userId") long userId, @Param("roleId") long roleId,
                     @Param("type") String type, @Param("ref") String ref,
                     @Param("deptId") Long deptId, @Param("actorId") long actorId);
    int insertEffectiveRole(@Param("userId") long userId, @Param("roleId") long roleId, @Param("actorId") long actorId);
    int revokeSource(@Param("userId") long userId, @Param("roleId") long roleId,
                     @Param("sourceId") long sourceId, @Param("actorId") long actorId);
    int deleteEffectiveRole(@Param("userId") long userId, @Param("roleId") long roleId);
    int advanceRevision(@Param("userId") long userId, @Param("expected") long expected);
}
