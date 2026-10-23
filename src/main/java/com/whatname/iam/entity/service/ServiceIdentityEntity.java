package com.whatname.iam.entity.service;

import jakarta.persistence.*;

/** Persistence mapping; never expose directly as an HTTP response. */
@Entity
@Table(name = "sys_service_identity")
public class ServiceIdentityEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "app_id")
    public Long appId;

    @Column(name = "client_id")
    public String clientId;

    @Column(name = "credential_hash")
    public String credentialHash;

    @Column(name = "public_key")
    public String publicKey;

    @Column(name = "key_id")
    public String keyId;

    @Column(name = "allowed_role_ids", columnDefinition = "json")
    public String allowedRoleIds;

    @Column(name = "allowed_dept_ids", columnDefinition = "json")
    public String allowedDeptIds;

    @Column(name = "allowed_operations", columnDefinition = "json")
    public String allowedOperations;

    @Column(name = "allowed_scope")
    public String allowedScope;

    @Column(name = "status")
    public Integer status;

    @Column(name = "created_at")
    public java.time.LocalDateTime createdAt;

    @Column(name = "updated_at")
    public java.time.LocalDateTime updatedAt;

    @Override
    public String toString() { return "ServiceIdentityEntity[redacted]"; }
}
