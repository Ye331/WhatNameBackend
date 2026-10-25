package com.whatname.iam.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/** Eleven tables. Associations use explicit IDs so the RBAC joins remain easy to follow. */
public final class Domain {
  private Domain() {}

  @MappedSuperclass
  public abstract static class Row {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;
  }

  @Entity(name = "Account")
  @Table(name = "sys_user")
  public static class Account extends Row {
    public String username;
    public String realName;
    public String phone;
    @JsonIgnore public String passwordHash;
    public String employeeNo;
    public Long deptId;
    public int status = 1;
    public int source = 1;
    public Integer employmentType;
    public String managementScope = "DEPT_TREE";
    @JsonIgnore public long sessionVersion = 1;
    public LocalDateTime createdAt = LocalDateTime.now();
  }

  @Entity(name = "Department")
  @Table(name = "sys_dept")
  public static class Department extends Row {
    public long parentId;
    public String deptCode;
    public String deptName;
    public int status = 1;
    public Long mergedIntoDeptId;
  }

  @Entity(name = "Application")
  @Table(name = "sys_application")
  public static class Application extends Row {
    public String appCode;
    public String appName;
    public String appDesc;
    public int status = 1;
  }

  @Entity(name = "Menu")
  @Table(name = "sys_menu")
  public static class Menu extends Row {
    public Long appId;
    public long parentId;
    public String menuCode;
    public String menuName;
    public int menuType = 2;
    public String path;
    public int sortOrder;
    public int status = 1;
  }

  @Entity(name = "Api")
  @Table(name = "sys_api")
  public static class Api extends Row {
    public Long appId;
    public String apiCode;
    public String apiName;
    public String apiPath;
    public String canonicalPath;
    public String httpMethod;
    public int status = 1;
  }

  @Entity(name = "MenuApi")
  @Table(name = "sys_menu_api")
  public static class MenuApi extends Row {
    public Long menuId;
    public Long apiId;

    public MenuApi() {}

    public MenuApi(long menuId, long apiId) {
      this.menuId = menuId;
      this.apiId = apiId;
    }
  }

  @Entity(name = "Role")
  @Table(name = "sys_role")
  public static class Role extends Row {
    public String roleCode;
    public String roleName;
    public String roleDesc;
    public int status = 1;
  }

  @Entity(name = "UserRole")
  @Table(name = "sys_user_role")
  public static class UserRole extends Row {
    public Long userId;
    public Long roleId;

    public UserRole() {}

    public UserRole(long userId, long roleId) {
      this.userId = userId;
      this.roleId = roleId;
    }
  }

  @Entity(name = "RolePermission")
  @Table(name = "sys_role_permission")
  public static class RolePermission extends Row {
    public Long roleId;
    public Long menuId;

    public RolePermission() {}

    public RolePermission(long roleId, long menuId) {
      this.roleId = roleId;
      this.menuId = menuId;
    }
  }

  @Entity(name = "DataSignature")
  @Table(name = "sys_data_signature")
  public static class DataSignature extends Row {
    public String dataType;
    public Long dataId;
    public String signature;
  }

  @Entity(name = "OperationLog")
  @Table(name = "sys_operation_log")
  public static class OperationLog extends Row {
    public Long userId;
    public Long targetUserId;
    public Long deptId;
    public String operationType;
    public String targetType;
    public Long targetId;
    public String detail;
    public boolean success;
    public LocalDateTime createdAt = LocalDateTime.now();
  }
}
