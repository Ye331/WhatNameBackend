# D 模块内部契约（PR01 基线）

状态：**待组织者确认后才能作为后续 PR 的实现依据**。本文只定义 Java 进程内的 Service 协作边界；不新增 HTTP、RPC、DAO、Redis 或数据库表。

## 共同规则

- 调用方从可信会话或已验证的服务身份取得操作者，不能把请求体中的 `userId` 当作操作者。
- 业务事实、对应签名和关键审计必须加入调用方现有的同一个 MySQL 事务；D 的内部方法不得自行提交或另开业务事务。
- 所有 ID 在外部/JSON 表示为十进制字符串。内部 Java 可使用 `long`，但不得混用 `gs-`、`B-` 等前缀。
- 身份、签名、数据库或审计依赖不可用时安全拒绝：分别使用既有 `INVALID_TOKEN`、`INVALID_SERVICE`、`INTEGRITY_FAILURE`、`AUTH_STATE_UNAVAILABLE`、`AUDIT_UNAVAILABLE`；不得默认允许。
- 本 PR 不实现以下能力，也不提供生产放行路径：JWT 解析、Ed25519 签名验签、M5 对象范围、AU02/AU03/US06、LG01--LG04。

## 拟定的内部能力

| 能力 | 调用方 | 可信输入与返回 | 事务/拒绝语义 |
|---|---|---|---|
| 当前功能权限检查 | A/B/C 的管理入口、后续菜单/API查询 | 已认证的主体、应用、实际 HTTP 方法和路由；返回仅对本次请求有效的允许/拒绝、理由和证据快照 | 在新的主库一致性读事务中读取；无有效事实、验签失败或审计失败均不能返回允许 |
| 受保护事实签名/验签 | A/B/C 事实写入与 D 鉴权 | 已冻结的对象类型、ID、字段快照；返回签名记录或验签结论 | 与事实变更同一事务；验签失败不自动重签或修复 |
| 管理操作审计 | A/B/C 的管理、HR、OA Service | 可信 `requestId`、操作者、目标、动作、结果、白名单证据 | 与业务写入同一事务；写入失败使业务失败；禁止记录完整凭据、密码、验证码、私钥或完整 JWT |
| 鉴权审计 | D 的权限决策 | 决策、主体、真实应用/API、请求方法/路由、`decisionId`、证据 | `AUTH_CHECK` 仅在日志同步成功后才可返回允许；证据至少含 `user_role`、`role_permission`、`menu_api`、`state`、`signatures` |
| 登录日志 | A 的认证 Service | 已认证流程的成功/失败、来源摘要和可信 `requestId` | 只记录 `sys_login_log` 白名单字段；不复制 A 的认证规则，不记录密码、验证码或 JWT |
| 可信服务请求校验 | AU03、A/C 服务入口（后续 PR） | `X-Client-Id`、`X-Key-Id`、时间戳、nonce、签名、实际方法/路径/请求体摘要 | 验签、服务绑定和范围检查后，原子写入 `(client_id, nonce)`；业务回滚不得使 nonce 可重放 |

## 签名对象与字段清单

`sys_data_signature` 中 `(data_type, data_id)` 唯一；`sign_version` 是序列化格式版本，`key_id` 是密钥标识，两者不能互换。空集合必须有独立、可验签的表示。以下字段来源于当前 SQL/设计，**字段分隔符、NULL 编码、时间格式、排序规则及最终字节序列尚未冻结**，PR02 前必须由组织者确认。

| data_type | data_id | 覆盖字段/成员（按最终冻结顺序） |
|---|---:|---|
| `USER_IDENTITY` | `sys_user.id` | `id`、`dept_id`、`status`、`source`、`employment_type`、`password_initialized`、`session_version`、`grant_revision`、`hr_version`；排除 `last_login_at`、失败计数、锁定时间等非授权统计字段 |
| `USER_GRANTS` | `sys_user.id` | 用户 ID、`grant_revision`，以及全部有效 `sys_user_role_source` 成员：`id`、`role_id`、`source_type`、`source_ref`、`source_dept_id`、`status`；成员集合含空集 |
| `ROLE_MENUS` | `sys_role.id` | 角色 ID、`menu_revision`、角色状态，以及全部 `sys_role_permission.menu_id` 成员；成员集合含空集 |
| `MENU_APIS` | `sys_menu.id` | 菜单 ID、`app_id`、`menu_type`、`status`、`api_revision`，以及全部 `sys_menu_api.api_id` 成员；成员集合含空集 |
| `DEPARTMENT` | `sys_dept.id` | `id`、`parent_id`、`dept_path`、`status`、`hr_version` |
| `RESOURCE_APPLICATION` / `RESOURCE_API` / `RESOURCE_MENU` | 对应资源 ID | 应用、API、菜单用于鉴权的标识、归属、路由/方法、父级、类型、状态和修订号；最终对象名待确认 |
| `ADMIN` / `ADMIN_DELEGABLE_ROLES` | 管理员用户 ID | 管理资格、等级、范围、状态、可委派角色集合与修订号；最终拆分待确认 |
| `SERVICE_IDENTITY` | `sys_service_identity.id` | `app_id`、`client_id`、`credential_hash`、`public_key`、`key_id`、允许范围和状态；不含私钥 |

## PR01 夹具

`PermissionFixture` 是仅测试代码的确定性内存场景：应用、根/子部门、普通用户与管理员、角色、有效手工授权来源、目录和权限菜单、菜单 API 映射，以及绑定应用的 `API_AUTH` 服务身份。它不加载 Spring 上下文、不写数据库，也不含任何真实密钥、密码或凭据。

## 需要组织者确认的阻塞项

1. A 提供可信 JWT 主体（含会话版本）的内部入口及失效错误映射。
2. C 提供 M5 目标对象范围校验的内部入口；D 只判定功能/API 权限。
3. LG04 的 `eventId` 与 `sys_operation_log.id`、`external_event_id` 的映射，以及 `decisionId` 在 `evidence` 中的固定路径。
4. 上表对象的最终字段列表、字段排序、NULL/时间/数值编码、集合排序和空集合字节表示；冻结后才可实现 `sign_version=1`。
5. 测试密钥的注入方式、可信历史公钥轮换方式，以及嵌套 Service 调用时的事务传播约定。
