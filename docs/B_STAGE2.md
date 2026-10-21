# B 第二阶段功能实现说明（资源与角色管理）

任务分支：`feat/resource-role-menu` 。

本文面向项目组交接，说明 B 第二阶段已实现内容、跨成员协作依赖、以及测试负责人需要同步的接口变更。

## 1. B 第二阶段已实现内容

### 1.1 资源管理（Application/Menu/API）

- 已完成资源写操作的管理鉴权接入（含 Bearer token 入口、管理员校验、功能权限校验）。
- 已完成核心写流程签名约束：
  - 变更前验签（防篡改）
  - 变更后重签
  - 新事实创建时初始化签名
- 已完成菜单树与菜单-API 关联的业务约束校验（类型、归属、版本冲突、循环关系等）。
- 已完成资源相关关键审计写入。

主要文件：

- `src/main/java/com/whatname/iam/service/resource/ResourceService.java`

### 1.2 角色管理（Role/RoleMenus）

- 已完成角色写操作管理鉴权接入。
- 已完成角色与角色-菜单映射的签名校验与重签流程。
- 已完成角色菜单替换的版本控制与业务约束校验。
- 已完成角色相关关键审计写入。

主要文件：

- `src/main/java/com/whatname/iam/service/role/RoleService.java`

### 1.3 只读与写入的管理员校验分流

- 已按分工要求区分只读与写入管理员校验：
  - 查询入口使用只读版本（不加写锁）
  - 写入口继续使用原有带锁版本
- 目标：修复 MySQL 只读事务中 `FOR UPDATE` 失败问题，同时不改变 A/其他写入口锁语义。

主要文件：

- `src/main/java/com/whatname/iam/service/admin/AdminPolicyService.java`

### 1.4 锁等待后的会话失效防护

- 已在 B 写入口实现“锁前/锁后分离”：
  - 锁前：仅 JWT 声明验证与 userId 提取
  - 锁后：重新核验当前会话状态与功能权限
- 锁后复核不复用锁前旧快照（通过新事务重检当前状态），用于阻断“等待管理员锁期间会话失效后旧 token 继续写入”。

主要文件：

- `src/main/java/com/whatname/iam/service/resource/ResourceService.java`
- `src/main/java/com/whatname/iam/service/role/RoleService.java`

## 2. 对全体成员的必要协作说明

| 责任方 | 协作事项 | 影响 |
|---|---|---|
| D | 提供/合并最终 `ResourceDataSecurity` 适配器接口与实现（含 `verifyBeforeMutation/signAfterMutation/createInitial` 及集合接口） | B 可从当前签名流程平滑切换到 D 新接口做联合验收 |
| A | 继续保持写入口使用带锁管理员校验，不回退为只读校验 | 保证 A 写并发与锁序设计不被破坏 |
| C | 与 B 联调管理权限链路（功能码、范围、拒绝语义） | 保证 C/B 写路径拒绝/通过行为一致 |
| 测试负责人 | 同步 B 服务签名变更（见第 3 节）并更新测试 token 依赖 | 解除 `BResourceRoleMySqlIntegrationTest` 编译失败 |
| 全组 | 对“缺签名旧数据”走受控初始化流程，不在普通更新路径自动补签 | 保证篡改检测与签名治理边界一致 |

## 3. 给测试负责人的接口变更说明（必须同步）

### 3.1 变更原则

- `ResourceService` / `RoleService` 的公开入口已统一要求 `String bearer`。

### 3.2 资源服务签名变更

| 方法 | 新签名 |
|---|---|
| listApplications | `listApplications(String bearer, PageRequest pageRequest)` |
| createApplication | `createApplication(String bearer, CreateApplicationRequest request)` |
| updateApplication | `updateApplication(String bearer, long appId, UpdateApplicationRequest request)` |
| listMenus | `listMenus(String bearer, long appId)` |
| createMenu | `createMenu(String bearer, long appId, CreateMenuRequest request)` |
| updateMenu | `updateMenu(String bearer, long menuId, UpdateMenuRequest request)` |
| listApis | `listApis(String bearer, long appId, String httpMethod, PageRequest pageRequest)` |
| createApi | `createApi(String bearer, long appId, CreateApiRequest request)` |
| updateApi | `updateApi(String bearer, long apiId, UpdateApiRequest request)` |
| listMenuApis | `listMenuApis(String bearer, long menuId)` |
| replaceMenuApis | `replaceMenuApis(String bearer, long menuId, ReplaceMenuApisRequest request)` |

### 3.3 角色服务签名变更

| 方法 | 新签名 |
|---|---|
| listRoles | `listRoles(String bearer, PageRequest pageRequest)` |
| createRole | `createRole(String bearer, CreateRoleRequest request)` |
| getRole | `getRole(String bearer, long roleId)` |
| updateRole | `updateRole(String bearer, long roleId, UpdateRoleRequest request)` |
| listRoleMenus | `listRoleMenus(String bearer, long roleId)` |
| replaceRoleMenus | `replaceRoleMenus(String bearer, long roleId, ReplaceRoleMenusRequest request)` |

### 3.4 测试侧最小改动建议

1. 为 `BResourceRoleMySqlIntegrationTest` 统一注入可用 `bearer`。  
2. 将所有旧调用替换为带 `bearer` 的新签名调用。  
3. 不新增或依赖任何无认证服务重载。  

## 4. 当前状态

- B 第二阶段核心实现已落地并可编译通过。
- 标准测试中因旧测试调用未补 `bearer` 导致的编译问题，属于测试代码同步范围，不通过在 B 服务层增加绕过认证重载来处理。

