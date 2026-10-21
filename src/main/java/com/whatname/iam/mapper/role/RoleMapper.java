package com.whatname.iam.mapper.role;

import com.whatname.iam.entity.resource.MenuEntity;
import com.whatname.iam.entity.role.RoleEntity;

import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RoleMapper {
    int countRoles();

    List<RoleEntity> listRoles(@Param("offset") long offset, @Param("limit") int limit);

    int countRoleCode(@Param("roleCode") String roleCode, @Param("excludeId") Long excludeId);

    int insertRole(RoleEntity entity);

    RoleEntity findRoleById(@Param("id") long id);

    int updateRole(RoleEntity entity);

    List<Long> listMenuIdsByRoleId(@Param("roleId") long roleId);

    int deleteRoleMenus(@Param("roleId") long roleId);

    int insertRoleMenus(@Param("roleId") long roleId, @Param("menuIds") List<Long> menuIds);

    int bumpRoleMenuRevision(
            @Param("roleId") long roleId, @Param("expectedRevision") int expectedRevision);

    List<MenuEntity> listMenusByIds(@Param("menuIds") List<Long> menuIds);
}
