package com.whatname.iam.dto.resource;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

public final class ResourceRequests {
    private ResourceRequests() {
    }

    public static class CreateApplicationRequest {
        @NotBlank
        private String appCode;
        @NotBlank
        private String appName;
        @NotBlank
        private String description;

        public String getAppCode() { return appCode; }
        public void setAppCode(String appCode) { this.appCode = appCode; }
        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
    }

    public static class UpdateApplicationRequest {
        private String appName;
        private Integer status;
        @NotBlank
        private String reason;

        @AssertTrue
        public boolean hasChange() {
            return appName != null || status != null;
        }

        public String getAppName() { return appName; }
        public void setAppName(String appName) { this.appName = appName; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class CreateMenuRequest {
        @NotBlank
        private String menuCode;
        @NotBlank
        private String menuName;
        @NotNull
        private Integer menuType;
        @NotNull
        private Long parentId;
        @NotBlank
        private String path;

        public String getMenuCode() { return menuCode; }
        public void setMenuCode(String menuCode) { this.menuCode = menuCode; }
        public String getMenuName() { return menuName; }
        public void setMenuName(String menuName) { this.menuName = menuName; }
        public Integer getMenuType() { return menuType; }
        public void setMenuType(Integer menuType) { this.menuType = menuType; }
        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }
    }

    public static class UpdateMenuRequest {
        private Long parentId;
        private Integer status;
        private String menuName;
        @NotBlank
        private String reason;

        @AssertTrue
        public boolean hasChange() {
            return parentId != null || status != null || menuName != null;
        }

        public Long getParentId() { return parentId; }
        public void setParentId(Long parentId) { this.parentId = parentId; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public String getMenuName() { return menuName; }
        public void setMenuName(String menuName) { this.menuName = menuName; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class CreateApiRequest {
        @NotBlank
        private String apiCode;
        @NotBlank
        private String apiName;
        @NotBlank
        private String apiPath;
        @NotBlank
        private String httpMethod;

        public String getApiCode() { return apiCode; }
        public void setApiCode(String apiCode) { this.apiCode = apiCode; }
        public String getApiName() { return apiName; }
        public void setApiName(String apiName) { this.apiName = apiName; }
        public String getApiPath() { return apiPath; }
        public void setApiPath(String apiPath) { this.apiPath = apiPath; }
        public String getHttpMethod() { return httpMethod; }
        public void setHttpMethod(String httpMethod) { this.httpMethod = httpMethod; }
    }

    public static class UpdateApiRequest {
        private String apiName;
        private Integer status;
        @NotBlank
        private String reason;

        @AssertTrue
        public boolean hasChange() {
            return apiName != null || status != null;
        }

        public String getApiName() { return apiName; }
        public void setApiName(String apiName) { this.apiName = apiName; }
        public Integer getStatus() { return status; }
        public void setStatus(Integer status) { this.status = status; }
        public String getReason() { return reason; }
        public void setReason(String reason) { this.reason = reason; }
    }

    public static class ReplaceMenuApisRequest {
        @NotNull
        private List<Long> apiIds;
        @NotBlank
        private String expectedVersion;

        public List<Long> getApiIds() { return apiIds; }
        public void setApiIds(List<Long> apiIds) { this.apiIds = apiIds; }
        public String getExpectedVersion() { return expectedVersion; }
        public void setExpectedVersion(String expectedVersion) { this.expectedVersion = expectedVersion; }
    }
}
