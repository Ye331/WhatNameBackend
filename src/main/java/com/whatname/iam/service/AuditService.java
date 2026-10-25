package com.whatname.iam.service;

import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.DomainRepository;
import org.springframework.stereotype.Service;

@Service
public class AuditService {
  private final DomainRepository db;

  public AuditService(DomainRepository db) {
    this.db = db;
  }

  public void record(
      Account actor,
      Account target,
      String operation,
      String type,
      Long targetId,
      String detail,
      boolean success) {
    var log = new OperationLog();
    log.userId = actor == null ? null : actor.id;
    log.targetUserId = target == null ? null : target.id;
    log.deptId = target != null ? target.deptId : actor == null ? null : actor.deptId;
    if ("DEPARTMENT".equals(type) && targetId != null) log.deptId = targetId;
    log.operationType = operation;
    log.targetType = type;
    log.targetId = targetId;
    log.detail = detail;
    log.success = success;
    db.save(log);
  }
}
