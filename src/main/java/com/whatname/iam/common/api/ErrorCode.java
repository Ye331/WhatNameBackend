package com.whatname.iam.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "请求参数不正确"),
    INVALID_ROUTE_TEMPLATE(HttpStatus.BAD_REQUEST, "API路由模板不正确"),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "身份凭据不正确"),
    INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "身份验证失败"),
    INVALID_SERVICE(HttpStatus.UNAUTHORIZED, "服务身份验证失败"),
    PERMISSION_DENIED(HttpStatus.FORBIDDEN, "无权执行此操作"),
    OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "目标不在管理范围内"),
    ROLE_NOT_DELEGABLE(HttpStatus.FORBIDDEN, "无权授予此角色"),
    DELEGATION_OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "角色委派超出范围"),
    OA_SCOPE_DENIED(HttpStatus.FORBIDDEN, "审批操作超出服务授权范围"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
    GRANT_SOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "授权来源不存在"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
    VERSION_CONFLICT(HttpStatus.CONFLICT, "数据版本冲突"),
    IDEMPOTENCY_CONFLICT(HttpStatus.CONFLICT, "同一请求标识对应不同内容"),
    DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "资源已经存在"),
    BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "不符合业务规则"),
    INVALID_HR_ORDER(HttpStatus.UNPROCESSABLE_ENTITY, "人员或部门事件版本不正确"),
    INVALID_DEPT_MAPPING(HttpStatus.UNPROCESSABLE_ENTITY, "部门标识无法正确对应"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "请求过于频繁"),
    AUTH_STATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "数据库或当前权限状态不可用"),
    INTEGRITY_FAILURE(HttpStatus.SERVICE_UNAVAILABLE, "数据完整性校验失败"),
    AUDIT_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "审计记录暂不可用"),
    SMS_DELIVERY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "短信发送暂不可用"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务处理失败");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message) {
        this.status = status;
        this.message = message;
    }

    public HttpStatus status() { return status; }
    public String message() { return message; }
}
