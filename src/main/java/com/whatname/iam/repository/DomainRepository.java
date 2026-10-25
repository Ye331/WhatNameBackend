package com.whatname.iam.repository;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.entity.Domain.*;
import jakarta.persistence.*;
import java.util.*;
import org.springframework.stereotype.Repository;

/** JPA only: JPQL for reads, entity persistence for writes, one row lock per edited object. */
@Repository
public class DomainRepository {
  @PersistenceContext private EntityManager em;

  public <T extends Row> T get(Class<T> type, long id) {
    T row = em.find(type, id);
    if (row == null) throw new BusinessException(RESOURCE_NOT_FOUND);
    return row;
  }

  public <T extends Row> T lock(Class<T> type, long id) {
    T row = em.find(type, id, LockModeType.PESSIMISTIC_WRITE);
    if (row == null) throw new BusinessException(RESOURCE_NOT_FOUND);
    // Refresh in case this entity was previously read during authorization.
    em.refresh(row, LockModeType.PESSIMISTIC_WRITE);
    return row;
  }

  public <T> List<T> query(Class<T> type, String jpql, Object... args) {
    var q = em.createQuery(jpql, type);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return q.getResultList();
  }

  public <T> List<T> page(Class<T> type, String jpql, int offset, int limit, Object... args) {
    var q = em.createQuery(jpql, type).setFirstResult(offset).setMaxResults(limit);
    for (int i = 0; i < args.length; i++) q.setParameter(i + 1, args[i]);
    return q.getResultList();
  }

  public <T extends Row> List<T> all(Class<T> type) {
    return query(type, "from " + type.getAnnotation(Entity.class).name() + " order by id");
  }

  public <T extends Row> T save(T row) {
    if (row.id == null) em.persist(row);
    return row;
  }

  public void remove(Row row) {
    em.remove(row);
  }

  public void flush() {
    em.flush();
  }

  public void clear() {
    em.clear();
  }

  public List<Long> roles(long user) {
    return query(
        Long.class, "select r.roleId from UserRole r where r.userId=?1 order by r.roleId", user);
  }

  public List<Long> menus(long role) {
    return query(
        Long.class,
        "select r.menuId from RolePermission r where r.roleId=?1 order by r.menuId",
        role);
  }

  public List<Long> apis(long menu) {
    return query(
        Long.class, "select r.apiId from MenuApi r where r.menuId=?1 order by r.apiId", menu);
  }

  public void clearRoles(long user) {
    query(UserRole.class, "from UserRole where userId=?1", user).forEach(this::remove);
  }

  public void clearMenus(long role) {
    query(RolePermission.class, "from RolePermission where roleId=?1", role).forEach(this::remove);
  }

  public void clearApis(long menu) {
    query(MenuApi.class, "from MenuApi where menuId=?1", menu).forEach(this::remove);
  }
}
