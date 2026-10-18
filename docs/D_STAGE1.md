# D 第一阶段实现及接入说明

本阶段在现有 23 表、Java Service / MyBatis Mapper 和错误码基础上实现 D。没有新增数据库表、缓存、内部 HTTP 或默认允许开关。

## 已实现

| 接口 | 实现 |
|---|---|
| AU02 `GET /auth/menus` | A 的 JWT 验证器、当前账号/会话版本及注销记录；有效角色菜单并集、祖先导航补全；冻结部门返回空菜单 |
| AU03 `POST /auth/check` | 根据可信服务提供的应用 ID 和真实路由模板作 API 判定；多菜单映射 OR；同一请求读取一致事实、同步提交决策审计 |
| US06 `GET /users/{userId}/permissions` | `IAM_USERS_VIEW` 功能检查及 C 的管理员范围规则，返回目标用户有效菜单 |
| LG01—LG04 | 操作、鉴权、登录日志分页及不可变历史证据查询；`IAM_AUDIT_VIEW` 和 C 范围规则 |
| 公共签名 | Ed25519，固定格式 v1，外置密钥、历史公钥、空集合签名；读取验签不自动重签 |
| 公共审计 | 调用方事务内追加操作/登录记录、签名审计证据；故障回滚，不记录请求体或凭据 |

所有路径带 `/api/v1`。菜单结果用 `parentId` 表示树，`navigationOnly=true` 标明只补显示的祖先，不扩大实际授权（B 当前也允许权限菜单作为父节点）。路由规范化复用 B 的 `RouteTemplateUtils`，不把用户输入的应用编码当作服务身份。

## 第一、二阶段边界

- `TrustedApiCaller.authenticatedApplication(request, rawBody)` 是服务认证接入点。正式默认实现始终返回 `INVALID_SERVICE`。第一阶段 MySQL 测试在**测试配置**中提供预置服务身份；生产代码不识别 `X-Test-Service`、`X-App-Code` 等放行头。
- 第二阶段 D 实现服务请求签名、应用绑定和 nonce 防重放，再接入 A/C 的 HR/OA 入口。
- US06、LG01—LG04 复用 C 的 `AdminPolicyService`、`AdminRules`。C 现有 `AdminPorts.Integrity` 未适配时仍明确拒绝。专项测试提供调用真实 D 验签的测试适配器；不等同于已完成生产 C 适配。
- A 的 `ManagementDataSecurity`、C 的 `AdminPorts` / `GrantPorts` 以及 B 的写入入口，第二阶段按下述公共方法适配。此 PR 不替换这些模块现有的拒绝实现。
- `requireFunction` 是调用方事务中的功能读取。管理写入还必须由 A/B/C 按统一顺序锁定并复核管理员、目标、授权和资源；不能把一次读取检查当作完整并发写保护。
- 真实 A/C 流程、短信通道、两个演示系统、故障恢复及性能验收仍属于后续联调；本阶段不宣称已达到 20ms/50ms 指标。

## 公共方法与事务

| 方法 | 约定 |
|---|---|
| `DataSignatureService.sign(type, id)` | 调用方已合法修改数据后，读取完整记录及集合写签名；必须存在事务。不要对未验证的旧数据补签后继续授权 |
| `DataSignatureService.verify(type, id)` | 验证当前完整事实，返回对象、密钥 ID、摘要及签名引用；无签名/字段不一致/未知公钥拒绝 |
| `PermissionService.requireFunction(userId, permission)` | 已认证主体；当前事务内检查管理应用的有效权限菜单编码。管理应用由 `iam.permission.management-app-code` 指定，默认 `IAM` |
| `AuditService.change(operation, actorId, targetType, targetId, externalEventId, evidence)` | 同事务追加业务变更；evidence 由调用方选取非敏感 before/after 字段，禁止传入实体或完整请求 |
| `AuditService.login(userId, type, success, reasonCode)` | 同事务追加登录结果；方式 1/2，原因用安全错误码，未知用户可为空 |

在线 AU03 使用显式 `REQUIRES_NEW` + `REPEATABLE_READ` 事务，不继承调用方旧快照。普通允许/拒绝均先保存审计再返回；JWT/完整性故障先提交拒绝证据再返回原错误。审计或提交失败不能返回允许。其他签名和审计方法使用 `MANDATORY`，与调用方业务一起回滚。

## 签名 v1 的确定格式

格式基线在此次实现中固定，第二阶段适配者复用实现，不各自拼接签名字符串：

1. 信封为 `{ "type": TYPE, "id": ID, "signVersion": 1, "facts": {...} }`。
2. UTF-8 JSON；对象键按 Java 字符串字典序排序，字符串保留原内容并按 JSON 转义；不做大小写折叠或 Unicode 归一化。
3. 所有数字使用无前导零的十进制字符串；SQL NULL 显式写成 JSON null，空集合写成 `[]`，二者不同。
4. 成员按 Mapper 的主键/成员 ID 顺序排列，不使用数据库无序结果。签名字段不包含登录时间、失败次数、更新时间等统计字段。
5. Ed25519 签名 Base64 写入 `sys_data_signature`；`sign_version=1` 是格式版本，`key_id` 才是密钥标识。

完整固定字段清单在 `SignedDataType`；成员查询在 `SignatureMapper`，要点如下：

| 类型 / data_id | 覆盖边界 |
|---|---|
| `USER_IDENTITY` / 用户 ID | 身份密文、电话索引、密码哈希、账号来源和状态、部门、设密/会话/授权/HR 版本、创建来源 |
| `USER_GRANTS` / 用户 ID | 授权修订号、当前角色 ID 集合、所有有效来源的 ID/角色/类型/引用/来源部门/状态；含空集合 |
| `ROLE_MENUS` / 角色 ID | 编码、类型、启用状态、集合版本、菜单 ID 集合 |
| `RESOURCE_APPLICATION` / 应用 ID | 编码、状态、菜单及 API 成员 ID 集合，避免删除成员后无法检测 |
| `RESOURCE_MENU` / 菜单 ID | 归属、父节点、编码、显示字段、类型、状态、映射版本 |
| `MENU_APIS` / 菜单 ID | 归属、类型、状态、映射版本及 API ID 集合 |
| `RESOURCE_API` / API ID | 归属、编码、路径及规范化路径、方法、状态 |
| `DEPARTMENT` / 部门 ID | 父节点、编码/名称、路径、冻结、合并去向、负责人、HR 版本 |
| `ORGANIZATION` / **0** | 按部门 ID 排序的全部部门规范记录，覆盖整棵树成员删除 |
| `ADMIN` / **管理员用户 ID** | 等级、范围、任命来源、状态、可授权集合修订号 |
| `ADMIN_DELEGABLE_ROLES` / 管理员用户 ID | 修订号和可授权角色 ID 集合 |
| `SERVICE_IDENTITY` / 服务记录 ID | 绑定应用、clientId、公钥指纹、公钥及 keyId、操作/角色/部门范围、状态 |

使用角色时还检查 `sys_user_role` 的角色集合与有效来源支持的角色集合一致。缺少有效来源、伪造角色关系、删除最后关联但仍保留来源等不一致，均不能形成允许。

第二阶段写入适配时，必须在同一事务更新所有受影响对象：用户授权变更同时签 `USER_IDENTITY` / `USER_GRANTS`；菜单 API 集合变更同时签 `RESOURCE_MENU` / `MENU_APIS`；增删应用资源成员还要签 `RESOURCE_APPLICATION`；组织变更签 `ORGANIZATION` 及对应部门，用户归属改变还要签用户身份。单个对象签名成功不能替代整笔业务的校验、其他对象签名和审计。

## 密钥配置

在被忽略的本地配置或部署密钥配置中提供：

```yaml
iam:
  permission:
    management-app-code: IAM
  security:
    data-signing:
      key-id: k1
      private-key: ${IAM_DATA_PRIVATE_K1:} # Ed25519 PKCS8 Base64
      public-keys:
        k1: ${IAM_DATA_PUBLIC_K1:}        # Ed25519 X509 Base64
```

数据签名、JWT、AES/HMAC 和服务请求签名使用独立密钥。轮换时保留旧公钥以验证历史签名和审计证据，改用新 key-id/私钥签署新记录；验证不需要私钥。没有默认密钥或自动生成生产密钥。预置数据先由受控初始化在事务中签署，不能在查询失败后自动补签。

## 审计与范围

- `eventId` 统一为 `sys_operation_log.id` 的十进制字符串，直接来自 LG01/LG02；HR/OA 原事件 ID 保存在 `external_event_id` 和签名元数据，避免来源间同号歧义。`decisionId` 为 UUID，固定存于 `evidence.decisionId`。
- `permissionVersion` 是本次规范化事实和路由的 SHA-256 摘要，不包含随机 decisionId；不是缓存版本。
- 鉴权证据包括 `user_role`、`role_permission`、`menu_api`、`state`、`signatures`，并保留当时的来源、资源和版本，不查询当前授权来重建历史。
- 留存证据另有 Ed25519 detached seal，包含 requestId、操作者、目标、动作及决策元数据；LG04 使用留存证据及历史公钥验签。旧的无签名证据不冒充 `integrityVerified=true`。
- 有效一级管理员可读全局；其他管理员仅可读其当前部门范围内的用户/部门事件，存在操作者时也要求操作者在范围内。未知主体的登录记录仅全局管理员可见。范围校验和查询计数/分页使用同一事务。
- 数据库已有追加/禁止删除触发器继续有效；不宣称数据库已经是 WORM 存储。

## 复测

普通回归使用 `mvn clean verify`。真实 MySQL 专项显式设置 `D_DB_TEST=true`、`A_TEST_DB_URL`、`A_TEST_DB_USERNAME`、`A_TEST_DB_PASSWORD` 后运行 `mvn -Dtest=DMySqlIntegrationTest test`。密码仅从本地密钥文件或环境注入，不写进报告。

专项在已有 `01_schema.sql` + `02_add_canonical_path_and_index.sql` 库中插入 `D` 前缀随机夹具，不新建数据库或容器，不物理删除账号、来源和日志。仅对可清空的个人开发库运行；运行前确认配置正确。本次执行沿用用户授权的原开发库，结束清理本轮数据。
