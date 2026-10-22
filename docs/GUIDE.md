# 功能与代码阅读说明

## 一、实现边界

- 一个 Spring Boot 后端、一个 Vue 管理前端、一套 MySQL 数据库。
- Controller 接收参数；Service 实现业务步骤与事务；Mapper 执行 SQL。
- 不做角色继承、显式拒绝、角色先决约束、权限自动过期、微服务、消息队列或 Redis。
- 签名、审计、事务、授权来源精确回收、事件幂等、批量任务持久化都来自详细设计（docs/contracts），属于业务要求，不是可删的重复代码。

## 二、权限规则

### 功能权限：RBAC0

用户关联角色，角色关联菜单，菜单关联 API。多个有效角色的权限取并集；用户不直接获得菜单或 API。

`PermissionService` 每次请求都从数据库读取当前事实并验签：JWT 只证明登录身份，账号状态、会话版本、注销记录和当前授权都要重新检查。回收最后一个授权来源后，下一次请求立即被拒；还有其他有效来源时，角色仍然有效。

### 管理资格：与功能权限同时校验

一个管理操作必须同时满足四点：当前会话有效、具备该操作的功能权限、管理员任命有效、目标在可管理范围内。资源与角色维护还要求一级管理员。

可授权角色的规则（集中在 `AdminRules.checkRole`）：

1. 一级管理员可以授予有效的业务角色或管理角色；
2. 下级管理员只能授予上级放入其可授权清单的角色；
3. 向下委派清单时，目标等级必须更低且在操作者范围内；
4. 获得管理角色不会自动产生管理员任命；
5. 超级角色不能通过普通的角色、授权或委派入口操作。

## 三、按功能读代码

| 功能 | 先读的 Service | 要能讲清楚的问题 |
| --- | --- | --- |
| 登录与账号 | `auth/AuthService`、`user/UserService` | 密码如何校验，JWT 如何产生，注销为何能让旧令牌失效 |
| 组织与人事 | `department/DepartmentService`、`hr/HrService` | HR 事件如何认证、去重、更新组织并回收授权 |
| 应用与权限资源 | `resource/ResourceService` | 应用、菜单、API 和映射如何维护 |
| 角色与授权 | `role/RoleService`、`grant/GrantService`、`grant/GrantOperations` | 角色定义与授予的区别，多来源为何不能直接删用户角色 |
| 管理员与范围 | `admin/AdminService`、`admin/AdminPolicyService`、`admin/AdminRules` | 功能权限、任命、部门范围如何共同生效 |
| 在线鉴权 | `permission/PermissionService` | 菜单并集与 API 判定如何计算 |
| 初始化与兜底 | `system/InitializationService` | 空库如何导入数据、登记资源、创建超级用户 |

各模块共用的安全类都是普通的 `@Service`，直接注入使用，没有接口或适配层：

| 类 | 作用 |
| --- | --- |
| `permission/DataSignatureService` | 事实签名与验签（Ed25519） |
| `audit/AuditService` | 操作审计，证据带签名封印 |
| `admin/RequestSecurity` | 管理请求的身份与功能复核，记录本次授权依据 |
| `admin/AdminIntegrity`、`admin/AdminAudit` | 管理员数据的验签/签名、审计 |
| `grant/GrantIntegrity`、`grant/GrantAudit` | 授权数据的验签/签名、审计 |
| `common/security/AccountSecurity` | 账号模块的范围准备、验签、功能检查、签名、审计 |
| `department/DepartmentAccessService` | 部门模块的管理员认证、HR 服务认证、组织验签与审计 |
| `grant/HrGrantCollaboration` | HR 调岗/离职对授权的影响 |
| `event/ExternalEventService` | HR/OA 事件的幂等与事务 |
| `permission/ServiceApiCaller`、`ServiceOaCaller` | 业务系统、OA 的请求签名认证 |

阅读顺序：Controller 看请求字段 → Service 按"校验 → 权限/范围 → 修改 → 签名/审计"读 → Mapper/XML 看改了哪些表 → 同名测试先看正常场景，再看拒绝与回滚。

## 四、三条主流程

**登录与访问**：`AuthService.login` 查账号、验签、校验密码 → 生成 JWT、写登录日志。之后每次请求由 `PermissionService.currentUser` 检查会话，`requireFunction` 或 `check` 检查当前功能权限。

**授予与回收**：`GrantController` → `GrantService` → `GrantOperations` → `AdminPolicyService` 校验范围与角色资格 → 检查功能权限和授权版本 → 修改授权来源与有效关系 → 更新版本、签名、审计，同一事务提交。只回收指定来源；另一个岗位或人工来源仍有效时，用户的角色继续有效。

**资源或角色维护**：Controller → 业务 Service → `ManagementAccessService`（会话、一级管理员、功能权限）→ 业务规则 → 修改前验签 → 写入 → 修改后签名 → 审计 → 提交。取得管理锁后会重新读取会话，防止等锁期间已注销的令牌继续写入。

## 五、初始化与演示

三份 Excel 位于工作区 data/。在空库上执行一次：

```powershell
.\scripts\dev.ps1 init -SuperPhone 13900000000
```

脚本先运行 `prepare-data.py` 校验并整理 Excel（生成 target/prepared-data.json，含个人数据，不提交），再以初始化模式启动后端，由 `InitializationService` 在**一个事务**里完成：

1. 导入 111 个部门和 10000 名员工。员工为 HR 同步账号，密码未初始化，须本人用短信验证码首次设密；登录名为姓名拼音首字母，重名追加工号后六位（详细设计第 10 节）；
2. 登记本系统（IAM，14 个菜单及其 API）和两个演示业务系统：OA 协同办公（4 个菜单）、人力资源（4 个菜单）；
3. 创建角色：超级管理员（超级角色）、一级管理员（管理角色）、一线员工基础权限（考勤打卡 + OA 请假，用于演示 2000+ 人批量授权）；
4. 创建超级用户：持有超级角色并任命为一级管理员。密码由运维输入（或环境变量 `IAM_INIT_SUPER_PASSWORD`），须符合密码策略，代码中没有默认密码；
5. 为全部数据签名，写一条初始化审计。

已有账号时拒绝执行，任何一步失败都整体回滚。

**兜底恢复**：唯一管理员误删自己的权限后，执行 `.\scripts\dev.ps1 recover`，重新启用超级用户的一级任命和超级角色。恢复前先验签（不绕过篡改检测），并写审计。

**演示主线**：超级用户登录 → 任命部门管理员并授予"一级管理员"或可委派角色 → 部门管理员只能看到本部门及下级 → 批量授予"一线员工基础权限" → 业务系统调用 `/auth/check`：有权限放行，回收后下一次调用即被拒。

## 六、详细设计条目对照

| 功能条目 | 代码入口 |
| --- | --- |
| 1.1–1.3 账号生成、临时账号、账号状态 | `UserService`、`HrService` |
| 1.4–1.5 身份凭据、登录会话 | `AuthService`（scrypt 哈希、错误锁定、会话版本、注销记录） |
| 2.1–2.6 组织、HR 同步、调岗、离职、合并、冻结 | `DepartmentService`、`HrService`、`HrGrantCollaboration` |
| 3.1–3.5 应用、菜单、API、映射、有效性 | `ResourceService` |
| 4.1–4.3 角色、角色菜单、用户角色 | `RoleService`、`GrantOperations` |
| 4.4 批量授权 | `BatchService`、`BatchWorker`（持久化任务，逐项事务） |
| 4.5 OA 审批 | `OaDecisionService`、`OaGrantOperations` |
| 5.1–5.5 任命、等级、范围、合法性、撤销 | `AdminService`、`AdminPolicyService`、`AdminRules` |
| 5.6 超级用户初始化与兜底 | `InitializationService` |
| 6.1–6.5 身份状态、菜单计算、API 判定、实时生效 | `PermissionService` |

## 七、已知限制

- **鉴权性能**：需求目标是鉴权 ≤20ms、上限 50ms，并提示"别把鉴权做成每次查库"。本项目出于安全考虑选择每次请求读库并验签、不使用缓存，以保证撤权立即生效、篡改立即发现。代价是单次鉴权需要多次查询和验签，尚未做正式性能测试，很可能达不到 20ms。答辩时应说明这一取舍。
- 同样的原因，管理端查询也偏慢：在 1 万名员工的真实数据上，超级用户查询第一页用户列表约 3.6 秒（需要逐个验签范围内的账号）。整套初始化约 5 分钟（一次性操作）。
- 当前管理写入采用较宽的固定加锁顺序，以保证并发正确性。
- HR/OA 服务身份（`sys_service_identity`）需要按各自的公钥另行登记，初始化不生成。
- 需求写的是"重名拼手机号后几位"，详细设计写的是"追加 SG 工号后六位"，实现按详细设计。

## 八、接口增补（相对 contracts）

| 接口/配置 | 约定 |
| --- | --- |
| `GET /auth/menus` | 增加 `management`，只描述当前登录人：`{ "appointed": false }` 或 `{ "appointed": true, "adminLevel": 1 }` |
| 菜单列表 / API 列表 | 补充 `menuCode`、`menuName`、`path` / `apiName` |
| 资源、角色 PATCH | 必须包含实际修改字段，只有 `reason` 时返回输入错误 |
| 平台编码 | 后端 `iam.permission.management-app-code` 与前端 `VITE_IAM_APP_CODE` 一致，默认 `IAM` |

验证见 [测试说明](TESTING.md)，运行见 [README](../README.md)，设计原件见 [contracts](contracts/README.md)。
