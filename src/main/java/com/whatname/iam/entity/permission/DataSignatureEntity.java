package com.whatname.iam.entity.permission;

import jakarta.persistence.*;

/** Persistence mapping; never expose directly as an HTTP response. */
@Entity
@Table(name = "sys_data_signature")
public class DataSignatureEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "data_type")
    public String dataType;

    @Column(name = "data_id")
    public Long dataId;

    @Column(name = "signature")
    public String signature;

    @Column(name = "sign_version")
    public Integer signVersion;

    @Column(name = "key_id")
    public String keyId;

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    public java.time.LocalDateTime updatedAt;

    @Override
    public String toString() { return "DataSignatureEntity[redacted]"; }
}
