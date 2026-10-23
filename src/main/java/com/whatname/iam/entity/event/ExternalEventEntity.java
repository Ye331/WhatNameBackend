package com.whatname.iam.entity.event;

import jakarta.persistence.*;

/** Persistence mapping; never expose directly as an HTTP response. */
@Entity
@Table(name = "sys_external_event")
public class ExternalEventEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "source_system")
    public String sourceSystem;

    @Column(name = "event_id")
    public String eventId;

    @Column(name = "event_type")
    public String eventType;

    @Column(name = "event_version")
    public Long eventVersion;

    @Column(name = "payload_digest")
    public String payloadDigest;

    @Column(name = "target_type")
    public String targetType;

    @Column(name = "target_ref")
    public String targetRef;

    @Column(name = "processing_status")
    public String processingStatus;

    @Column(name = "processing_stage")
    public String processingStage;

    @Column(name = "payload_json")
    public String payloadJson;

    @Column(name = "result_data", columnDefinition = "json")
    public String resultData;

    @Column(name = "result_summary")
    public String resultSummary;

    @Column(name = "occurred_at")
    public java.time.LocalDateTime occurredAt;

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    public java.time.LocalDateTime updatedAt;

    @Override
    public String toString() { return "ExternalEventEntity[redacted]"; }
}
