package com.whatname.iam.common.api;

import org.springframework.http.HttpStatus;

public enum ErrorCode {
  INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "请求参数不正确"),
  INVALID_ROUTE_TEMPLATE(HttpStatus.BAD_REQUEST, "接口路径模板不正确"),
  INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "账号或密码错误"),
  INVALID_TOKEN(HttpStatus.UNAUTHORIZED, "登录已失效"),
  INVALID_SERVICE(HttpStatus.UNAUTHORIZED, "服务身份验证失败"),
  PERMISSION_DENIED(HttpStatus.FORBIDDEN, "无权执行此操作"),
  OUT_OF_SCOPE(HttpStatus.FORBIDDEN, "目标不在管理范围内"),
  RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "资源不存在"),
  METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "请求方法不支持"),
  UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请求内容类型不支持"),
  DUPLICATE_RESOURCE(HttpStatus.CONFLICT, "资源已经存在"),
  BUSINESS_RULE_VIOLATION(HttpStatus.UNPROCESSABLE_ENTITY, "不符合业务规则"),
  INTEGRITY_FAILURE(HttpStatus.SERVICE_UNAVAILABLE, "数据签名校验失败"),
  AUTH_STATE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "数据库或权限状态不可用"),
  INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "服务处理失败");
  private final HttpStatus status;
  private final String message;

  ErrorCode(HttpStatus status, String message) {
    this.status = status;
    this.message = message;
  }

  public HttpStatus status() {
    return status;
  }

  public String message() {
    return message;
  }
}
