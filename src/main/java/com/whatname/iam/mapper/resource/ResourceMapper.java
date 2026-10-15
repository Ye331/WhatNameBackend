package com.whatname.iam.mapper.resource;

import com.whatname.iam.entity.resource.ApiEntity;
import com.whatname.iam.entity.resource.ApplicationEntity;
import com.whatname.iam.entity.resource.MenuEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface ResourceMapper {
    int countApplications();

    List<ApplicationEntity> listApplications(@Param("offset") long offset, @Param("limit") int limit);

    int countApplicationCode(@Param("appCode") String appCode, @Param("excludeId") Long excludeId);

    int insertApplication(ApplicationEntity entity);

    ApplicationEntity findApplicationById(@Param("id") long id);

    int updateApplication(ApplicationEntity entity);

    List<MenuEntity> listMenusByAppId(@Param("appId") long appId);

    int countMenuCode(@Param("appId") long appId, @Param("menuCode") String menuCode, @Param("excludeId") Long excludeId);

    int insertMenu(MenuEntity entity);

    MenuEntity findMenuById(@Param("id") long id);

    int updateMenu(MenuEntity entity);

    long countApisByApp(@Param("appId") long appId, @Param("httpMethod") String httpMethod);

    List<ApiEntity> listApisByApp(@Param("appId") long appId, @Param("httpMethod") String httpMethod,
                                  @Param("offset") long offset, @Param("limit") int limit);

    int insertApi(ApiEntity entity);

    ApiEntity findApiById(@Param("id") long id);

    int updateApi(ApiEntity entity);

    List<ApiEntity> listApisByAppAndMethod(@Param("appId") long appId, @Param("httpMethod") String httpMethod);

    List<Long> listApiIdsByMenuId(@Param("menuId") long menuId);

    int deleteMenuApis(@Param("menuId") long menuId);

    int insertMenuApis(@Param("menuId") long menuId, @Param("apiIds") List<Long> apiIds);

    int bumpMenuApiRevision(@Param("menuId") long menuId, @Param("expectedRevision") int expectedRevision);

    List<MenuEntity> listMenusByIds(@Param("menuIds") List<Long> menuIds);

    List<ApiEntity> listApisByIds(@Param("apiIds") List<Long> apiIds);
}
