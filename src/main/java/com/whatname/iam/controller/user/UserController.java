package com.whatname.iam.controller.user;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.common.PageResponse;
import com.whatname.iam.dto.user.UserRequests.*;
import com.whatname.iam.service.user.UserService;

import jakarta.validation.Valid;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {
    private final UserService users;

    public UserController(UserService users) {
        this.users = users;
    }

    @GetMapping
    public ApiResponse<PageResponse<UserService.UserView>> list(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String deptId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) Integer source,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(users.list(bearer, keyword, deptId, status, source, page, pageSize));
    }

    @GetMapping("/{userId}")
    public ApiResponse<UserService.UserView> detail(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String userId) {
        return ApiResponse.ok(users.detail(bearer, userId));
    }

    @PostMapping("/temporary")
    public ApiResponse<UserService.Created> temporary(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @Valid @RequestBody Temporary input) {
        return ApiResponse.ok(users.createTemporary(bearer, input));
    }

    @PatchMapping("/{userId}/status")
    public ApiResponse<UserService.StatusResult> status(
            @RequestHeader(value = "Authorization", required = false) String bearer,
            @PathVariable String userId,
            @Valid @RequestBody Status input) {
        return ApiResponse.ok(users.changeStatus(bearer, userId, input));
    }
}
