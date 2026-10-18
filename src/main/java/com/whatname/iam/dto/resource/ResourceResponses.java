package com.whatname.iam.dto.resource;

import java.util.List;

public final class ResourceResponses {
    private ResourceResponses() {
    }

    public record ApplicationItem(Long appId, String appCode, String appName, Integer status) {
    }

    public record CreateApplicationResult(Long appId, String appCode, Integer status) {
    }

    public record UpdateApplicationResult(Long appId, Integer status) {
    }

    public record MenuItem(Long menuId, Integer menuType, Integer status, Long parentId) {
    }

    public record AppMenusResult(Long appId, List<MenuItem> menus) {
    }

    public record CreateMenuResult(Long menuId, Integer menuType, Integer status) {
    }

    public record UpdateMenuResult(Long menuId, Long parentId, Integer status) {
    }

    public record ApiItem(Long apiId, String apiCode, String apiPath, String httpMethod, Integer status) {
    }

    public record CreateApiResult(Long apiId, String apiPath, String httpMethod, Integer status) {
    }

    public record UpdateApiResult(Long apiId, Integer status) {
    }

    public record MenuApisResult(Long menuId, List<Long> apiIds, String mappingVersion) {
    }
}
