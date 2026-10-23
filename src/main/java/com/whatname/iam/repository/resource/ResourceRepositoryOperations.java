package com.whatname.iam.repository.resource;

import com.whatname.iam.entity.resource.ApiEntity;
import com.whatname.iam.entity.resource.ApplicationEntity;
import com.whatname.iam.entity.resource.MenuEntity;
import java.util.List;

public interface ResourceRepositoryOperations {
    int countApplications();

    List<ApplicationEntity> listApplications(long offset, int limit);

    int countApplicationCode(String appCode, Long excludeId);

    int insertApplication(ApplicationEntity entity);

    ApplicationEntity findApplicationById(long id);

    ApplicationEntity lockApplicationById(long id);

    int updateApplication(ApplicationEntity entity);

    List<MenuEntity> listMenusByAppId(long appId);

    int countMenuCode(long appId, String menuCode, Long excludeId);

    int insertMenu(MenuEntity entity);

    MenuEntity findMenuById(long id);

    int updateMenu(MenuEntity entity);

    long countApisByApp(long appId, String httpMethod);

    List<ApiEntity> listApisByApp(long appId, String httpMethod, long offset, int limit);

    int insertApi(ApiEntity entity);

    ApiEntity findApiById(long id);

    int updateApi(ApiEntity entity);

    List<ApiEntity> listApisByAppAndMethod(long appId, String httpMethod);

    List<Long> listApiIdsByMenuId(long menuId);

    int deleteMenuApis(long menuId);

    int insertMenuApis(long menuId, List<Long> apiIds);

    int bumpMenuApiRevision(long menuId, int expectedRevision);

    List<MenuEntity> listMenusByIds(List<Long> menuIds);

    List<ApiEntity> listApisByIds(List<Long> apiIds);

}
