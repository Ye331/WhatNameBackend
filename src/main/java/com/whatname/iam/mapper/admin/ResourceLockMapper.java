package com.whatname.iam.mapper.admin;

import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 管理锁之后、首次功能权限读取之前锁住功能相关资源及映射，直到业务提交。 */
public interface ResourceLockMapper {
    @Select("SELECT id FROM sys_application ORDER BY id FOR UPDATE")
    List<Long> applications();

    @Select("SELECT id FROM sys_menu ORDER BY id FOR UPDATE")
    List<Long> menus();

    @Select("SELECT id FROM sys_api ORDER BY id FOR UPDATE")
    List<Long> apis();

    @Select("SELECT id FROM sys_role_permission ORDER BY id FOR UPDATE")
    List<Long> roleMenus();

    @Select("SELECT id FROM sys_menu_api ORDER BY id FOR UPDATE")
    List<Long> menuApis();
}
