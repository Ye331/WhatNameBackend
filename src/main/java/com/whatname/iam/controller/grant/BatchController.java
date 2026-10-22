package com.whatname.iam.controller.grant;

import com.whatname.iam.common.api.ApiResponse;
import com.whatname.iam.common.web.RequestIdFilter;
import com.whatname.iam.dto.grant.BatchDtos.*;
import com.whatname.iam.service.grant.BatchService;

import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/grants")
public class BatchController {
    private final BatchService service;

    public BatchController(BatchService service) {
        this.service = service;
    }

    @PostMapping("/user-roles/batch")
    public ResponseEntity<ApiResponse<Object>> submit(
            @RequestHeader("Idempotency-Key") String key, @RequestBody Request request) {
        var result = service.submit(key, request);
        return ResponseEntity.status(result.created() ? 202 : 200)
                .body(
                        new ApiResponse<>(
                                true,
                                result.created() ? "ACCEPTED" : "OK",
                                null,
                                MDC.get(RequestIdFilter.MDC_KEY),
                                result.data()));
    }

    @GetMapping("/batches/{batchId}")
    public ApiResponse<Result> result(
            @PathVariable String batchId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.result(batchId, page, pageSize));
    }
}
