package com.whatname.iam.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.whatname.iam.common.web.RequestIdFilter;
import org.slf4j.MDC;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
    boolean success, String code, String message, String requestId, T data) {
  public static <T> ApiResponse<T> ok(T data) {
    return new ApiResponse<>(true, "OK", null, MDC.get(RequestIdFilter.MDC_KEY), data);
  }

  public static ApiResponse<Void> error(String code, String message) {
    return new ApiResponse<>(false, code, message, MDC.get(RequestIdFilter.MDC_KEY), null);
  }
}
