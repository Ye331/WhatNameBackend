package com.whatname.iam.entity.admin;

import jakarta.persistence.*;

/** Persistence mapping; never expose directly as an HTTP response. */
@Entity
@Table(name = "sys_admin")
public class AdminAppointmentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    public Long id;

    @Column(name = "user_id")
    public Long userId;

    @Column(name = "admin_level")
    public Integer adminLevel;

    @Column(name = "dept_id")
    public Long deptId;

    @Column(name = "appointed_by")
    public Long appointedBy;

    @Column(name = "appointed_at")
    public java.time.LocalDateTime appointedAt;

    @Column(name = "status")
    public Integer status;

    @Column(name = "delegable_revision")
    public Integer delegableRevision;

    @Override
    public String toString() { return "AdminAppointmentEntity[redacted]"; }
}
