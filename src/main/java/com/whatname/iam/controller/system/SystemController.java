package com.whatname.iam.controller.system;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.system.SystemStatus;
import com.whatname.iam.service.system.SystemService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {
    private final SystemService service;

    public SystemController(SystemService service) {
        this.service = service;
    }

    @GetMapping("/ping")
    public ApiResponse<SystemStatus> ping() {
        return ApiResponse.ok(service.applicationStatus());
    }

    @GetMapping("/database")
    public ApiResponse<SystemStatus> database() {
        return ApiResponse.ok(service.databaseStatus());
    }
}
