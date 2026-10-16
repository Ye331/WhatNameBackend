package com.whatname.iam.service.resource;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.RevisionUtils;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.dto.common.PageRequest;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.dto.resource.ResourceRequests.CreateApiRequest;
import com.whatname.iam.dto.resource.ResourceRequests.CreateApplicationRequest;
import com.whatname.iam.dto.resource.ResourceRequests.CreateMenuRequest;
import com.whatname.iam.dto.resource.ResourceRequests.ReplaceMenuApisRequest;
import com.whatname.iam.dto.resource.ResourceRequests.UpdateApiRequest;
import com.whatname.iam.dto.resource.ResourceRequests.UpdateApplicationRequest;
import com.whatname.iam.dto.resource.ResourceRequests.UpdateMenuRequest;
import com.whatname.iam.dto.resource.ResourceResponses.ApiItem;
import com.whatname.iam.dto.resource.ResourceResponses.AppMenusResult;
import com.whatname.iam.dto.resource.ResourceResponses.ApplicationItem;
import com.whatname.iam.dto.resource.ResourceResponses.CreateApiResult;
import com.whatname.iam.dto.resource.ResourceResponses.CreateApplicationResult;
import com.whatname.iam.dto.resource.ResourceResponses.CreateMenuResult;
import com.whatname.iam.dto.resource.ResourceResponses.MenuApisResult;
import com.whatname.iam.dto.resource.ResourceResponses.MenuItem;
import com.whatname.iam.dto.resource.ResourceResponses.UpdateApiResult;
import com.whatname.iam.dto.resource.ResourceResponses.UpdateApplicationResult;
import com.whatname.iam.dto.resource.ResourceResponses.UpdateMenuResult;
import com.whatname.iam.entity.resource.ApiEntity;
import com.whatname.iam.entity.resource.ApplicationEntity;
import com.whatname.iam.entity.resource.MenuEntity;
import com.whatname.iam.mapper.resource.ResourceMapper;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResourceService {
    private static final Set<String> HTTP_METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private final ResourceMapper mapper;
    private final String protectedAppCode;

    public ResourceService(ResourceMapper mapper, @Value("${app.system-app-code:IAM}") String protectedAppCode) {
        this.mapper = mapper;
        this.protectedAppCode = protectedAppCode;
    }

    public PageResponse<ApplicationItem> listApplications(PageRequest pageRequest) {
        int total = mapper.countApplications();
        List<ApplicationItem> items = mapper.listApplications(pageRequest.offset(), pageRequest.getPageSize())
                .stream()
                .map(app -> new ApplicationItem(app.getId(), app.getAppCode(), app.getAppName(), app.getStatus()))
                .toList();
        return new PageResponse<>(items, pageRequest.getPage(), pageRequest.getPageSize(), total);
    }

    @Transactional
    public CreateApplicationResult createApplication(CreateApplicationRequest request) {
        String appCode = request.getAppCode().trim().toUpperCase(Locale.ROOT);
        if (mapper.countApplicationCode(appCode, null) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        ApplicationEntity entity = new ApplicationEntity();
        entity.setAppCode(appCode);
        entity.setAppName(request.getAppName().trim());
        entity.setAppDesc(request.getDescription().trim());
        entity.setStatus(1);
        mapper.insertApplication(entity);
        return new CreateApplicationResult(entity.getId(), entity.getAppCode(), entity.getStatus());
    }

    @Transactional
    public UpdateApplicationResult updateApplication(long appId, UpdateApplicationRequest request) {
        ApplicationEntity existing = requireApplication(appId);
        Integer status = request.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (status != null && status == 0 && protectedAppCode.equalsIgnoreCase(existing.getAppCode())) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        ApplicationEntity update = new ApplicationEntity();
        update.setId(appId);
        if (request.getAppName() != null) {
            update.setAppName(request.getAppName().trim());
        }
        update.setStatus(status);
        mapper.updateApplication(update);
        ApplicationEntity latest = requireApplication(appId);
        return new UpdateApplicationResult(latest.getId(), latest.getStatus());
    }

    public AppMenusResult listMenus(long appId) {
        requireApplication(appId);
        List<MenuItem> menus = mapper.listMenusByAppId(appId)
                .stream()
                .map(menu -> new MenuItem(menu.getId(), menu.getMenuType(), menu.getStatus(), menu.getParentId()))
                .toList();
        return new AppMenusResult(appId, menus);
    }

    @Transactional
    public CreateMenuResult createMenu(long appId, CreateMenuRequest request) {
        lockApplication(appId);
        int menuType = requireMenuType(request.getMenuType());
        Long parentId = request.getParentId();
        if (parentId < 0) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (parentId != 0) {
            MenuEntity parent = requireMenu(parentId);
            if (!parent.getAppId().equals(appId)) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            }
        }
        String menuCode = request.getMenuCode().trim().toUpperCase(Locale.ROOT);
        if (mapper.countMenuCode(appId, menuCode, null) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        MenuEntity entity = new MenuEntity();
        entity.setAppId(appId);
        entity.setParentId(parentId);
        entity.setMenuCode(menuCode);
        entity.setMenuName(request.getMenuName().trim());
        entity.setMenuType(menuType);
        entity.setPath(request.getPath().trim());
        entity.setApiRevision(1);
        entity.setStatus(1);
        mapper.insertMenu(entity);
        return new CreateMenuResult(entity.getId(), entity.getMenuType(), entity.getStatus());
    }

    @Transactional
    public UpdateMenuResult updateMenu(long menuId, UpdateMenuRequest request) {
        MenuEntity existing = requireMenu(menuId);
        lockApplication(existing.getAppId());
        existing = requireMenu(menuId);
        if (request.getStatus() != null && request.getStatus() != 0 && request.getStatus() != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        Long parentId = request.getParentId();
        if (parentId != null) {
            if (parentId < 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            if (parentId.equals(menuId)) {
                throw new BusinessException(ErrorCode.MENU_TREE_CYCLE);
            }
            if (!parentId.equals(0L)) {
                MenuEntity parent = requireMenu(parentId);
                if (!existing.getAppId().equals(parent.getAppId())) {
                    throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                }
                if (wouldCreateCycle(menuId, parentId)) {
                    throw new BusinessException(ErrorCode.MENU_TREE_CYCLE);
                }
            }
        }

        MenuEntity update = new MenuEntity();
        update.setId(menuId);
        update.setParentId(parentId);
        update.setStatus(request.getStatus());
        if (request.getMenuName() != null) {
            update.setMenuName(request.getMenuName().trim());
        }
        mapper.updateMenu(update);
        MenuEntity latest = requireMenu(menuId);
        return new UpdateMenuResult(latest.getId(), latest.getParentId(), latest.getStatus());
    }

    public PageResponse<ApiItem> listApis(long appId, String httpMethod, PageRequest pageRequest) {
        requireApplication(appId);
        String normalizedMethod = normalizeMethod(httpMethod, true);
        long total = mapper.countApisByApp(appId, normalizedMethod);
        List<ApiItem> items = mapper.listApisByApp(appId, normalizedMethod, pageRequest.offset(), pageRequest.getPageSize())
                .stream()
                .map(api -> new ApiItem(api.getId(), api.getApiCode(), api.getApiPath(), api.getHttpMethod(), api.getStatus()))
                .toList();
        return new PageResponse<>(items, pageRequest.getPage(), pageRequest.getPageSize(), (int) total);
    }

    @Transactional
    public CreateApiResult createApi(long appId, CreateApiRequest request) {
        // serialize API creation per-application to avoid canonicalization race
        lockApplication(appId);
        String method = normalizeMethod(request.getHttpMethod(), false);
        String apiPath = RouteTemplateUtils.normalizeTemplate(request.getApiPath());
        String canonical = RouteTemplateUtils.canonicalTemplate(apiPath);
        // pre-check using canonical comparison
        validateTemplateConflict(appId, method, apiPath, null);

        ApiEntity entity = new ApiEntity();
        entity.setAppId(appId);
        entity.setApiCode(request.getApiCode().trim().toUpperCase(Locale.ROOT));
        entity.setApiName(request.getApiName().trim());
        entity.setApiPath(apiPath);
        entity.setCanonicalPath(canonical);
        entity.setHttpMethod(method);
        entity.setStatus(1);
        try {
            mapper.insertApi(entity);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // another concurrent request inserted an equivalent canonical path
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        return new CreateApiResult(entity.getId(), entity.getApiPath(), entity.getHttpMethod(), entity.getStatus());
    }

    @Transactional
    public UpdateApiResult updateApi(long apiId, UpdateApiRequest request) {
        ApiEntity existing = requireApi(apiId);
        Integer status = request.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }

        ApiEntity update = new ApiEntity();
        update.setId(apiId);
        update.setStatus(status);
        if (request.getApiName() != null) {
            update.setApiName(request.getApiName().trim());
        }
        mapper.updateApi(update);
        ApiEntity latest = requireApi(apiId);
        return new UpdateApiResult(latest.getId(), latest.getStatus());
    }

    public MenuApisResult listMenuApis(long menuId) {
        MenuEntity menu = requireMenu(menuId);
        if (menu.getMenuType() != 2) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        List<Long> apiIds = mapper.listApiIdsByMenuId(menuId);
        return new MenuApisResult(menuId, apiIds, RevisionUtils.toVersion(menu.getApiRevision()));
    }

    @Transactional
    public MenuApisResult replaceMenuApis(long menuId, ReplaceMenuApisRequest request) {
        MenuEntity menu = requireMenu(menuId);
        if (menu.getMenuType() != 2) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        int expectedRevision = RevisionUtils.parseExpectedVersion(request.getExpectedVersion());
        if (menu.getApiRevision() != expectedRevision) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        List<Long> dedupApiIds = deduplicateIds(request.getApiIds());
        if (!dedupApiIds.isEmpty()) {
            List<ApiEntity> apis = mapper.listApisByIds(dedupApiIds);
            if (apis.size() != dedupApiIds.size()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            for (ApiEntity api : apis) {
                if (!api.getAppId().equals(menu.getAppId())) {
                    throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                }
            }
        }

        mapper.deleteMenuApis(menuId);
        if (!dedupApiIds.isEmpty()) {
            mapper.insertMenuApis(menuId, dedupApiIds);
        }
        if (mapper.bumpMenuApiRevision(menuId, expectedRevision) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        MenuEntity latest = requireMenu(menuId);
        return new MenuApisResult(menuId, dedupApiIds, RevisionUtils.toVersion(latest.getApiRevision()));
    }

    private int requireMenuType(Integer menuType) {
        if (menuType == null || (menuType != 1 && menuType != 2)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return menuType;
    }

    private ApplicationEntity requireApplication(long appId) {
        ApplicationEntity entity = mapper.findApplicationById(appId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private ApplicationEntity lockApplication(long appId) {
        ApplicationEntity entity = mapper.lockApplicationById(appId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private MenuEntity requireMenu(long menuId) {
        MenuEntity menu = mapper.findMenuById(menuId);
        if (menu == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return menu;
    }

    private ApiEntity requireApi(long apiId) {
        ApiEntity api = mapper.findApiById(apiId);
        if (api == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return api;
    }

    private String normalizeMethod(String method, boolean allowNull) {
        if (method == null) {
            if (allowNull) {
                return null;
            }
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        String normalized = method.trim().toUpperCase(Locale.ROOT);
        if (!HTTP_METHODS.contains(normalized)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return normalized;
    }

    private void validateTemplateConflict(long appId, String method, String apiPath, Long excludeApiId) {
        String targetCanonical = RouteTemplateUtils.canonicalTemplate(apiPath);
        List<ApiEntity> existing = mapper.listApisByAppAndMethod(appId, method);
        for (ApiEntity candidate : existing) {
            if (excludeApiId != null && excludeApiId.equals(candidate.getId())) {
                continue;
            }
            String candidateCanonical = RouteTemplateUtils.canonicalTemplate(RouteTemplateUtils.normalizeTemplate(candidate.getApiPath()));
            if (candidateCanonical.equals(targetCanonical)) {
                throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
            }
        }
    }

    private boolean wouldCreateCycle(long menuId, long parentId) {
        long current = parentId;
        while (current != 0) {
            if (current == menuId) {
                return true;
            }
            MenuEntity parent = mapper.findMenuById(current);
            if (parent == null) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            }
            current = parent.getParentId();
        }
        return false;
    }

    private List<Long> deduplicateIds(List<Long> source) {
        if (source == null) {
            return List.of();
        }
        Set<Long> set = new LinkedHashSet<>();
        for (Long id : source) {
            if (id == null || id <= 0) {
                throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
            }
            set.add(id);
        }
        return new ArrayList<>(set);
    }
}
