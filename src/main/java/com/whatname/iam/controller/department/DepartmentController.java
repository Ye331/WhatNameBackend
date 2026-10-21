package com.whatname.iam.controller.department;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.department.DepartmentRequests;
import com.whatname.iam.service.department.DepartmentCollaboration;
import com.whatname.iam.service.department.DepartmentService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class DepartmentController {
    private final DepartmentService service;
    private final DepartmentCollaboration integration;

    public DepartmentController(DepartmentService service, DepartmentCollaboration integration) {
        this.service = service;
        this.integration = integration;
    }

    @GetMapping("/departments")
    public ApiResponse<Map<String, Object>> tree(
            HttpServletRequest request,
            @RequestParam(defaultValue = "true") boolean includeDescendants) {
        return ApiResponse.ok(
                service.tree(
                        integration.authenticateManager(request, "IAM_DEPTS_VIEW"),
                        includeDescendants));
    }

    @GetMapping("/departments/{deptId}/users")
    public ApiResponse<Map<String, Object>> users(
            HttpServletRequest request,
            @PathVariable String deptId,
            @RequestParam(defaultValue = "false") boolean includeDescendants,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(
                service.users(
                        integration.authenticateManager(request, "IAM_USERS_VIEW"),
                        id(deptId),
                        includeDescendants,
                        page,
                        pageSize));
    }

    @PutMapping("/integrations/hr/departments/{deptCode}")
    public ApiResponse<Map<String, Object>> sync(
            HttpServletRequest request,
            @PathVariable String deptCode,
            @Valid @RequestBody DepartmentRequests.Sync body) {
        return ApiResponse.ok(
                service.sync(integration.authenticateHr(request, "HR_DEPARTMENT"), deptCode, body));
    }

    @PostMapping("/integrations/hr/merges")
    public ApiResponse<Map<String, Object>> merge(
            HttpServletRequest request, @Valid @RequestBody DepartmentRequests.Merge body) {
        return ApiResponse.ok(service.merge(integration.authenticateHr(request, "HR_MERGE"), body));
    }

    @PostMapping("/departments/{deptId}/freeze")
    public ApiResponse<Map<String, Object>> freeze(
            HttpServletRequest request,
            @PathVariable String deptId,
            @Valid @RequestBody DepartmentRequests.Reason body) {
        return ApiResponse.ok(
                service.freeze(
                        integration.authenticateManager(request, "IAM_DEPTS_FREEZE"),
                        id(deptId),
                        true,
                        body.reason()));
    }

    @PostMapping("/departments/{deptId}/unfreeze")
    public ApiResponse<Map<String, Object>> unfreeze(
            HttpServletRequest request,
            @PathVariable String deptId,
            @Valid @RequestBody DepartmentRequests.Reason body) {
        return ApiResponse.ok(
                service.freeze(
                        integration.authenticateManager(request, "IAM_DEPTS_FREEZE"),
                        id(deptId),
                        false,
                        body.reason()));
    }

    private long id(String input) {
        try {
            if (!input.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(input);
        } catch (NumberFormatException e) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
    }
}
