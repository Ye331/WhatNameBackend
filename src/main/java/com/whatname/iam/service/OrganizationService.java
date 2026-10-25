package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.PhoneNumbers;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.*;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.service.hr.HrLoginName;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class OrganizationService {
  private final DomainRepository db;
  private final AccountRepository accounts;
  private final AccessService access;
  private final SignatureService signatures;
  private final AuditService audit;
  private final PasswordHasher passwords;

  public OrganizationService(
      DomainRepository db,
      AccountRepository accounts,
      AccessService access,
      SignatureService signatures,
      AuditService audit,
      PasswordHasher passwords) {
    this.db = db;
    this.accounts = accounts;
    this.access = access;
    this.signatures = signatures;
    this.audit = audit;
    this.passwords = passwords;
  }

  public List<Department> departments(String bearer) {
    var actor = access.current(bearer);
    // This catalog also supplies the user department selector, never an administrator gate.
    var codes =
        access.menus(actor, access.application("IAM").id).stream().map(m -> m.menuCode).toList();
    if (!codes.contains("IAM_DEPTS_VIEW")
        && !codes.contains("IAM_USERS_VIEW")
        && !codes.contains("IAM_GRANTS_VIEW")) throw new BusinessException(PERMISSION_DENIED);
    return db.all(Department.class).stream().filter(d -> access.within(actor, d.id)).toList();
  }

  public void freeze(String bearer, long id, boolean frozen) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_DEPTS_FREEZE");
    var dept = db.lock(Department.class, id);
    access.scope(actor, dept);
    dept.status = frozen ? 0 : 1;
    signatures.sign(dept);
    audit.record(actor, null, "DEPARTMENT_FREEZE", "DEPARTMENT", id, "frozen=" + frozen, true);
  }

  public Department syncDepartment(Map<String, Object> input) {
    String code = Input.text(input, "deptCode", 64);
    var found =
        db.query(Department.class, "from Department where deptCode=?1", code).stream()
            .findFirst()
            .orElse(null);
    var dept = found == null ? new Department() : db.lock(Department.class, found.id);
    if (found != null) signatures.verify(dept);
    if (dept.mergedIntoDeptId != null) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    long parent =
        input.get("parentId") == null || "0".equals(input.get("parentId").toString())
            ? 0
            : Input.id(input, "parentId");
    if (parent != 0
        && access.departmentChain(parent).stream().anyMatch(p -> Objects.equals(p.id, dept.id)))
      throw new BusinessException(BUSINESS_RULE_VIOLATION);
    dept.deptCode = code;
    dept.deptName = Input.text(input, "deptName", 128);
    dept.parentId = parent;
    db.save(dept);
    signatures.sign(dept);
    audit.record(null, null, "HR_DEPARTMENT_SYNC", "DEPARTMENT", dept.id, code, true);
    return dept;
  }

  public Account syncEmployee(Map<String, Object> input) {
    String employeeNo = Input.text(input, "employeeNo", 64);
    var found = accounts.findByEmployeeNo(employeeNo).orElse(null);
    var user = found == null ? new Account() : db.lock(Account.class, found.id);
    if (found != null) {
      signatures.verify(user);
      if (user.status == 3) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    }
    var dept = db.get(Department.class, Input.id(input, "deptId"));
    signatures.verify(dept);
    if (dept.mergedIntoDeptId != null) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    user.realName = Input.text(input, "realName", 64);
    user.phone = PhoneNumbers.normalize(Input.text(input, "phone", 20));
    if (found == null) {
      user.username = uniqueUsername(user.realName, user.phone);
      user.employeeNo = employeeNo;
      user.source = 1;
      String password = Input.text(input, "password", 64);
      Input.password(password);
      user.passwordHash = passwords.hash(password);
    } else if (!Objects.equals(user.deptId, dept.id)) db.clearRoles(user.id);
    user.deptId = dept.id;
    user.employmentType =
        Input.number(
            input, "employmentType", user.employmentType == null ? 1 : user.employmentType, 1, 2);
    user.status = Input.number(input, "status", user.status, 1, 2);
    if (found != null && user.status != 1) user.sessionVersion++;
    db.save(user);
    signatures.sign(user);
    audit.record(null, user, "HR_EMPLOYEE_SYNC", "USER", user.id, employeeNo, true);
    return user;
  }

  public void transfer(long id, long deptId) {
    var user = db.lock(Account.class, id);
    signatures.verify(user);
    if (user.source != 1 || user.status == 3) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    var dept = db.get(Department.class, deptId);
    signatures.verify(dept);
    if (dept.mergedIntoDeptId != null) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    if (!user.deptId.equals(deptId)) {
      db.clearRoles(id);
      user.deptId = deptId;
    }
    signatures.sign(user);
    audit.record(null, user, "HR_TRANSFER", "USER", id, "deptId=" + deptId, true);
  }

  public void terminate(long id) {
    var user = db.lock(Account.class, id);
    signatures.verify(user);
    if (user.source != 1) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    if (user.status != 3) {
      user.status = 3;
      db.clearRoles(id);
      user.sessionVersion++;
    }
    signatures.sign(user);
    audit.record(null, user, "HR_TERMINATE", "USER", id, "离职，保留账号", true);
  }

  public void merge(long source, long target) {
    if (source == target) Input.fail();
    // Two department rows only, ordered to avoid two opposite merge requests deadlocking.
    db.lock(Department.class, Math.min(source, target));
    db.lock(Department.class, Math.max(source, target));
    var from = db.get(Department.class, source);
    var into = db.get(Department.class, target);
    signatures.verify(from);
    signatures.verify(into);
    if (Objects.equals(from.mergedIntoDeptId, target)) return;
    if (from.mergedIntoDeptId != null
        || into.mergedIntoDeptId != null
        || access.departmentChain(target).stream().anyMatch(d -> d.id == source))
      throw new BusinessException(BUSINESS_RULE_VIOLATION);
    for (var user : db.query(Account.class, "from Account where deptId=?1 order by id", source)) {
      user = db.lock(Account.class, user.id);
      signatures.verify(user);
      user.deptId = target;
      signatures.sign(user);
    }
    for (var child :
        db.query(Department.class, "from Department where parentId=?1 order by id", source)) {
      child = db.lock(Department.class, child.id);
      signatures.verify(child);
      child.parentId = target;
      signatures.sign(child);
    }
    from.status = 0;
    from.mergedIntoDeptId = target;
    signatures.sign(from);
    audit.record(null, null, "HR_DEPARTMENT_MERGE", "DEPARTMENT", source, "target=" + target, true);
  }

  public String uniqueUsername(String name, String phone) {
    String base = HrLoginName.initials(name), candidate = base;
    if (accounts.findByUsername(candidate).isPresent())
      candidate = base + phone.substring(phone.length() - 4);
    int n = 2;
    while (accounts.findByUsername(candidate).isPresent())
      candidate = base + phone.substring(phone.length() - 4) + n++;
    return candidate;
  }
}
