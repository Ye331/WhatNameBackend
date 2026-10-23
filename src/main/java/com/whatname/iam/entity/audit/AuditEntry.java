package com.whatname.iam.entity.audit;

import jakarta.persistence.*;

/** Persistence mapping; never expose directly as an HTTP response. */
@Entity
@Table(name = "sys_operation_log")
public class AuditEntry {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "user_id")
    public Long userId;

    @Column(name = "username")
    public String username;

    @Column(name = "request_id")
    public String requestId;

    @Column(name = "app_id")
    public Long appId;

    @Column(name = "api_id")
    public Long apiId;

    @Column(name = "auth_at")
    public java.time.LocalDateTime authAt;

    @Column(name = "auth_decision")
    public Integer authDecision;

    @Column(name = "auth_reason")
    public String authReason;

    @Column(name = "evidence", columnDefinition = "json")
    public String evidence;

    @Column(name = "source_system")
    public String sourceSystem;

    @Column(name = "external_event_id")
    public String externalEventId;

    @Column(name = "operation_type")
    public String operationType;

    @Column(name = "operation_desc")
    public String operationDesc;

    @Column(name = "target_type")
    public String targetType;

    @Column(name = "target_id")
    public Long targetId;

    @Column(name = "request_ip")
    public String requestIp;

    @Column(name = "request_method")
    public String requestMethod;

    @Column(name = "request_url")
    public String requestUrl;

    @Column(name = "request_params")
    public String requestParams;

    @Column(name = "response_result")
    public String responseResult;

    @Column(name = "status")
    public Integer status;

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    @Override
    public String toString() { return "AuditEntry[redacted]"; }
}
