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
import com.whatname.iam.repository.resource.ResourceRepository;
import com.whatname.iam.service.admin.ManagementAccessService;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.permission.DataSignatureService;
import com.whatname.iam.service.permission.SignedDataType;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Transactional(readOnly = true)
public class ResourceService {
    private static final Set<String> HTTP_METHODS =
            Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS");
    private final ResourceRepository repository;
    private final ManagementAccessService access;
    private final DataSignatureService signatures;
    private final AuditService audit;
    private final String protectedAppCode;

    public ResourceService(
            ResourceRepository repository,
            ManagementAccessService access,
            DataSignatureService signatures,
            AuditService audit,
            @Value("${iam.permission.management-app-code:IAM}") String protectedAppCode) {
        this.repository = repository;
        this.access = access;
        this.signatures = signatures;
        this.audit = audit;
        this.protectedAppCode = protectedAppCode;
    }

    public PageResponse<ApplicationItem> listApplications(String bearer, PageRequest pageRequest) {
        access.requireRead(bearer, "IAM_RESOURCES_VIEW");
        int total = repository.countApplications();
        List<ApplicationItem> items =
                repository.listApplications(pageRequest.offset(), pageRequest.getPageSize()).stream()
                        .map(
                                app ->
                                        new ApplicationItem(
                                                app.getId(),
                                                app.getAppCode(),
                                                app.getAppName(),
                                                app.getStatus()))
                        .toList();
        return new PageResponse<>(items, pageRequest.getPage(), pageRequest.getPageSize(), total);
    }

    @Transactional
    public CreateApplicationResult createApplication(
            String bearer, CreateApplicationRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        String appCode = request.getAppCode().trim().toUpperCase(Locale.ROOT);
        if (repository.countApplicationCode(appCode, null) > 0) {
            throw new BusinessException(ErrorCode.DUPLICATE_RESOURCE);
        }
        ApplicationEntity entity = new ApplicationEntity();
        entity.setAppCode(appCode);
        entity.setAppName(request.getAppName().trim());
        entity.setAppDesc(request.getDescription().trim());
        entity.setStatus(1);
        repository.insertApplication(entity);
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, entity.getId());
        audit.resourceChange(
                "RESOURCE",
                "RESOURCE_APPLICATION_CREATE",
                actorId,
                entity.getId(),
                request.getDescription(),
                null,
                signatures.snapshot(SignedDataType.RESOURCE_APPLICATION, entity.getId()),
                Map.of(
                        "appCode",
                        entity.getAppCode(),
                        "appName",
                        entity.getAppName(),
                        "status",
                        entity.getStatus()));
        return new CreateApplicationResult(entity.getId(), entity.getAppCode(), entity.getStatus());
    }

    @Transactional
    public UpdateApplicationResult updateApplication(
            String bearer, long appId, UpdateApplicationRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        ApplicationEntity existing = requireApplication(appId);
        signatures.verify(SignedDataType.RESOURCE_APPLICATION, appId);
        Integer status = request.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        if (status != null && status == 0 && isProtectedApp(existing)) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        Map<String, Object> before =
                signatures.snapshot(SignedDataType.RESOURCE_APPLICATION, appId);
        ApplicationEntity update = new ApplicationEntity();
        update.setId(appId);
        if (request.getAppName() != null) {
            update.setAppName(request.getAppName().trim());
        }
        update.setStatus(status);
        repository.updateApplication(update);
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, appId);
        Map<String, Object> after = signatures.snapshot(SignedDataType.RESOURCE_APPLICATION, appId);
        audit.resourceChange(
                "RESOURCE",
                "RESOURCE_APPLICATION_UPDATE",
                actorId,
                appId,
                request.getReason(),
                before,
                after,
                AuditService.details("appName", request.getAppName(), "status", status));
        ApplicationEntity latest = requireApplication(appId);
        return new UpdateApplicationResult(latest.getId(), latest.getStatus());
    }

    public AppMenusResult listMenus(String bearer, long appId) {
        access.requireRead(bearer, "IAM_RESOURCES_VIEW");
        requireApplication(appId);
        List<MenuItem> menus =
                repository.listMenusByAppId(appId).stream()
                        .map(
                                menu ->
                                        new MenuItem(
                                                menu.getId(),
                                                menu.getMenuType(),
                                                menu.getStatus(),
                                                menu.getParentId(),
                                                menu.getMenuCode(),
                                                menu.getMenuName(),
                                                menu.getPath()))
                        .toList();
        return new AppMenusResult(appId, menus);
    }

    @Transactional
    public CreateMenuResult createMenu(String bearer, long appId, CreateMenuRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        requireApplication(appId);
        signatures.verify(SignedDataType.RESOURCE_APPLICATION, appId);
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
        if (repository.countMenuCode(appId, menuCode, null) > 0) {
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
        repository.insertMenu(entity);
        signatures.sign(SignedDataType.RESOURCE_MENU, entity.getId());
        signatures.sign(SignedDataType.MENU_APIS, entity.getId());
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, appId);
        audit.resourceChange(
                "RESOURCE",
                "RESOURCE_MENU_CREATE",
                actorId,
                entity.getId(),
                request.getPath(),
                null,
                signatures.snapshot(SignedDataType.RESOURCE_MENU, entity.getId()),
                Map.of(
                        "appId",
                        appId,
                        "menuType",
                        menuType,
                        "parentId",
                        parentId,
                        "menuCode",
                        entity.getMenuCode()));
        return new CreateMenuResult(entity.getId(), entity.getMenuType(), entity.getStatus());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public UpdateMenuResult updateMenu(String bearer, long menuId, UpdateMenuRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        MenuEntity existing = requireMenu(menuId);
        signatures.verify(SignedDataType.RESOURCE_MENU, menuId);
        signatures.verify(SignedDataType.MENU_APIS, menuId);
        signatures.verify(SignedDataType.RESOURCE_APPLICATION, existing.getAppId());
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

        Map<String, Object> before = signatures.snapshot(SignedDataType.RESOURCE_MENU, menuId);
        MenuEntity update = new MenuEntity();
        update.setId(menuId);
        update.setParentId(parentId);
        update.setStatus(request.getStatus());
        if (request.getMenuName() != null) {
            update.setMenuName(request.getMenuName().trim());
        }
        repository.updateMenu(update);
        signatures.sign(SignedDataType.RESOURCE_MENU, menuId);
        signatures.sign(SignedDataType.MENU_APIS, menuId);
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, existing.getAppId());
        Map<String, Object> after = signatures.snapshot(SignedDataType.RESOURCE_MENU, menuId);
        audit.resourceChange(
                "RESOURCE",
                "RESOURCE_MENU_UPDATE",
                actorId,
                menuId,
                request.getReason(),
                before,
                after,
                AuditService.details(
                        "parentId",
                        parentId,
                        "status",
                        request.getStatus(),
                        "menuName",
                        request.getMenuName()));
        MenuEntity latest = requireMenu(menuId);
        return new UpdateMenuResult(latest.getId(), latest.getParentId(), latest.getStatus());
    }

    public PageResponse<ApiItem> listApis(
            String bearer, long appId, String httpMethod, PageRequest pageRequest) {
        access.requireRead(bearer, "IAM_RESOURCES_VIEW");
        requireApplication(appId);
        String normalizedMethod = normalizeMethod(httpMethod, true);
        long total = repository.countApisByApp(appId, normalizedMethod);
        List<ApiItem> items =
                repository
                        .listApisByApp(
                                appId,
                                normalizedMethod,
                                pageRequest.offset(),
                                pageRequest.getPageSize())
                        .stream()
                        .map(
                                api ->
                                        new ApiItem(
                                                api.getId(),
                                                api.getApiCode(),
                                                api.getApiPath(),
                                                api.getHttpMethod(),
                                                api.getStatus(),
                                                api.getApiName()))
                        .toList();
        return new PageResponse<>(
                items, pageRequest.getPage(), pageRequest.getPageSize(), (int) total);
    }

    @Transactional
    public CreateApiResult createApi(String bearer, long appId, CreateApiRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        requireApplication(appId);
        signatures.verify(SignedDataType.RESOURCE_APPLICATION, appId);
        String method = normalizeMethod(request.getHttpMethod(), false);
        String apiPath = RouteTemplateUtils.normalizeTemplate(request.getApiPath());
        String canonical = RouteTemplateUtils.canonicalTemplate(apiPath);
        validateTemplateConflict(appId, method, apiPath, null);

        ApiEntity entity = new ApiEntity();
        entity.setAppId(appId);
        entity.setApiCode(request.getApiCode().trim().toUpperCase(Locale.ROOT));
        entity.setApiName(request.getApiName().trim());
        entity.setApiPath(apiPath);
        entity.setCanonicalPath(canonical);
        entity.setHttpMethod(method);
        entity.setStatus(1);
        repository.insertApi(entity);
        signatures.sign(SignedDataType.RESOURCE_API, entity.getId());
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, appId);
        audit.resourceChange(
                "RESOURCE",
                "RESOURCE_API_CREATE",
                actorId,
                entity.getId(),
                request.getApiPath(),
                null,
                signatures.snapshot(SignedDataType.RESOURCE_API, entity.getId()),
                Map.of(
                        "appId",
                        appId,
                        "apiCode",
                        entity.getApiCode(),
                        "apiPath",
                        entity.getApiPath(),
                        "httpMethod",
                        entity.getHttpMethod()));
        return new CreateApiResult(
                entity.getId(), entity.getApiPath(), entity.getHttpMethod(), entity.getStatus());
    }

    @Transactional
    public UpdateApiResult updateApi(String bearer, long apiId, UpdateApiRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        ApiEntity existing = requireApi(apiId);
        signatures.verify(SignedDataType.RESOURCE_API, apiId);
        signatures.verify(SignedDataType.RESOURCE_APPLICATION, existing.getAppId());
        Integer status = request.getStatus();
        if (status != null && status != 0 && status != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }

        Map<String, Object> before = signatures.snapshot(SignedDataType.RESOURCE_API, apiId);
        ApiEntity update = new ApiEntity();
        update.setId(apiId);
        update.setStatus(status);
        if (request.getApiName() != null) {
            update.setApiName(request.getApiName().trim());
        }
        repository.updateApi(update);
        signatures.sign(SignedDataType.RESOURCE_API, apiId);
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, existing.getAppId());
        Map<String, Object> after = signatures.snapshot(SignedDataType.RESOURCE_API, apiId);
        audit.resourceChange(
                "RESOURCE",
                "RESOURCE_API_UPDATE",
                actorId,
                apiId,
                request.getReason(),
                before,
                after,
                AuditService.details("apiName", request.getApiName(), "status", status));
        ApiEntity latest = requireApi(apiId);
        return new UpdateApiResult(latest.getId(), latest.getStatus());
    }

    public MenuApisResult listMenuApis(String bearer, long menuId) {
        access.requireRead(bearer, "IAM_RESOURCES_VIEW");
        MenuEntity menu = requireMenu(menuId);
        if (menu.getMenuType() != 2) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        List<Long> apiIds = repository.listApiIdsByMenuId(menuId);
        return new MenuApisResult(menuId, apiIds, RevisionUtils.toVersion(menu.getApiRevision()));
    }

    @Transactional
    public MenuApisResult replaceMenuApis(
            String bearer, long menuId, ReplaceMenuApisRequest request) {
        long actorId = access.requireLevelOneWrite(bearer, "IAM_RESOURCES_WRITE");
        MenuEntity menu = requireMenu(menuId);
        signatures.verify(SignedDataType.RESOURCE_MENU, menuId);
        signatures.verify(SignedDataType.MENU_APIS, menuId);
        signatures.verify(SignedDataType.RESOURCE_APPLICATION, menu.getAppId());
        if (menu.getMenuType() != 2) {
            throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
        }
        int expectedRevision = RevisionUtils.parseExpectedVersion(request.getExpectedVersion());
        if (menu.getApiRevision() != expectedRevision) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }

        List<Long> dedupApiIds = com.whatname.iam.common.util.IdUtils.distinct(request.getApiIds());
        if (!dedupApiIds.isEmpty()) {
            List<ApiEntity> apis = repository.listApisByIds(dedupApiIds);
            if (apis.size() != dedupApiIds.size()) {
                throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
            }
            for (ApiEntity api : apis) {
                if (!api.getAppId().equals(menu.getAppId())) {
                    throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
                }
                signatures.verify(SignedDataType.RESOURCE_API, api.getId());
            }
        }

        Map<String, Object> before = signatures.snapshot(SignedDataType.MENU_APIS, menuId);
        repository.deleteMenuApis(menuId);
        if (!dedupApiIds.isEmpty()) {
            repository.insertMenuApis(menuId, dedupApiIds);
        }
        if (repository.bumpMenuApiRevision(menuId, expectedRevision) != 1) {
            throw new BusinessException(ErrorCode.VERSION_CONFLICT);
        }
        signatures.sign(SignedDataType.MENU_APIS, menuId);
        signatures.sign(SignedDataType.RESOURCE_MENU, menuId);
        signatures.sign(SignedDataType.RESOURCE_APPLICATION, menu.getAppId());
        Map<String, Object> after = signatures.snapshot(SignedDataType.MENU_APIS, menuId);
        audit.resourceChange(
                "RESOURCE",
                "MENU_APIS_REPLACE",
                actorId,
                menuId,
                request.getExpectedVersion(),
                before,
                after,
                AuditService.details(
                        "apiIds", dedupApiIds, "expectedVersion", request.getExpectedVersion()));
        MenuEntity latest = requireMenu(menuId);
        return new MenuApisResult(
                menuId, dedupApiIds, RevisionUtils.toVersion(latest.getApiRevision()));
    }

    private int requireMenuType(Integer menuType) {
        if (menuType == null || (menuType != 1 && menuType != 2)) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return menuType;
    }

    private boolean isProtectedApp(ApplicationEntity app) {
        return protectedAppCode.equalsIgnoreCase(app.getAppCode());
    }

    private ApplicationEntity requireApplication(long appId) {
        ApplicationEntity entity = repository.findApplicationById(appId);
        if (entity == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return entity;
    }

    private MenuEntity requireMenu(long menuId) {
        MenuEntity menu = repository.findMenuById(menuId);
        if (menu == null) {
            throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND);
        }
        return menu;
    }

    private ApiEntity requireApi(long apiId) {
        ApiEntity api = repository.findApiById(apiId);
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

    private void validateTemplateConflict(
            long appId, String method, String apiPath, Long excludeApiId) {
        List<ApiEntity> existing = repository.listApisByAppAndMethod(appId, method);
        for (ApiEntity candidate : existing) {
            if (excludeApiId != null && excludeApiId.equals(candidate.getId())) {
                continue;
            }
            String candidateTemplate = RouteTemplateUtils.normalizeTemplate(candidate.getApiPath());
            if (RouteTemplateUtils.overlapsTemplate(apiPath, candidateTemplate)) {
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
            MenuEntity parent = repository.findMenuById(current);
            if (parent == null) {
                throw new BusinessException(ErrorCode.BUSINESS_RULE_VIOLATION);
            }
            current = parent.getParentId();
        }
        return false;
    }
}
