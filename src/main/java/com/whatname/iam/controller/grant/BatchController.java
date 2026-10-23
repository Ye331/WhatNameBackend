package com.whatname.iam.controller.grant;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.dto.grant.BatchDtos.*;
import com.whatname.iam.service.grant.BatchService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/grants")
public class BatchController {
    private final BatchService service;
    public BatchController(BatchService service) { this.service = service; }

    @PostMapping("/user-roles/batch")
    public ApiResponse<Result> submit(
            @RequestHeader(value = "Idempotency-Key", required = false) String key,
            @RequestBody Request request) {
        return ApiResponse.ok(service.submit(key, request));
    }
}
