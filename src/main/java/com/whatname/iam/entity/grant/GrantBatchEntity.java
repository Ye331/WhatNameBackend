package com.whatname.iam.entity.grant;

import jakarta.persistence.*;

/** Persistence mapping; never expose directly as an HTTP response. */
@Entity
@Table(name = "sys_grant_batch")
public class GrantBatchEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "actor_user_id")
    public Long actorUserId;

    @Column(name = "idempotency_key")
    public String idempotencyKey;

    @Column(name = "payload_digest")
    public String payloadDigest;

    @Column(name = "operation")
    public String operation;

    @Column(name = "role_id")
    public Long roleId;

    @Column(name = "reason")
    public String reason;

    @Column(name = "processing_status")
    public String processingStatus;

    @Column(name = "total_count")
    public Integer totalCount;

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    public java.time.LocalDateTime updatedAt;

    @Override
    public String toString() { return "GrantBatchEntity[redacted]"; }
}
