package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.DomainRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class GrantService {
  private final DomainRepository db;
  private final AccessService access;
  private final SignatureService signatures;
  private final AuditService audit;

  public GrantService(
      DomainRepository db, AccessService access, SignatureService signatures, AuditService audit) {
    this.db = db;
    this.access = access;
    this.signatures = signatures;
    this.audit = audit;
  }

  public List<Long> roles(String bearer, long id) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_GRANTS_VIEW");
    var target = db.get(Account.class, id);
    access.scope(actor, target);
    return db.roles(id);
  }

  public void replace(String bearer, long id, List<Long> roleIds) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_GRANTS_WRITE");
    var target = db.lock(Account.class, id);
    access.scope(actor, target);
    checkTarget(target, roleIds.isEmpty());
    var before = db.roles(id);
    var desired = new TreeSet<>(roleIds);
    for (long role : desired) checkRole(role);
    for (var row : db.query(UserRole.class, "from UserRole where userId=?1", id))
      if (!desired.contains(row.roleId)) db.remove(row);
    for (long role : desired) if (!before.contains(role)) db.save(new UserRole(id, role));
    signatures.sign(target);
    audit.record(actor, target, "USER_ROLES_REPLACE", "USER", id, before + " -> " + desired, true);
  }

  public void change(String bearer, long user, long role, boolean grant) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_GRANTS_WRITE");
    var target = db.lock(Account.class, user);
    access.scope(actor, target);
    apply(actor, target, role, grant);
  }

  public void oa(long user, long role, boolean grant) {
    var target = db.lock(Account.class, user);
    signatures.verify(target);
    apply(null, target, role, grant);
  }

  private void apply(Account actor, Account target, long role, boolean grant) {
    checkTarget(target, !grant);
    var definition = db.get(Role.class, role);
    signatures.verify(definition);
    if (grant) checkRole(role);
    var existing =
        db.query(UserRole.class, "from UserRole where userId=?1 and roleId=?2", target.id, role);
    if (grant && existing.isEmpty()) db.save(new UserRole(target.id, role));
    if (!grant) existing.forEach(db::remove);
    signatures.sign(target);
    audit.record(
        actor,
        target,
        actor == null ? "OA_ROLE_CHANGE" : "USER_ROLE_CHANGE",
        "ROLE",
        role,
        grant ? "GRANT" : "REVOKE",
        true);
  }

  private void checkRole(long id) {
    var role = db.get(Role.class, id);
    signatures.verify(role);
    if (role.status != 1) throw new BusinessException(BUSINESS_RULE_VIOLATION);
  }

  private void checkTarget(Account target, boolean onlyRevoke) {
    if (!onlyRevoke
        && (target.status != 1
            || access.departmentChain(target.deptId).stream()
                .anyMatch(d -> d.status != 1 || d.mergedIntoDeptId != null)))
      throw new BusinessException(BUSINESS_RULE_VIOLATION);
  }
}
