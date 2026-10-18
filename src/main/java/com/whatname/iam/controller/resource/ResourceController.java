package com.whatname.iam.controller.resource;

import com.whatname.iam.common.api.ApiResponse;
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
import com.whatname.iam.dto.resource.ResourceResponses.UpdateApiResult;
import com.whatname.iam.dto.resource.ResourceResponses.UpdateApplicationResult;
import com.whatname.iam.dto.resource.ResourceResponses.UpdateMenuResult;
import com.whatname.iam.service.resource.ResourceService;
import jakarta.validation.Valid;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1")
public class ResourceController {
    private final ResourceService service;

    public ResourceController(ResourceService service) {
        this.service = service;
    }

    @GetMapping("/applications")
    public ApiResponse<PageResponse<ApplicationItem>> listApplications(@Valid PageRequest pageRequest) {
        return ApiResponse.ok(service.listApplications(pageRequest));
    }

    @PostMapping("/applications")
    public ApiResponse<CreateApplicationResult> createApplication(@Valid @RequestBody CreateApplicationRequest request) {
        return ApiResponse.ok(service.createApplication(request));
    }

    @PatchMapping("/applications/{appId}")
    public ApiResponse<UpdateApplicationResult> updateApplication(@PathVariable long appId,
                                                                  @Valid @RequestBody UpdateApplicationRequest request) {
        return ApiResponse.ok(service.updateApplication(appId, request));
    }

    @GetMapping("/applications/{appId}/menus")
    public ApiResponse<AppMenusResult> listMenus(@PathVariable long appId) {
        return ApiResponse.ok(service.listMenus(appId));
    }

    @PostMapping("/applications/{appId}/menus")
    public ApiResponse<CreateMenuResult> createMenu(@PathVariable long appId, @Valid @RequestBody CreateMenuRequest request) {
        return ApiResponse.ok(service.createMenu(appId, request));
    }

    @PatchMapping("/menus/{menuId}")
    public ApiResponse<UpdateMenuResult> updateMenu(@PathVariable long menuId, @Valid @RequestBody UpdateMenuRequest request) {
        return ApiResponse.ok(service.updateMenu(menuId, request));
    }

    @GetMapping("/applications/{appId}/apis")
    public ApiResponse<PageResponse<ApiItem>> listApis(@PathVariable long appId,
                                                       @RequestParam(required = false) String httpMethod,
                                                       @Valid PageRequest pageRequest) {
        return ApiResponse.ok(service.listApis(appId, httpMethod, pageRequest));
    }

    @PostMapping("/applications/{appId}/apis")
    public ApiResponse<CreateApiResult> createApi(@PathVariable long appId, @Valid @RequestBody CreateApiRequest request) {
        return ApiResponse.ok(service.createApi(appId, request));
    }

    @PatchMapping("/apis/{apiId}")
    public ApiResponse<UpdateApiResult> updateApi(@PathVariable long apiId, @Valid @RequestBody UpdateApiRequest request) {
        return ApiResponse.ok(service.updateApi(apiId, request));
    }

    @GetMapping("/menus/{menuId}/apis")
    public ApiResponse<MenuApisResult> listMenuApis(@PathVariable long menuId) {
        return ApiResponse.ok(service.listMenuApis(menuId));
    }

    @PutMapping("/menus/{menuId}/apis")
    public ApiResponse<MenuApisResult> replaceMenuApis(@PathVariable long menuId,
                                                       @Valid @RequestBody ReplaceMenuApisRequest request) {
        return ApiResponse.ok(service.replaceMenuApis(menuId, request));
    }
}
