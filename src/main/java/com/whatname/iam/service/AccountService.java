package com.whatname.iam.service;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.entity.Domain.*;
import com.whatname.iam.repository.*;
import com.whatname.iam.service.auth.PasswordHasher;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {
  private final DomainRepository db;
  private final AccountRepository accounts;
  private final AccessService access;
  private final SignatureService signatures;
  private final AuditService audit;
  private final PasswordHasher passwords;
  private final JwtTokenService jwt;

  public AccountService(
      DomainRepository db,
      AccountRepository accounts,
      AccessService access,
      SignatureService signatures,
      AuditService audit,
      PasswordHasher passwords,
      JwtTokenService jwt) {
    this.db = db;
    this.accounts = accounts;
    this.access = access;
    this.signatures = signatures;
    this.audit = audit;
    this.passwords = passwords;
    this.jwt = jwt;
  }

  // Return a failed result so its audit commits before the controller reports 401.
  public Map<String, Object> login(Map<String, Object> input) {
    String identifier = Input.text(input, "identifier", 100);
    String type = Input.text(input, "loginType", 16);
    if (!Set.of("PHONE", "USERNAME").contains(type)) Input.fail();
    var found =
        "PHONE".equals(type)
            ? accounts.findByPhone(PhoneNumbers.normalize(identifier))
            : accounts.findByUsername(identifier);
    var user = found.orElse(null);
    if (user != null) signatures.verify(user);
    boolean ok =
        user != null
            && user.status == 1
            && passwords.matches((String) input.get("password"), user.passwordHash);
    audit.record(
        user, user, "LOGIN", "USER", user == null ? null : user.id, ok ? "登录成功" : "凭据错误或账号不可用", ok);
    if (!ok) return Map.of("authenticated", false);
    return Map.of(
        "authenticated", true, "token", jwt.issue(user.id, user.sessionVersion), "user", user);
  }

  public Map<String, Object> me(String bearer) {
    var actor = access.current(bearer);
    return Map.of("user", actor, "menus", access.menus(actor, access.application("IAM").id));
  }

  public void logout(String bearer) {
    var actor = access.current(bearer);
    actor = db.lock(Account.class, actor.id);
    signatures.verify(actor);
    actor.sessionVersion++;
    signatures.sign(actor);
    audit.record(actor, actor, "LOGOUT", "USER", actor.id, "退出全部会话", true);
  }

  public void ownPassword(String bearer, Map<String, Object> input) {
    var actor = access.current(bearer);
    actor = db.lock(Account.class, actor.id);
    signatures.verify(actor);
    if (!passwords.matches((String) input.get("oldPassword"), actor.passwordHash))
      throw new BusinessException(INVALID_CREDENTIALS);
    setPassword(actor, Input.text(input, "password", 64));
    audit.record(actor, actor, "PASSWORD_CHANGE", "USER", actor.id, "修改本人密码", true);
  }

  public Map<String, Object> users(
      String bearer,
      String keyword,
      Long deptId,
      Integer status,
      Integer source,
      int page,
      int pageSize) {
    var actor = access.current(bearer);
    var codes =
        access.menus(actor, access.application("IAM").id).stream().map(m -> m.menuCode).toList();
    if (!codes.contains("IAM_USERS_VIEW") && !codes.contains("IAM_GRANTS_VIEW"))
      throw new BusinessException(PERMISSION_DENIED);
    if (page < 1 || pageSize < 1 || pageSize > 100) Input.fail();
    var visible = visibleDepartments(actor);
    if (deptId != null) {
      if (!visible.contains(deptId)) throw new BusinessException(OUT_OF_SCOPE);
      visible = List.of(deptId);
    }
    if (visible.isEmpty())
      return Map.of("items", List.of(), "total", 0, "page", page, "pageSize", pageSize);
    String query =
        "from Account u where u.deptId in ?1 and (u.username like ?2 or u.realName like ?2 or"
            + " u.employeeNo like ?2) and (?3 is null or u.status=?3) and (?4 is null or"
            + " u.source=?4)";
    String match = "%" + (keyword == null ? "" : keyword.strip()) + "%";
    var items =
        db.page(
            Account.class,
            query + " order by u.id",
            (page - 1) * pageSize,
            pageSize,
            visible,
            match,
            status,
            source);
    items.forEach(signatures::verify);
    var count =
        db.query(Long.class, "select count(u) " + query, visible, match, status, source).getFirst();
    return Map.of("items", items, "total", count, "page", page, "pageSize", pageSize);
  }

  public List<Long> visibleDepartments(Account actor) {
    return db.all(Department.class).stream()
        .filter(d -> access.within(actor, d.id))
        .map(d -> d.id)
        .toList();
  }

  public Account detail(String bearer, long id) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_USERS_VIEW");
    var target = db.get(Account.class, id);
    access.scope(actor, target);
    return target;
  }

  public Account temporary(String bearer, Map<String, Object> input) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_USERS_WRITE");
    var dept = db.get(Department.class, Input.id(input, "deptId"));
    access.scope(actor, dept);
    if (access.departmentChain(dept.id).stream()
        .anyMatch(d -> d.status != 1 || d.mergedIntoDeptId != null))
      throw new BusinessException(BUSINESS_RULE_VIOLATION);
    var user = new Account();
    user.username = Input.text(input, "username", 64);
    user.realName = Input.text(input, "realName", 64);
    user.phone = PhoneNumbers.normalize(Input.text(input, "phone", 20));
    user.deptId = dept.id;
    user.source = 2;
    String password = Input.text(input, "password", 64);
    Input.password(password);
    user.passwordHash = passwords.hash(password);
    db.save(user);
    signatures.sign(user);
    audit.record(actor, user, "USER_CREATE", "USER", user.id, "创建临时账号", true);
    return user;
  }

  public Account updateTemporary(String bearer, long id, Map<String, Object> input) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_USERS_WRITE");
    var target = db.lock(Account.class, id);
    access.scope(actor, target);
    if (target.source != 2) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    if (input.containsKey("realName")) target.realName = Input.text(input, "realName", 64);
    if (input.containsKey("phone"))
      target.phone = PhoneNumbers.normalize(Input.text(input, "phone", 20));
    signatures.sign(target);
    audit.record(actor, target, "USER_EDIT", "USER", id, "维护临时账号", true);
    return target;
  }

  public void status(String bearer, long id, Map<String, Object> input) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_USERS_WRITE");
    var target = db.lock(Account.class, id);
    access.scope(actor, target);
    if (target.source != 2) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    target.status = Input.number(input, "status", target.status, 1, 2);
    if (target.status != 1) {
      db.clearRoles(id);
      target.sessionVersion++;
    }
    signatures.sign(target);
    audit.record(actor, target, "USER_STATUS", "USER", id, "status=" + target.status, true);
  }

  public void scope(String bearer, long id, Map<String, Object> input) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_SCOPE_WRITE");
    var target = db.lock(Account.class, id);
    access.scope(actor, target);
    String scope = Input.text(input, "managementScope", 16);
    if (!Set.of("ALL", "DEPT_TREE").contains(scope)) Input.fail();
    if ("ALL".equals(scope) && !"ALL".equals(actor.managementScope))
      throw new BusinessException(OUT_OF_SCOPE);
    target.managementScope = scope;
    signatures.sign(target);
    audit.record(actor, target, "USER_SCOPE", "USER", id, "managementScope=" + scope, true);
  }

  public void resetPassword(String bearer, long id, Map<String, Object> input) {
    var actor = access.current(bearer);
    access.function(actor, "IAM_PASSWORD_RESET");
    var target = db.lock(Account.class, id);
    access.scope(actor, target);
    if (target.status == 3) throw new BusinessException(BUSINESS_RULE_VIOLATION);
    setPassword(target, Input.text(input, "password", 64));
    audit.record(actor, target, "PASSWORD_RESET", "USER", id, "管理者重置密码", true);
  }

  private void setPassword(Account target, String password) {
    Input.password(password);
    target.passwordHash = passwords.hash(password);
    target.sessionVersion++;
    signatures.sign(target);
  }
}
