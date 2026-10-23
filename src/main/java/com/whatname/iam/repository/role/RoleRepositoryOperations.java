package com.whatname.iam.repository.role;

import com.whatname.iam.entity.resource.MenuEntity;
import com.whatname.iam.entity.role.RoleEntity;
import java.util.List;

public interface RoleRepositoryOperations {
    int countRoles();

    List<RoleEntity> listRoles(long offset, int limit);

    int countRoleCode(String roleCode, Long excludeId);

    int insertRole(RoleEntity entity);

    RoleEntity findRoleById(long id);

    int updateRole(RoleEntity entity);

    List<Long> listMenuIdsByRoleId(long roleId);

    int deleteRoleMenus(long roleId);

    int insertRoleMenus(long roleId, List<Long> menuIds);

    int bumpRoleMenuRevision(long roleId, int expectedRevision);

    List<MenuEntity> listMenusByIds(List<Long> menuIds);

}
