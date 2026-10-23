> 当前课程作业口径：见 [简化范围](../COURSEWORK_SCOPE.md)。已取消 GR06，批量同步返回；下面各接口按更新后的契约验收。

> 当前作业口径（2026-10-23）：按用户确认，姓名和手机号使用明文演示数据，不做存储加密或展示脱敏；权限与手机号防篡改签名仍保留。此项与原始需求 PDF 的隐私保护要求不同。

# 统一权限管理系统：API 接口设计规范

## 1. 模块归属、契约边界

| 模块 | API 责任 | 对应接口组 | 数量 |
|---|---|---|---:|
| M1 身份认证与账号管理 | 登录、短信、密码、账号维护 | AU01, AU05, AU07, AU08, AU04, US01, US02, US03, US04 | 9 |
| M2 组织与人员变动管理 | HR事件、组织查询、部门冻结 | DP01, DP02, HR01, HR02, HR03, HR04, HR05, DP03, DP04 | 9 |
| M3 应用权限资源管理 | 全局应用/菜单/API/映射管理 | RE01, RE02, RE03, RE04, RE05, RE06, RE07, RE08, RE09, RE11, RE10 | 11 |
| M4 角色与授权管理 | 全局角色、角色菜单、授权来源、OA | RL01, RL02, RL03, RL04, RL05, RL06, US05, GR05, GR01, GR02, GR03, GR04, OA01 | 13 |
| M5 管理员与管理范围控制 | 管理员任命、范围与可授权清单 | AD01, AD02, AD03, AD04, AD05, AD06 | 6 |
| M6 在线权限鉴定 | 当前菜单、后端 API 决策 | AU02, AU03, US06 | 3 |
| PUB 公共支撑：审计查询 | 审计查询 | LG01, LG02, LG03, LG04 | 4 |

**职责边界**：M1 认证，M2 编排 HR，M3 登记资源，M4 维护角色和授权依据，M5 判断管理员及对象/角色范围，M6 在线判定并数据库事务提交；审计与签名为公共支撑。除登记和维护外，M3 不实施 API 请求鉴权；M4 不替代 M6 的运行时权限判断。

## 2. 统一 HTTP 协议

| 项目 | 约定 |
|---|---|
| 基础地址 | `https://iam.example.test/api/v1` 为占位；真实域名待部署确认 |
| 传输/编码 | HTTPS；UTF-8；请求/响应 JSON |
| ID | MySQL BIGINT 在 JSON 中统一表示为十进制字符串；SG 号为独立业务字符串 |
| 时间/分页 | ISO 8601 含时区；`page=1`、`pageSize=20`、最大 500 |
| JWT | `Authorization: Bearer <JWT>`，不得把角色和菜单作为 JWT 固定权限声明 |
| 业务服务认证 | HTTPS + Ed25519 服务请求签名；凭据绑定应用、禁止客户端随意声明 `appCode` |
| HR/OA 认证 | 使用独立可信服务凭据和受控操作范围；示例头 `X-Client-Id`、`X-Request-Timestamp`、`X-Request-Nonce`、`X-Request-Signature`，算法固定 Ed25519，签名内容与时间窗见本节开发基线 |
| 请求追踪 | `X-Request-Id` 可选；无值由服务端生成，关联业务、鉴权和审计 |
| 幂等 | HR/OA 必须有 `(sourceSystem,eventId)` 唯一记录；批量请求不再使用请求级幂等键；HR/OA以事件键为业务幂等依据；普通授予以稳定来源键判定重复，全量替换以集合版本保护 |
| 并发 | 全量集合替换使用 `expectedVersion`；版本来源为各表修订号（`api_revision`、`menu_revision`、`delegable_revision`）；用户授权使用 `sys_user.grant_revision` |
| 数据展示 | 姓名、手机号按查看资格返回完整演示值；不返回密码哈希、验证码、完整 JWT（登录时返回 accessToken 是必要例外）或密钥 |
外部事件登记和业务成功结果同事务；失败整体回滚，不提供在线恢复。鉴权返回 allowed、reasonCode、decisionId，不返回权限证据摘要。

**成功响应**（普通查询或已完成业务操作）：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "id": "101"
  }
}
```
**失败响应**：
```json
{
  "success": false,
  "code": "PERMISSION_DENIED",
  "message": "无权执行此操作",
  "requestId": "req-demo"
}
```
外部事件登记和业务成功结果同事务；失败整体回滚，不提供在线恢复。鉴权返回 allowed、reasonCode、decisionId，不返回权限证据摘要。

### 2.1 统一错误码

| HTTP | 业务码（示例） | 处理意义 |
|---|---|---|
| 400 | `INVALID_ARGUMENT`、`INVALID_ROUTE_TEMPLATE` | 参数/路由错误；不得继续执行 |
| 401 | `INVALID_CREDENTIALS`、`INVALID_TOKEN`、`INVALID_SERVICE` | 身份凭据不成立 |
| 403 | `PERMISSION_DENIED`、`OUT_OF_SCOPE`、`ROLE_NOT_DELEGABLE`、`OA_SCOPE_DENIED` | 有身份但无功能、范围或角色资格 |
| 404 | `RESOURCE_NOT_FOUND`、`GRANT_SOURCE_NOT_FOUND` | 目标不存在；范围外目标统一采用反枚举策略 |
| 409 | `VERSION_CONFLICT`、`IDEMPOTENCY_CONFLICT`、`DUPLICATE_RESOURCE` | 版本冲突、同号不同内容、唯一键冲突 |
| 422 | `BUSINESS_RULE_VIOLATION`、`INVALID_HR_ORDER`、`INVALID_DEPT_MAPPING` | 规则或 HR 顺序/映射异常 |
| 429 | `RATE_LIMITED` | 验证码、登录及敏感操作限流 |
| 503 | `AUTH_STATE_UNAVAILABLE`、`INTEGRITY_FAILURE`、`AUDIT_UNAVAILABLE`、`SMS_DELIVERY_UNAVAILABLE` | 可信状态、必要证据或服务暂不可用，安全失败 |

**关键状态语义**：部门冻结不影响 M1 登录、注销、必要密码操作，但保护业务 API 必须拒绝；冻结时 AU02 返回空 `menus`；账号暂停/离职禁止登录和受保护业务访问。修改部门状态、撤回授权、停用资源及管理员任命回收不能使用旧允许缓存放行。

### 2.2 鉴权级别与写入一致性

| 缩写 | 含义 |
|---|---|
| `PUBLIC` | 无需已登录 JWT，仍需限流和凭据验证 |
| `PUBLIC_SMS_PROOF` | 凭绑定手机号及对应用途的一次性验证码证明身份 |
| `USER_SELF` | 有效 JWT + 当前账户状态；注销按AU04例外处理，设密/重置使用短信证明 |
| `M5_SCOPE` | 有效管理员任命 + 实际用户/部门目标范围 |
| `M5_L1` | 有效一级总公司管理员资格；还必须有相应的 M6 功能权限 |
| `DELEGABLE_ROLE` | 一级按集团角色与等级资格边界判定，二级及以下按当前可授权清单，同时满足角色类型和等级限制 |
| `HR_SERVICE` | 可信 HR 服务身份和允许的同步操作范围 |
| `OA_SERVICE_SCOPE` | 可信 OA 服务身份 + 审批事件的操作、角色和对象范围，不代替普通管理员任命 |
| `TRUSTED_SERVICE+USER_JWT` | 已登记并绑定应用的可信业务服务 + 当前用户 JWT |
| `ADMIN` | 管理端需具有有效管理资格及所列功能权限；全局只读不自动要求一级 |

**权限检查顺序**：管理后台先执行 M6 当前功能鉴权，再由 M5 检查实际对象、等级及可授权角色资格；业务动作提交前复核资格的有效性。全局 M3 和全局角色配置写接口只允许 L1。普通管理员自我授权/回收允许，但条件与操作其他用户相同。OA 事件按受控服务权限执行，不重新检验原申请人的现时任命。

**权限变更规则**：Service 在同一 MySQL 事务提交事实、签名与关键审计，成功后返回；M6 每次请求开启新的一致数据库视图读取当前状态，不使用 Redis、权限缓存、发布版本或栅栏。详细规则见详细设计第8.5节。

### 2.3 字段、标识与授权来源

| 字段 | 契约含义 |
|---|---|
| `sgNo` | HR 确认的 SG 稳定人员号，映射至 `sys_user.employee_no` |
| `deptId` | 内部 `sys_dept.id` 字符串主键；HR 系统使用 deptCode，入库前执行唯一映射 |
| `grantSourceId` | 用户—角色的独立授权依据唯一标识；单独回收来源而非简单删除整个角色 |
| `sourceType/sourceRef` | 授权来源类型及外部业务标识，例如 MANUAL、DEPT_POSITION、OA_APPROVAL；枚举固定 |
| `roleId` | 全局角色唯一 ID；角色类型不自动赋权 |
| `expectedVersion` | 当前授权或资源集合期望修订号，冲突返回 409 |
| `approvalId` | 外部 OA 审批单号，可作为 OA 授权来源业务依据 |
| `eventVersion` | HR 每人员/部门的顺序版本，不应仅依全局时间戳决定顺序 |
| `processingStatus` | 外部事件成功为 APPLIED；同步批量为 COMPLETED / PARTIAL_SUCCESS / FAILED |

### 2.4 本期已冻结的开发口径

Controller → Service → Repository 三层，Spring Data JPA + MySQL，全部55项接口纳入本期实现范围；无DAO、Redis、消息队列或缓存发布机制。鉴权直接查主库，在本次一致事务读取状态并同步提交审计，只记录课堂数据规模的实测耗时。功能权限与M5范围在写入事务中加锁复核。

- JWT有效期3600秒，携带sv；注销当前jti，重置/暂停/离职递增session_version。
- 短信码5分钟、间隔60秒、最多5次；重新发送撤销同用途旧码，验证码与密码更新事务核销。演示通过独立模拟发送通道，正式HTTP响应不返回验证码。
- 服务签名头固定X-Client-Id、X-Key-Id、X-Request-Timestamp、X-Request-Nonce、X-Request-Signature；时间戳为Unix秒，允许正负300秒。签名UTF-8内容按换行连接clientId、keyId、timestamp、nonce、大写HTTP方法、原始路径及查询串、原始请求体SHA-256十六进制摘要；Ed25519签名Base64。验签及绑定验证后原子插入(clientId,nonce)，保留10分钟，业务重试使用新nonce。代理须保留签名路径并明确路径前缀。
- 岗位授予sourceType=DEPT_POSITION时sourceDeptId必填且校验为目标用户当前部门；MANUAL是独立于岗位的来源，普通管理入口禁止OA_APPROVAL，OA入口由服务生成该来源。各来源ID为数据库主键十进制字符串。
- GR01/GR02的expectedVersion来自US05/GR05返回的grantRevision（sys_user.grant_revision），事务内比较并递增；RL05/RE11/AD05分别提供对应集合修订号。
- GR03 最多100人同步逐项事务执行，HTTP 200 返回全部结果；不创建后台任务，不支持 dryRun=true。
外部事件登记和业务成功结果同事务；失败整体回滚，不提供在线恢复。鉴权返回 allowed、reasonCode、decisionId，不返回权限证据摘要。
- HR02部门变化必须调用调岗处理；HR版本比较锁定目标行，OA无顺序版本允许数据库event_version=NULL。OA执行角色、部门和操作按服务身份配置校验。

## 3. 接口目录（55项，课程作业范围）

### M1 身份认证与账号管理

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| AU01 | `POST` | `/auth/login` | 用户名或手机号密码登录 | `PUBLIC` |
| AU05 | `POST` | `/auth/sms/send` | 发送短信验证码 | `PUBLIC` |
| AU07 | `POST` | `/auth/password/initialize` | 首次设置登录密码 | `PUBLIC_SMS_PROOF` |
| AU08 | `POST` | `/auth/password/reset` | 短信验证后重置密码 | `PUBLIC_SMS_PROOF` |
| AU04 | `POST` | `/auth/logout` | 注销当前 JWT 会话 | `USER_SELF` |
| US01 | `GET` | `/users` | 按管理范围查询用户 | `IAM_USERS_VIEW+M5_SCOPE` |
| US02 | `GET` | `/users/{userId}` | 查询用户详情 | `IAM_USERS_VIEW+M5_SCOPE` |
| US03 | `POST` | `/users/temporary` | 创建无 SG 号的临时账号 | `IAM_USERS_WRITE+M5_SCOPE` |
| US04 | `PATCH` | `/users/{userId}/status` | 暂停或恢复临时账号 | `IAM_USERS_WRITE+M5_SCOPE` |

### M2 组织与人员变动管理

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| DP01 | `GET` | `/departments` | 按管理范围查询部门树 | `IAM_DEPTS_VIEW+M5_SCOPE` |
| DP02 | `GET` | `/departments/{deptId}/users` | 查询部门及下属人员 | `IAM_USERS_VIEW+M5_SCOPE` |
| HR01 | `PUT` | `/integrations/hr/departments/{deptCode}` | HR 部门增量同步 | `HR_SERVICE` |
| HR02 | `PUT` | `/integrations/hr/employees/{sgNo}` | HR 人员入职或资料同步 | `HR_SERVICE` |
| HR03 | `POST` | `/integrations/hr/transfers` | HR 调岗事件 | `HR_SERVICE` |
| HR04 | `POST` | `/integrations/hr/terminations` | HR 离职事件 | `HR_SERVICE` |
| HR05 | `POST` | `/integrations/hr/merges` | HR 部门合并事件 | `HR_SERVICE` |
| DP03 | `POST` | `/departments/{deptId}/freeze` | 冻结部门业务权限 | `IAM_DEPTS_FREEZE+M5_SCOPE` |
| DP04 | `POST` | `/departments/{deptId}/unfreeze` | 解除部门冻结标识 | `IAM_DEPTS_FREEZE+M5_SCOPE` |

### M3 应用权限资源管理

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| RE01 | `GET` | `/applications` | 查询全局应用 | `IAM_RESOURCES_VIEW+ADMIN` |
| RE02 | `POST` | `/applications` | 登记全局应用 | `IAM_RESOURCES_WRITE+M5_L1` |
| RE03 | `PATCH` | `/applications/{appId}` | 修改或停用应用 | `IAM_RESOURCES_WRITE+M5_L1` |
| RE04 | `GET` | `/applications/{appId}/menus` | 查询应用菜单资源树 | `IAM_RESOURCES_VIEW+ADMIN` |
| RE05 | `POST` | `/applications/{appId}/menus` | 创建导航目录或权限菜单 | `IAM_RESOURCES_WRITE+M5_L1` |
| RE06 | `PATCH` | `/menus/{menuId}` | 修改、移动或停用菜单 | `IAM_RESOURCES_WRITE+M5_L1` |
| RE07 | `GET` | `/applications/{appId}/apis` | 查询应用 API 资源 | `IAM_RESOURCES_VIEW+ADMIN` |
| RE08 | `POST` | `/applications/{appId}/apis` | 登记受保护 API | `IAM_RESOURCES_WRITE+M5_L1` |
| RE09 | `PATCH` | `/apis/{apiId}` | 修改或停用 API 资源 | `IAM_RESOURCES_WRITE+M5_L1` |
| RE11 | `GET` | `/menus/{menuId}/apis` | 查询菜单关联 API | `IAM_RESOURCES_VIEW+ADMIN` |
| RE10 | `PUT` | `/menus/{menuId}/apis` | 全量替换菜单—API 关联 | `IAM_RESOURCES_WRITE+M5_L1` |

### M4 角色与授权管理

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| RL01 | `GET` | `/roles` | 查询全局角色列表 | `IAM_ROLES_VIEW+ADMIN` |
| RL02 | `POST` | `/roles` | 创建业务或管理角色 | `IAM_ROLES_WRITE+M5_L1` |
| RL03 | `GET` | `/roles/{roleId}` | 查询角色定义 | `IAM_ROLES_VIEW+ADMIN` |
| RL04 | `PATCH` | `/roles/{roleId}` | 修改或停用角色 | `IAM_ROLES_WRITE+M5_L1` |
| RL05 | `GET` | `/roles/{roleId}/menus` | 读取角色—菜单关系 | `IAM_ROLES_VIEW+ADMIN` |
| RL06 | `PUT` | `/roles/{roleId}/menus` | 替换角色全部菜单 | `IAM_ROLES_WRITE+M5_L1` |
| US05 | `GET` | `/users/{userId}/roles` | 查询用户当前角色 | `IAM_USERS_VIEW+M5_SCOPE` |
| GR05 | `GET` | `/users/{userId}/roles/{roleId}/sources` | 查询角色的独立授权依据 | `IAM_GRANTS_VIEW+M5_SCOPE` |
| GR01 | `POST` | `/grants/user-roles` | 为用户授予角色并登记来源 | `IAM_GRANTS_WRITE+M5_SCOPE+DELEGABLE_ROLE` |
| GR02 | `POST` | `/grants/user-roles/revoke` | 按授权依据回收角色 | `IAM_GRANTS_WRITE+M5_SCOPE+DELEGABLE_ROLE` |
| GR03 | `POST` | `/grants/user-roles/batch` | 批量授予或按依据回收 | `IAM_GRANTS_WRITE+M5_SCOPE+DELEGABLE_ROLE` |
| GR04 | `GET` | `/grants/history` | 查询角色授权变更历史 | `IAM_GRANTS_VIEW+M5_SCOPE` |
| OA01 | `POST` | `/integrations/oa/decisions` | 执行 OA 已批准角色授权或回收 | `OA_SERVICE_SCOPE` |

### M5 管理员与管理范围控制

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| AD01 | `GET` | `/admins` | 按管理范围查询管理员 | `IAM_ADMINS_VIEW+M5_SCOPE` |
| AD02 | `POST` | `/admins/appointments` | 任命同级或下级管理员 | `IAM_ADMINS_WRITE+M5_SCOPE` |
| AD03 | `POST` | `/admins/{userId}/revoke` | 撤销管理员任命 | `IAM_ADMINS_WRITE+M5_SCOPE` |
| AD04 | `GET` | `/admins/me/scope` | 查询本人管理资格与范围 | `IAM_ADMINS_VIEW+SELF` |
| AD05 | `GET` | `/admins/{userId}/delegable-roles` | 查询管理员可授权角色清单 | `IAM_GRANTS_VIEW+M5_SCOPE` |
| AD06 | `PUT` | `/admins/{userId}/delegable-roles` | 上级配置下级可授权角色清单 | `IAM_GRANTS_DELEGATE+M5_SCOPE` |

### M6 在线权限鉴定

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| AU02 | `GET` | `/auth/menus` | 查询本人指定应用有效菜单 | `USER_SELF` |
| AU03 | `POST` | `/auth/check` | 业务服务端实时 API 鉴权 | `TRUSTED_SERVICE+USER_JWT` |
| US06 | `GET` | `/users/{userId}/permissions` | 管理员查询用户当前有效菜单 | `IAM_USERS_VIEW+M5_SCOPE` |

### PUB 公共支撑：审计查询

| 编号 | 方法 | 路径（统一前缀 `/api/v1`） | 用途 | 校验 |
|---|---|---|---|---|
| LG01 | `GET` | `/audit/operations` | 查询管理及事件操作日志 | `IAM_AUDIT_VIEW+M5_SCOPE` |
| LG02 | `GET` | `/audit/authorizations` | 查询历史鉴权决策 | `IAM_AUDIT_VIEW+M5_SCOPE` |
| LG03 | `GET` | `/audit/logins` | 查询登录日志 | `IAM_AUDIT_VIEW+M5_SCOPE` |
| LG04 | `GET` | `/audit/evidence/{eventId}` | 查询授权或变更审计证据 | `IAM_AUDIT_VIEW+M5_SCOPE` |

**新增编号**：AU05、AU07、AU08（验证码与密码）、GR05（授权依据查询）、AD05—AD06（可授权角色清单）。原 AU06（验证码登录）已按需求删除：登录方式仅保留账号密码、手机号密码两种，短信验证码只用于首次设密与密码重置的本人身份验证。旧有49项仍保留编号；HR01 保留外部部门编码并强制映射至 `sys_dept.id`，HR02 路径变量明确为 SG 号。

## 4. 逐接口详细契约

以下 JSON 请求、响应为契约示例。公共头、签名、限流和权限检查遵循第2章，不在每个接口重复。路径 ID 均为示例；需要版本控制的变更须有可靠持久化版本。

### 4.1 M1 身份认证与账号管理

#### AU01 · `POST /api/v1/auth/login` — 用户名或手机号密码登录

- **调用方/权限**：用户/业务系统入口；`PUBLIC`。
- **处理与边界**：用户名或手机号均可作为登录标识（账号密码、手机号密码两种方式，均为密码登录，不提供短信验证码登录）；检查密码是否已初始化（未设密拒绝登录）、scrypt 哈希、账号及必要签名；部门冻结不阻止登录。失败不披露账号是否存在。
- **主要数据**：sys_user、sys_login_log。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `loginType` | body | `string` | 是 | 登录标识类型：USERNAME / PHONE |
| `identifier` | body | `string` | 是 | 用户名或手机号 |
| `password` | body | `string` | 是 | 登录密码，不得记录到日志 |

**请求体示例**：
```json
{
  "loginType": "USERNAME",
  "identifier": "zs",
  "password": "<PASSWORD>"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "accessToken": "<JWT>",
    "tokenType": "Bearer",
    "expiresIn": 3600,
    "userId": "101"
  }
}
```

**典型错误**：`INVALID_CREDENTIALS`(401：未知/未设密/暂停/离职/锁定/错误密码统一提示，防枚举)、`INVALID_ARGUMENT`(400)、`RATE_LIMITED`(429)、`INTEGRITY_FAILURE`/`AUDIT_UNAVAILABLE`/`AUTH_STATE_UNAVAILABLE`(503)。

#### AU05 · `POST /api/v1/auth/sms/send` — 发送短信验证码

- **调用方/权限**：用户/业务系统入口；`PUBLIC`。
- **处理与边界**：按 SET_PASSWORD、RESET_PASSWORD 隔离用途（验证码仅用于验证本人身份以设密/重置，不用于登录），限制手机号格式、发送频率和次数；验证码不可复用。响应不得暴露手机号是否已注册或验证码值。过期时长固定5分钟；发送通道可替换，演示使用独立模拟通道，真实供应商凭据由部署配置提供。
- **主要数据**：sys_sms_code（验证码一次性状态）、sys_user。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `phone` | body | `string` | 是 | 规范化演示手机号，明文保存 |
| `purpose` | body | `string` | 是 | 验证码用途：SET_PASSWORD / RESET_PASSWORD |

**请求体示例**：
```json
{
  "phone": "13800000000",
  "purpose": "SET_PASSWORD"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "accepted": true,
    "retryAfterSeconds": 60
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`RATE_LIMITED`(429)、`SMS_DELIVERY_UNAVAILABLE`/`INTEGRITY_FAILURE`/`AUDIT_UNAVAILABLE`/`AUTH_STATE_UNAVAILABLE`(503)。未知或不可用手机号仍返回同一200受理结构，无账号资格401分支。

#### AU07 · `POST /api/v1/auth/password/initialize` — 首次设置登录密码

- **调用方/权限**：用户/业务系统入口；`PUBLIC_SMS_PROOF`。
- **处理与边界**：使用绑定手机号和 SET_PASSWORD 专用验证码验证本人；仅账号可用且密码未初始化时设置新密码。验证码一次性核销，密码带盐哈希保存，禁止返回明文。
- **主要数据**：sys_user、验证码状态、签名/审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `phone` | body | `string` | 是 | 规范化演示手机号，明文保存 |
| `code` | body | `string` | 是 | 一次性短信验证码 |
| `newPassword` | body | `string` | 是 | 新密码，不得记录日志 |

**请求体示例**：
```json
{
  "phone": "13800000000",
  "code": "123456",
  "newPassword": "<NEW_PASSWORD>"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "initialized": true
  }
}
```

**典型错误**：`INVALID_CREDENTIALS`(401：无效证明、账号不可用或重复初始化统一提示)、`INVALID_ARGUMENT`(400)、`BUSINESS_RULE_VIOLATION`(422：密码策略/最近5次历史)、`RATE_LIMITED`(429)、必要可信依赖失败503。422不核销有效验证码，密码/历史/会话/签名审计同事务回滚。

#### AU08 · `POST /api/v1/auth/password/reset` — 短信验证后重置密码

- **调用方/权限**：用户/业务系统入口；`PUBLIC_SMS_PROOF`。
- **处理与边界**：验证账号可用及 RESET_PASSWORD 专用验证码，更改密码并使所有旧会话失效；密码重置即使原 JWT 有效也不得保留旧会话授权。
- **主要数据**：sys_user、验证码状态、共享会话失效状态。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `phone` | body | `string` | 是 | 规范化演示手机号，明文保存 |
| `code` | body | `string` | 是 | 一次性短信验证码 |
| `newPassword` | body | `string` | 是 | 新密码，不得记录日志 |

**请求体示例**：
```json
{
  "phone": "13800000000",
  "code": "123456",
  "newPassword": "<NEW_PASSWORD>"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "passwordReset": true,
    "sessionsInvalidated": true
  }
}
```

**典型错误**：`INVALID_CREDENTIALS`(401)、`INVALID_ARGUMENT`(400)、`BUSINESS_RULE_VIOLATION`(422：密码策略/最近5次历史)、`RATE_LIMITED`(429)、必要可信依赖失败503。422不修改原密码/会话版本、不核销有效验证码。

#### AU04 · `POST /api/v1/auth/logout` — 注销当前 JWT 会话

- **调用方/权限**：已登录用户；`USER_SELF`。
- **处理与边界**：从可验证Bearer JWT确认jti，写入MySQL单令牌注销记录并提交后成功；重复注销幂等。部门冻结、账号暂停/离职或会话版本已失效仍允许注销，不能借此访问业务。
- **主要数据**：sys_session_invalidation、sys_operation_log。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

**请求示例**：`POST /api/v1/auth/logout`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "revoked": true
  }
}
```

**典型错误**：`INVALID_TOKEN`、`AUTH_STATE_UNAVAILABLE`。

#### US01 · `GET /api/v1/users` — 按管理范围查询用户

- **调用方/权限**：管理端；`IAM_USERS_VIEW+M5_SCOPE`。
- **处理与边界**：M6 检查功能权限、M5 将搜索限定至当前部门根及子树；关键字段经范围授权和验签后返回明文演示值。
- **主要数据**：sys_user、sys_dept。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `keyword` | query | `string` | 否 | 搜索关键词，受限字段搜索 |
| `deptId` | query | `string` | 否 | 内部部门主键，字符串 |
| `status` | query | `integer` | 否 | 1可用、2暂停、3离职；省略不限制状态 |
| `source` | query | `integer` | 否 | 1 HR同步、2管理员手动创建、3受控初始化；省略不限制来源 |
| `page` | query | `integer` | 否 | 默认1，范围1至1,000,000 |
| `pageSize` | query | `integer` | 否 | 默认20，范围1至500 |

**请求示例**：`GET /api/v1/users?keyword=张&deptId=17&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "userId": "101",
        "username": "zs",
        "sgNo": "SG000123",
        "realName": "示例用户",
        "phone": "13800000000",
        "deptId": "17",
        "status": 1,
        "passwordInitialized": true,
        "source": 1
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400：枚举/类型/分页/ID/关键词长度)、`INVALID_TOKEN`(401：缺失/失效会话)、`PERMISSION_DENIED`/`OUT_OF_SCOPE`(403)、必要可信依赖失败503。


**10实现差异说明**：status/source与keyword/deptId均取当前M5范围交集，items/total共用SQL谓词；keyword最长64字符。当前只支持用户名/SG号子串、完整规范化规范化手机号精确匹配；原文“搜索关键词，受限字段搜索”及keyword=张示例保留，姓名包含搜索尚无已确认的安全可分页方案，仍未完成。响应UserView还含passwordInitialized与source，非HR账号sgNo为null。

#### US02 · `GET /api/v1/users/{userId}` — 查询用户详情

- **调用方/权限**：管理端；`IAM_USERS_VIEW+M5_SCOPE`。
- **处理与边界**：根据真实 userId 查询所属部门并重新执行 M5 范围检查；不能仅凭列表已过滤跳过详情校验。
- **主要数据**：sys_user、sys_dept。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |

**请求示例**：`GET /api/v1/users/101`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "101",
    "username": "zs",
    "sgNo": "SG000123",
    "realName": "示例用户",
    "phone": "13800000000",
    "deptId": "17",
    "status": 1,
    "passwordInitialized": true,
    "source": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`(403)、`RESOURCE_NOT_FOUND`(404：不存在或范围外统一)、必要可信依赖失败503。


**10响应对齐**：UserView含source(1/2/3)，非HR账号sgNo为null，与US01使用相同投影。

#### US03 · `POST /api/v1/users/temporary` — 创建无 SG 号的临时账号

- **调用方/权限**：管理端；`IAM_USERS_WRITE+M5_SCOPE`。
- **处理与边界**：仅创建 source=2/employee_no=NULL 的临时用户（普通账号，不设独立类型）；手机号必填且全局唯一，不能创建带工号的正式/外包人员（有工号账号只能由 HR 同步生成）。禁止管理员设置初始明文密码，未设密不能登录，首次由本人短信验证设密（AU05→AU07）后方可登录。临时用户名服务端生成唯一值，生成格式为temp加唯一编号。
- **主要数据**：sys_user、sys_data_signature、sys_operation_log。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `realName` | body | `string` | 是 | 演示姓名，明文入库 |
| `phone` | body | `string` | 是 | 规范化演示手机号，明文保存 |
| `deptId` | body | `string` | 是 | 内部部门主键，字符串 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "realName": "临时值班人员",
  "phone": "13800000000",
  "deptId": "17",
  "reason": "临时服务支持"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "301",
    "username": "temp301",
    "source": 2,
    "phone": "13800000000",
    "deptId": "17",
    "status": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`/`OUT_OF_SCOPE`(403)、`DUPLICATE_RESOURCE`(409：手机号/登录名唯一冲突)、`BUSINESS_RULE_VIOLATION`(422：不存在或已合并目标部门)、必要可信依赖失败503。

#### US04 · `PATCH /api/v1/users/{userId}/status` — 暂停或恢复临时账号

- **调用方/权限**：管理端；`IAM_USERS_WRITE+M5_SCOPE`。
- **处理与边界**：人工暂停/恢复仅对手动创建（source=2）的账号开放；HR 同步账号的状态由 HR 事件驱动，不通过本接口人工暂停。离职状态只能由 HR 事件设置。
- **主要数据**：sys_user、sys_data_signature。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |
| `status` | body | `integer` | 是 | 1恢复可用、2暂停；其他输入400 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "status": 2,
  "reason": "临时账号停用"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "301",
    "status": 2
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400：status仅1/2)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`(403)、`RESOURCE_NOT_FOUND`(404：不存在或范围外)、`BUSINESS_RULE_VIOLATION`(422：非source=2或已离职账号)、必要可信依赖失败503。当前没有expectedVersion输入或VERSION_CONFLICT分支。

### 4.2 M2 组织与人员变动管理

#### DP01 · `GET /api/v1/departments` — 按管理范围查询部门树

- **调用方/权限**：管理端；`IAM_DEPTS_VIEW+M5_SCOPE`。
- **处理与边界**：返回属于操作者管理范围的组织节点与 parentId、冻结状态；不暴露范围外组织的完整路径。
- **主要数据**：sys_dept。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `includeDescendants` | query | `boolean` | 否 | DP01默认true：返回范围内全部节点；false仅返回范围根。DP02默认false：仅直属人员；true含范围内下属 |

**请求示例**：`GET /api/v1/departments?includeDescendants=true`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "deptId": "17",
        "parentId": "1",
        "deptName": "示例部门",
        "status": 1
      }
    ]
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`(403)、`BUSINESS_RULE_VIOLATION`(422：组织循环或路径超长)、必要可信依赖失败503。

#### DP02 · `GET /api/v1/departments/{deptId}/users` — 查询部门及下属人员

- **调用方/权限**：管理端；`IAM_USERS_VIEW+M5_SCOPE`。
- **处理与边界**：以 sys_dept.id 标识部门；按 M5 范围过滤目标部门及实际返回的用户。
- **主要数据**：sys_dept、sys_user。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `deptId` | path | `string` | 是 | 内部部门主键，字符串 |
| `includeDescendants` | query | `boolean` | 否 | DP01默认true：返回范围内全部节点；false仅返回范围根。DP02默认false：仅直属人员；true含范围内下属 |
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/departments/17/users?includeDescendants=true&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "userId": "101",
        "sgNo": "SG000123",
        "deptId": "17",
        "status": 1
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`/`OUT_OF_SCOPE`(403)、`INVALID_DEPT_MAPPING`/`BUSINESS_RULE_VIOLATION`(422：范围中目标不存在、组织循环或路径超长)、必要可信依赖失败503。

#### HR01 · `PUT /api/v1/integrations/hr/departments/{deptCode}` — HR 部门增量同步

- **调用方/权限**：可信 HR 服务；`HR_SERVICE`。
- **处理与边界**：事件按 (sourceSystem,eventId) 幂等，以可信 HR 部门编码查找或建立与 sys_dept.id 的确定映射，校验父部门、顺序版本与无环；部门名称不能用于关联，首次新建时由数据库生成内部部门 ID。
- **主要数据**：sys_dept、外部事件记录、签名。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `deptCode` | path | `string` | 是 | HR 部门稳定外部编码，须唯一映射到 sys_dept.id |
| `sourceSystem` | body | `string` | 是 | 可信外部系统名称 |
| `eventId` | body | `string` | 是 | 外部唯一事件 ID |
| `eventVersion` | body | `integer` | 是 | 人员/部门变更单调版本 |
| `deptCode` | body | `string` | 是 | HR 部门稳定外部编码，须唯一映射到 sys_dept.id |
| `deptName` | body | `string` | 是 | 部门名称（非唯一标识） |
| `parentDeptCode` | body | `string` | 是 | HR 上级部门外部编码，须稳定映射；根使用空字符串 |
| `managerSgNo` | body | `string` | 否 | 部门负责人 SG 号（可选）；首次省略允许，更新省略或null保留，不能清空；提供非空值须可信在职HR用户映射，不能以姓名推断 |
| `occurredAt` | body | `string` | 是 | 业务事件时间，含时区 |

**请求体示例**：
```json
{
  "sourceSystem": "HR",
  "eventId": "HR-D-001",
  "eventVersion": 10,
  "deptCode": "D017",
  "deptName": "示例部门",
  "parentDeptCode": "D001",
  "managerSgNo": "SG000123",
  "occurredAt": "2026-10-12T10:00:00+08:00"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "HR-D-001",
    "processingStatus": "APPLIED",
    "deptId": "17"
  }
}
```

**典型错误**：`INVALID_HR_ORDER`、`IDEMPOTENCY_CONFLICT`、`INVALID_DEPT_MAPPING`。

#### HR02 · `PUT /api/v1/integrations/hr/employees/{sgNo}` — HR 人员入职或资料同步

- **调用方/权限**：可信 HR 服务；`HR_SERVICE`。
- **处理与边界**：SG 号是确认的稳定人员标识；登录名用姓名拼音首字母，重名追加 SG 后六位；不设置初始明文密码。检验手机号唯一和部门 ID 映射，事件版本按人员顺序。
- **主要数据**：sys_user、sys_dept、外部事件记录、签名。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `sgNo` | path | `string` | 是 | 人员 SG 稳定标识 |
| `sourceSystem` | body | `string` | 是 | 可信外部系统名称 |
| `eventId` | body | `string` | 是 | 外部唯一事件 ID |
| `eventVersion` | body | `integer` | 是 | 人员/部门变更单调版本 |
| `sgNo` | body | `string` | 是 | 人员 SG 稳定标识 |
| `realName` | body | `string` | 是 | 演示姓名，明文入库 |
| `phone` | body | `string` | 是 | 规范化演示手机号，明文保存 |
| `deptCode` | body | `string` | 是 | HR 部门稳定外部编码，须唯一映射到 sys_dept.id |
| `employeeAction` | body | `string` | 否 | UPSERT（默认）/REHIRE/SUSPEND/RESUME；暂停保留授权且旧会话失效，恢复不增角色且旧令牌持续无效；离职仅REHIRE且不恢复旧授权 |
| `employmentType` | body | `string` | 是 | FORMAL 正式、OUTSOURCED 外包（固定枚举） |
| `occurredAt` | body | `string` | 是 | 业务事件时间，含时区 |

**请求体示例**：
```json
{
  "sourceSystem": "HR",
  "eventId": "HR-U-001",
  "eventVersion": 25,
  "sgNo": "SG000123",
  "realName": "示例用户",
  "phone": "13800000000",
  "deptCode": "D017",
  "employmentType": "FORMAL",
  "occurredAt": "2026-10-12T10:00:00+08:00"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "HR-U-001",
    "processingStatus": "APPLIED",
    "userId": "101",
    "username": "zs"
  }
}
```

**典型错误**：`DUPLICATE_RESOURCE`(409手机号/登录名唯一冲突)、`IDEMPOTENCY_CONFLICT`(409同号异内容)、`VERSION_CONFLICT`(409处理中)、`INVALID_HR_ORDER`/`INVALID_DEPT_MAPPING`(422)、`RESOURCE_NOT_FOUND`(404未知人员的非UPSERT动作)、可信依赖缺失503。

**人员状态边界（07实现选择，非新增负责人答复）**：未知人员只允许UPSERT，其余动作404；REHIRE仅允许已有离职账号，非离职REHIRE为422。普通UPSERT不解除暂停；再次SUSPEND只推进新的HR版本，不再次增加sv；RESUME不增sv、不新增角色。REHIRE递增sv并核对无有效旧授权/管理员资格，保留账号ID、登录名和合法凭据。所有必填资料继续校验。

#### HR03 · `POST /api/v1/integrations/hr/transfers` — HR 调岗事件

- **调用方/权限**：可信 HR 服务；`HR_SERVICE`。
- **处理与边界**：Service在同一事务回收旧岗位来源、更新所属部门、签名审计及事件结果；不需要旧部门确认，不自动授予新岗位角色。提交后返回完成，失败回滚本次业务，不能分阶段报告部分调岗完成。
- **主要数据**：sys_user、授权来源记录、外部事件及阶段状态。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `sourceSystem` | body | `string` | 是 | 可信外部系统名称 |
| `eventId` | body | `string` | 是 | 外部唯一事件 ID |
| `eventVersion` | body | `integer` | 是 | 人员/部门变更单调版本 |
| `sgNo` | body | `string` | 是 | 人员 SG 稳定标识 |
| `fromDeptCode` | body | `string` | 是 | 原部门 HR 稳定编码 |
| `toDeptCode` | body | `string` | 是 | 新部门 HR 稳定编码 |
| `occurredAt` | body | `string` | 是 | 业务事件时间，含时区 |

**请求体示例**：
```json
{
  "sourceSystem": "HR",
  "eventId": "HR-T-001",
  "eventVersion": 26,
  "sgNo": "SG000123",
  "fromDeptCode": "D017",
  "toDeptCode": "D018",
  "occurredAt": "2026-10-12T10:00:00+08:00"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "HR-T-001",
    "processingStatus": "APPLIED",
    "stage": "OLD_GRANTS_REVOKED_AND_DEPT_MOVED",
    "awaitingNewRoleGrant": true
  }
}
```

**典型错误**：`INVALID_HR_ORDER`/`INVALID_DEPT_MAPPING`/`BUSINESS_RULE_VIOLATION`(422)、`IDEMPOTENCY_CONFLICT`/`VERSION_CONFLICT`(409)、`RESOURCE_NOT_FOUND`(404)、`AUTH_STATE_UNAVAILABLE`(503)。

**调岗会话与来源边界**：fromDeptCode必须等于当前所属部门，已离职或原目标相同拒绝422。HR02部门变化复用HR03内部move能力，人员版本每事件只推进一次；不增加sv（JWT不含角色，M6每次读取最新事实），不由A改grant_revision。冻结目标允许HR归属变更，业务限制仍由冻结祖先链决定；已合并目标拒绝。

#### HR04 · `POST /api/v1/integrations/hr/terminations` — HR 离职事件

- **调用方/权限**：可信 HR 服务；`HR_SERVICE`。
- **处理与边界**：只由 HR 触发离职，立即阻止业务访问且保留历史记录；复职不得自动恢复旧授权。
- **主要数据**：sys_user、外部事件记录、签名。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `sourceSystem` | body | `string` | 是 | 可信外部系统名称 |
| `eventId` | body | `string` | 是 | 外部唯一事件 ID |
| `eventVersion` | body | `integer` | 是 | 人员/部门变更单调版本 |
| `sgNo` | body | `string` | 是 | 人员 SG 稳定标识 |
| `effectiveAt` | body | `string` | 是 | 已生效时间，含时区；未来返回422，不改账号/版本，生效后使用新eventId |

**请求体示例**：
```json
{
  "sourceSystem": "HR",
  "eventId": "HR-L-001",
  "eventVersion": 27,
  "sgNo": "SG000123",
  "effectiveAt": "2026-10-12T10:00:00+08:00"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "HR-L-001",
    "processingStatus": "APPLIED",
    "accountStatus": 3
  }
}
```

**典型错误**：`INVALID_HR_ORDER`/`BUSINESS_RULE_VIOLATION`(422，含未来或已离职状态)、`IDEMPOTENCY_CONFLICT`/`VERSION_CONFLICT`(409)、`RESOURCE_NOT_FOUND`(404)、可信依赖缺失503。

外部事件登记和业务成功结果同事务；失败整体回滚，不提供在线恢复。鉴权返回 allowed、reasonCode、decisionId，不返回权限证据摘要。

#### HR05 · `POST /api/v1/integrations/hr/merges` — HR 部门合并事件

- **调用方/权限**：可信 HR 服务；`HR_SERVICE`。
- **处理与边界**：保留空源行，merged_into_dept_id标记去向；源直属人员迁目标，直属子部门挂目标并重算子树路径，子部门自身冻结保留。源或祖先冻结时422，须先合法显式解冻。拒绝自合并/合入后代/已合并源或目标；源不再接收人员/任命。既有合法角色和来源历史保留，C同事务先限制失效管理员范围后重审，不自动扩大范围。eventVersion仅比较及推进源hr_version，源10目标40事件11成功后源11目标40。
- **主要数据**：sys_dept、sys_user、外部事件记录。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `sourceSystem` | body | `string` | 是 | 可信外部系统名称 |
| `eventId` | body | `string` | 是 | 外部唯一事件 ID |
| `eventVersion` | body | `integer` | 是 | 人员/部门变更单调版本 |
| `sourceDeptCode` | body | `string` | 是 | 合并源 HR 部门编码 |
| `targetDeptCode` | body | `string` | 是 | 合并目标 HR 部门编码 |
| `occurredAt` | body | `string` | 是 | 业务事件时间，含时区 |

**请求体示例**：
```json
{
  "sourceSystem": "HR",
  "eventId": "HR-M-001",
  "eventVersion": 11,
  "sourceDeptCode": "D017",
  "targetDeptCode": "D018",
  "occurredAt": "2026-10-12T10:00:00+08:00"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "HR-M-001",
    "processingStatus": "APPLIED",
    "rolesPreserved": true
  }
}
```

**典型错误**：`INVALID_HR_ORDER`、`IDEMPOTENCY_CONFLICT`、`INVALID_DEPT_MAPPING`。

#### DP03 · `POST /api/v1/departments/{deptId}/freeze` — 冻结部门业务权限

- **调用方/权限**：管理端；`IAM_DEPTS_FREEZE+M5_SCOPE`。
- **处理与边界**：只更新当前部门冻结标识，不停用登录、不删除角色。由 M6 对该子树受影响业务请求实施撤权保护。冻结管理员本人所在上级部门时须确保合法恢复渠道。
- **主要数据**：sys_dept、sys_data_signature。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `deptId` | path | `string` | 是 | 内部部门主键，字符串 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "reason": "组织权限核查"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "deptId": "17",
    "status": 0
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`/`OUT_OF_SCOPE`(403)、`INVALID_DEPT_MAPPING`/`BUSINESS_RULE_VIOLATION`(422：范围中目标不存在、已合并目标或非法组织路径)、必要可信依赖失败503。当前没有expectedVersion输入或VERSION_CONFLICT分支。

#### DP04 · `POST /api/v1/departments/{deptId}/unfreeze` — 解除部门冻结标识

- **调用方/权限**：管理端；`IAM_DEPTS_FREEZE+M5_SCOPE`。
- **处理与边界**：仅解除该部门冻结；祖先仍冻结时不可使用业务 API，不自动增授角色；需由未受冻结限制且具备范围权限的上级管理员操作。
- **主要数据**：sys_dept、sys_data_signature。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `deptId` | path | `string` | 是 | 内部部门主键，字符串 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "reason": "核查完成"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "deptId": "17",
    "status": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`(400)、`INVALID_TOKEN`(401)、`PERMISSION_DENIED`/`OUT_OF_SCOPE`(403)、`INVALID_DEPT_MAPPING`/`BUSINESS_RULE_VIOLATION`(422：范围中目标不存在、已合并目标或非法组织路径)、必要可信依赖失败503。解除自身标识仍受祖先冻结，当前没有VERSION_CONFLICT分支。

### 4.3 M3 应用权限资源管理

#### RE01 · `GET /api/v1/applications` — 查询全局应用

- **调用方/权限**：管理端；`IAM_RESOURCES_VIEW+ADMIN`。
- **处理与边界**：应用是集团全局资源；只读按 RBAC0 功能资格及管理端规则，不附加分公司限制。
- **主要数据**：sys_application。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/applications?page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "appId": "12",
        "appCode": "ATTEND",
        "appName": "考勤系统",
        "status": 1
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE02 · `POST /api/v1/applications` — 登记全局应用

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：仅有效一级总公司管理员且具备对应资源维护功能权限可新增应用；登记应用本身不创建可信业务服务凭据。
- **主要数据**：sys_application。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appCode` | body | `string` | 是 | 应用编码，查询筛选；鉴权身份以服务凭据绑定为准 |
| `appName` | body | `string` | 是 | 应用名称 |
| `description` | body | `string` | 是 | 描述 |

**请求体示例**：
```json
{
  "appCode": "ATTEND",
  "appName": "考勤系统",
  "description": "演示业务系统"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "appId": "12",
    "appCode": "ATTEND",
    "status": 1
  }
}
```

**典型错误**：`PERMISSION_DENIED`、`DUPLICATE_RESOURCE`、`INTEGRITY_FAILURE`。

#### RE03 · `PATCH /api/v1/applications/{appId}` — 修改或停用应用

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：一级管理员操作；停用使所属菜单/API 失效但不删除授权历史，启用后按当前权限重新计算。
- **主要数据**：sys_application。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appId` | path | `string` | 是 | 内部应用 ID，字符串 |
| `appName` | body | `string` | 是 | 应用名称 |
| `status` | body | `integer` | 是 | 有效状态；具体值依对应实体 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "appName": "考勤系统",
  "status": 0,
  "reason": "系统维护"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "appId": "12",
    "status": 0
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE04 · `GET /api/v1/applications/{appId}/menus` — 查询应用菜单资源树

- **调用方/权限**：管理端；`IAM_RESOURCES_VIEW+ADMIN`。
- **处理与边界**：资源管理视图可展示停用的目录/菜单；与 M6 只返回有效菜单的 AU02 不同。
- **主要数据**：sys_menu。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appId` | path | `string` | 是 | 内部应用 ID，字符串 |

**请求示例**：`GET /api/v1/applications/12/menus`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "appId": "12",
    "menus": [
      {
        "menuId": "300",
        "menuType": 1,
        "status": 1,
        "parentId": "0"
      },
      {
        "menuId": "301",
        "menuType": 2,
        "status": 1,
        "parentId": "300"
      }
    ]
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE05 · `POST /api/v1/applications/{appId}/menus` — 创建导航目录或权限菜单

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：一级管理员创建，同一应用校验 parentId、menuCode 唯一、无环；menuType=1 目录不可分配给角色或关联 API。
- **主要数据**：sys_menu。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appId` | path | `string` | 是 | 内部应用 ID，字符串 |
| `menuCode` | body | `string` | 是 | 应用内唯一菜单编码 |
| `menuName` | body | `string` | 是 | 菜单名称 |
| `menuType` | body | `integer` | 是 | 1目录、2权限菜单 |
| `parentId` | body | `string` | 是 | 父菜单 ID；0 表示根 |
| `path` | body | `string` | 是 | 前端导航路径 |

**请求体示例**：
```json
{
  "menuCode": "ATTEND_QUERY",
  "menuName": "考勤查询",
  "menuType": 2,
  "parentId": "300",
  "path": "/attendance/query"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "menuId": "301",
    "menuType": 2,
    "status": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE06 · `PATCH /api/v1/menus/{menuId}` — 修改、移动或停用菜单

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：新增 parentId 字段支持同应用移动，校验无环；移动保留原菜单 ID、角色授权和 API 映射，按新祖先状态重算。目录停用使后代菜单逻辑失效，不批量改写后代 status。
- **主要数据**：sys_menu。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `menuId` | path | `string` | 是 | 内部菜单 ID，字符串 |
| `parentId` | body | `string` | 是 | 父菜单 ID；0 表示根 |
| `status` | body | `integer` | 是 | 有效状态；具体值依对应实体 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "parentId": "302",
  "status": 1,
  "reason": "调整菜单层级"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "menuId": "301",
    "parentId": "302",
    "status": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`MENU_TREE_CYCLE`、`VERSION_CONFLICT`、`AUTH_STATE_UNAVAILABLE`。

#### RE07 · `GET /api/v1/applications/{appId}/apis` — 查询应用 API 资源

- **调用方/权限**：管理端；`IAM_RESOURCES_VIEW+ADMIN`。
- **处理与边界**：返回资源登记状态，未知或停用 API 可用于管理查看但不能用于鉴权。
- **主要数据**：sys_api。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appId` | path | `string` | 是 | 内部应用 ID，字符串 |
| `httpMethod` | query | `string` | 否 | HTTP 方法，如 GET/POST/PATCH |
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/applications/12/apis?httpMethod=GET&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "apiId": "41",
        "apiCode": "ATTEND_LIST",
        "apiPath": "/attendance/records/{id}",
        "httpMethod": "GET",
        "status": 1
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE08 · `POST /api/v1/applications/{appId}/apis` — 登记受保护 API

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：由可信业务后端提供真实标准路由模板；M3 校验 (app_id,http_method,api_path) 唯一、规范化和模板冲突，不承担真实请求的在线鉴权。
- **主要数据**：sys_api。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appId` | path | `string` | 是 | 内部应用 ID，字符串 |
| `apiCode` | body | `string` | 是 | API 维护编码；唯一性以 method/path/app 为准 |
| `apiName` | body | `string` | 是 | API 说明名称 |
| `apiPath` | body | `string` | 是 | 可信后端标准路由模板 |
| `httpMethod` | body | `string` | 是 | HTTP 方法，如 GET/POST/PATCH |

**请求体示例**：
```json
{
  "apiCode": "ATTEND_LIST",
  "apiName": "考勤记录查询",
  "apiPath": "/attendance/records/{id}",
  "httpMethod": "GET"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "apiId": "41",
    "apiPath": "/attendance/records/{id}",
    "httpMethod": "GET",
    "status": 1
  }
}
```

**典型错误**：`INVALID_ROUTE_TEMPLATE`、`DUPLICATE_RESOURCE`、`PERMISSION_DENIED`。

#### RE09 · `PATCH /api/v1/apis/{apiId}` — 修改或停用 API 资源

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：一级管理员维护；变更路由必须重复冲突校验，停用不删除历史关联，数据库事实、签名和审计事务提交后生效。
- **主要数据**：sys_api。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `apiId` | path | `string` | 是 | 内部 API ID，字符串 |
| `apiName` | body | `string` | 是 | API 说明名称 |
| `status` | body | `integer` | 是 | 有效状态；具体值依对应实体 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "apiName": "考勤记录查询",
  "status": 0,
  "reason": "接口停用"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "apiId": "41",
    "status": 0
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE11 · `GET /api/v1/menus/{menuId}/apis` — 查询菜单关联 API

- **调用方/权限**：管理端；`IAM_RESOURCES_VIEW+ADMIN`。
- **处理与边界**：返回目录以外权限菜单对应的当前登记 API 集合。
- **主要数据**：sys_menu_api、sys_api。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `menuId` | path | `string` | 是 | 内部菜单 ID，字符串 |

**请求示例**：`GET /api/v1/menus/301/apis`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "menuId": "301",
    "apiIds": [
      "41",
      "42"
    ],
    "mappingVersion": "v5"
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RE10 · `PUT /api/v1/menus/{menuId}/apis` — 全量替换菜单—API 关联

- **调用方/权限**：管理端；`IAM_RESOURCES_WRITE+M5_L1`。
- **处理与边界**：只允许权限菜单与同一应用的 API 关联；同一 API 可被不同菜单关联。版本冲突返回 409，不静默覆盖。
- **主要数据**：sys_menu_api、sys_menu.api_revision、sys_permission_revision。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `menuId` | path | `string` | 是 | 内部菜单 ID，字符串 |
| `apiIds` | body | `array` | 是 | 全量关联 API ID 集合 |
| `expectedVersion` | body | `string` | 是 | 期望修订号；冲突不得覆盖 |

**请求体示例**：
```json
{
  "apiIds": [
    "41",
    "42"
  ],
  "expectedVersion": "v5"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "menuId": "301",
    "apiIds": [
      "41",
      "42"
    ],
    "mappingVersion": "v6"
  }
}
```

**典型错误**：`VERSION_CONFLICT`、`BUSINESS_RULE_VIOLATION`、`INTEGRITY_FAILURE`。

### 4.4 M4 角色与授权管理

#### RL01 · `GET /api/v1/roles` — 查询全局角色列表

- **调用方/权限**：管理端；`IAM_ROLES_VIEW+ADMIN`。
- **处理与边界**：全局角色列表为配置读取，允许有查看功能资格的管理员；可授权角色范围应另通过 AD05 查询，不等同于全部角色。
- **主要数据**：sys_role。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/roles?page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "roleId": "5",
        "roleCode": "ATTEND_STAFF",
        "roleName": "考勤人员",
        "roleType": 1,
        "status": 1
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RL02 · `POST /api/v1/roles` — 创建业务或管理角色

- **调用方/权限**：管理端；`IAM_ROLES_WRITE+M5_L1`。
- **处理与边界**：仅有效一级管理员且拥有对应功能权限；不得通过普通接口创建 roleType=3 超级角色。
- **主要数据**：sys_role。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `roleCode` | body | `string` | 是 | 角色业务编码 |
| `roleName` | body | `string` | 是 | 角色名称 |
| `roleType` | body | `integer` | 是 | 1业务、2管理、3超级（普通接口禁止 3） |
| `description` | body | `string` | 是 | 描述 |

**请求体示例**：
```json
{
  "roleCode": "ATTEND_STAFF",
  "roleName": "考勤人员",
  "roleType": 1,
  "description": "考勤岗位"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "roleId": "5",
    "roleType": 1,
    "status": 1
  }
}
```

**典型错误**：`PERMISSION_DENIED`、`DUPLICATE_RESOURCE`、`BUSINESS_RULE_VIOLATION`。

#### RL03 · `GET /api/v1/roles/{roleId}` — 查询角色定义

- **调用方/权限**：管理端；`IAM_ROLES_VIEW+ADMIN`。
- **处理与边界**：停用角色仍可被管理查询，但不参与 M6 的有效菜单计算。
- **主要数据**：sys_role。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `roleId` | path | `string` | 是 | 全局角色 ID，字符串 |

**请求示例**：`GET /api/v1/roles/5`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "roleId": "5",
    "roleCode": "ATTEND_STAFF",
    "roleName": "考勤人员",
    "roleType": 1,
    "status": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RL04 · `PATCH /api/v1/roles/{roleId}` — 修改或停用角色

- **调用方/权限**：管理端；`IAM_ROLES_WRITE+M5_L1`。
- **处理与边界**：仅一级管理员修改全局角色；普通接口不可编辑超级角色；停用不删用户角色关系或历史授权依据。
- **主要数据**：sys_role。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `roleId` | path | `string` | 是 | 全局角色 ID，字符串 |
| `roleName` | body | `string` | 是 | 角色名称 |
| `status` | body | `integer` | 是 | 有效状态；具体值依对应实体 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "roleName": "考勤人员",
  "status": 0,
  "reason": "岗位权限调整"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "roleId": "5",
    "status": 0
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RL05 · `GET /api/v1/roles/{roleId}/menus` — 读取角色—菜单关系

- **调用方/权限**：管理端；`IAM_ROLES_VIEW+ADMIN`。
- **处理与边界**：返回关联权限菜单（menuType=2）及集合修订号；停用资源仍可在配置查询出现，但有效权限另由 M6 过滤。
- **主要数据**：sys_role_permission、sys_menu。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `roleId` | path | `string` | 是 | 全局角色 ID，字符串 |

**请求示例**：`GET /api/v1/roles/5/menus`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "roleId": "5",
    "menuIds": [
      "301"
    ],
    "permissionRevision": "v17"
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### RL06 · `PUT /api/v1/roles/{roleId}/menus` — 替换角色全部菜单

- **调用方/权限**：管理端；`IAM_ROLES_WRITE+M5_L1`。
- **处理与边界**：仅一级管理员，验证 menuType=2、资源关系、版本及签名；影响全部持有该角色的用户，变更及签名/审计同事务提交。
- **主要数据**：sys_role_permission、sys_role.menu_revision、sys_permission_revision。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `roleId` | path | `string` | 是 | 全局角色 ID，字符串 |
| `menuIds` | body | `array` | 是 | 全量角色菜单 ID 集合 |
| `expectedVersion` | body | `string` | 是 | 期望修订号；冲突不得覆盖 |

**请求体示例**：
```json
{
  "menuIds": [
    "301",
    "305"
  ],
  "expectedVersion": "v17"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "roleId": "5",
    "menuIds": [
      "301",
      "305"
    ],
    "permissionRevision": "v18"
  }
}
```

**典型错误**：`VERSION_CONFLICT`、`BUSINESS_RULE_VIOLATION`、`INTEGRITY_FAILURE`。

#### US05 · `GET /api/v1/users/{userId}/roles` — 查询用户当前角色

- **调用方/权限**：管理端；`IAM_USERS_VIEW+M5_SCOPE`。
- **处理与边界**：返回当前有效角色及独立授权依据数量；历史失效依据不自动形成有效角色。
- **主要数据**：sys_user_role、授权来源记录。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |

**请求示例**：`GET /api/v1/users/101/roles`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "grantRevision": "v22",
    "userId": "101",
    "roles": [
      {
        "roleId": "5",
        "active": true,
        "activeSourceCount": 2
      }
    ]
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### GR05 · `GET /api/v1/users/{userId}/roles/{roleId}/sources` — 查询角色的独立授权依据

- **调用方/权限**：管理端；`IAM_GRANTS_VIEW+M5_SCOPE`。
- **处理与边界**：供调岗核查与精确回收使用；按用户范围限制，返回来源唯一 ID、来源类型及有效状态，不泄露额外敏感审批材料。
- **主要数据**：授权来源记录、sys_user_role。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |
| `roleId` | path | `string` | 是 | 全局角色 ID，字符串 |

**请求示例**：`GET /api/v1/users/101/roles/5/sources`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "grantRevision": "v22",
    "userId": "101",
    "roleId": "5",
    "sources": [
      {
        "grantSourceId": "901",
        "sourceType": "DEPT_POSITION",
        "sourceRef": "POST-01",
        "sourceDeptId": "17",
        "active": true
      },
      {
        "grantSourceId": "902",
        "sourceType": "OA_APPROVAL",
        "sourceRef": "OA-001",
        "active": true
      }
    ]
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### GR01 · `POST /api/v1/grants/user-roles` — 为用户授予角色并登记来源

- **调用方/权限**：管理端；`IAM_GRANTS_WRITE+M5_SCOPE+DELEGABLE_ROLE`。
- **处理与边界**：M5 必须同时验证操作者管理员任命、目标用户所属范围及可授权角色资格：一级按集团角色与等级资格边界，二级及以下按上级配置清单；允许在同样条件下给自己授权。新操作生成唯一 grantSourceId，同角色不同来源可并存；读取US05/GR05的grantRevision作为expectedVersion。
- **主要数据**：sys_user_role、授权来源记录、版本及签名。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | body | `string` | 是 | 内部用户主键，字符串 |
| `roleId` | body | `string` | 是 | 全局角色 ID，字符串 |
| `sourceType` | body | `string` | 是 | 授权业务来源类型（固定枚举） |
| `sourceRef` | body | `string` | 是 | 该授权来源的稳定业务依据 |
| `sourceDeptId` | body | `string` | 条件必填 | DEPT_POSITION必填，校验为目标用户当前部门 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |
| `expectedVersion` | body | `string` | 是 | 期望修订号；冲突不得覆盖 |

**请求体示例**：
```json
{
  "userId": "101",
  "roleId": "5",
  "sourceType": "MANUAL",
  "sourceRef": "WORK-2026-001",
  "reason": "岗位需求",
  "expectedVersion": "v22"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "101",
    "roleId": "5",
    "grantSourceId": "901",
    "roleEffective": true,
    "permissionRevision": "v23"
  }
}
```

**典型错误**：`ROLE_NOT_DELEGABLE`、`OUT_OF_SCOPE`、`VERSION_CONFLICT`、`BUSINESS_RULE_VIOLATION`。

#### GR02 · `POST /api/v1/grants/user-roles/revoke` — 按授权依据回收角色

- **调用方/权限**：管理端；`IAM_GRANTS_WRITE+M5_SCOPE+DELEGABLE_ROLE`。
- **处理与边界**：必须指定 grantSourceId，避免误删同一用户角色的其他来源。管理员可撤销自己有权管理的角色；仅当最后有效依据被撤销，角色才失效。
- **主要数据**：sys_user_role、授权来源记录、版本及签名。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | body | `string` | 是 | 内部用户主键，字符串 |
| `roleId` | body | `string` | 是 | 全局角色 ID，字符串 |
| `grantSourceId` | body | `string` | 是 | 独立授权来源 ID；回收时精确指定 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |
| `expectedVersion` | body | `string` | 是 | 期望修订号；冲突不得覆盖 |

**请求体示例**：
```json
{
  "userId": "101",
  "roleId": "5",
  "grantSourceId": "901",
  "reason": "岗位权限终止",
  "expectedVersion": "v23"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "101",
    "roleId": "5",
    "revokedSourceId": "901",
    "roleEffective": true,
    "remainingActiveSources": 1
  }
}
```

**典型错误**：`GRANT_SOURCE_NOT_FOUND`、`ROLE_NOT_DELEGABLE`、`OUT_OF_SCOPE`、`VERSION_CONFLICT`。

#### GR03 · `POST /api/v1/grants/user-roles/batch` — 批量授予或按依据回收

需 JWT，逐项校验 IAM_GRANTS_WRITE、管理员范围及可授权角色。请求体 operation 为 GRANT / REVOKE，roleId、targets、reason 必填；targets 原始长度为 1–100，每位用户一个目标。GRANT 目标包含 userId、sourceType、sourceRef，岗位来源另有 sourceDeptId；REVOKE 包含 userId、grantSourceId。dryRun=true 不支持，返回 400。

同步逐人事务执行，全部结果直接以 HTTP 200 / code=OK 返回。不创建后台任务，没有 batchId、分页、待执行状态、后台自动重试或重启恢复。Idempotency-Key 可省略，若传入仅校验格式，不提供批量请求去重；重复授予仍以稳定授权来源键去重。只有明确业务失败目标可以修正后手工重试，网络或数据库结果不确定时先查询用户角色及来源。

```json
{"success":true,"code":"OK","requestId":"req-demo","data":{"processingStatus":"PARTIAL_SUCCESS","succeeded":1,"failed":1,"total":2,"items":[{"userId":"101","status":"APPLIED","grantSourceId":"901"},{"userId":"102","status":"FAILED","code":"OUT_OF_SCOPE"}]}}
```

请求格式和人数错误为 400，无有效身份为 401；每项业务拒绝记录在 items；基础设施故障返回 503，先前已提交项不会撤销。

#### 已取消的 GR06（历史编号）

此接口已移除，访问返回 404。批量结果由 GR03 同步返回。

#### GR04 · `GET /api/v1/grants/history` — 查询角色授权变更历史

- **调用方/权限**：管理端；`IAM_GRANTS_VIEW+M5_SCOPE`。
- **处理与边界**：查询来源级别的授权或回收事件，包含操作者、OA/岗位来源和时间；历史事实不以授权人的当前管理员身份重判。
- **主要数据**：授权来源记录、sys_operation_log。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | query | `string` | 否 | 内部用户主键，字符串 |
| `roleId` | query | `string` | 否 | 全局角色 ID，字符串 |
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/grants/history?userId=101&roleId=5&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "eventId": "GRANT-001",
        "userId": "101",
        "roleId": "5",
        "grantSourceId": "901",
        "operation": "GRANT",
        "occurredAt": "2026-10-12T10:00:00+08:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### OA01 · `POST /api/v1/integrations/oa/decisions` — 执行 OA 已批准角色授权或回收

- **调用方/权限**：可信 OA 服务；`OA_SERVICE_SCOPE`。
- **处理与边界**：由可信 OA 服务已获准的角色、对象和操作范围决定执行资格，不重新检查原申请人的当前管理任命。OA 身份不能代表超级用户。撤销时指定要回收的审批授权依据，不误删其他来源；同一审批单和目标角色的有效 OA 授权来源须防重复登记，不仅检查 eventId。
- **主要数据**：外部事件记录、授权来源记录、sys_operation_log。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。
- **补充**：REVOKE 事件须包含需撤销的 grantSourceId 或原批准单 originalApprovalId，必须能唯一定位 OA 授权依据；原申请人标识如提供仅作审计，不作执行期授权判断。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `sourceSystem` | body | `string` | 是 | 可信外部系统名称 |
| `eventId` | body | `string` | 是 | 外部唯一事件 ID |
| `approvalId` | body | `string` | 是 | 外部审批单号 |
| `approvalStatus` | body | `string` | 是 | 审批结论，仅 APPROVED 可执行 |
| `operation` | body | `string` | 是 | GRANT 或 REVOKE（精确大写枚举） |
| `targetUserId` | body | `string` | 是 | 授权目标用户 ID |
| `roleId` | body | `string` | 是 | 全局角色 ID，字符串 |
| `approvedAt` | body | `string` | 是 | 批准时间，ISO 8601含时区 |
| `grantSourceId` | body | `string` | 条件必填 | REVOKE时与originalApprovalId二选一 |
| `originalApprovalId` | body | `string` | 条件必填 | REVOKE时唯一定位原OA授权，不能使用本次回收审批单替代 |

**请求体示例**：
```json
{
  "sourceSystem": "OA",
  "eventId": "OA-001",
  "approvalId": "APPR-001",
  "approvalStatus": "APPROVED",
  "operation": "GRANT",
  "targetUserId": "101",
  "roleId": "5",
  "approvedAt": "2026-10-12T10:00:00+08:00"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "OA-001",
    "processingStatus": "APPLIED",
    "grantSourceId": "902",
    "idempotentReplay": false
  }
}
```

**典型错误**：`INVALID_SERVICE`、`OA_SCOPE_DENIED`、`IDEMPOTENCY_CONFLICT`、`BUSINESS_RULE_VIOLATION`。

### 4.5 M5 管理员与管理范围控制

#### AD01 · `GET /api/v1/admins` — 按管理范围查询管理员

- **调用方/权限**：管理端；`IAM_ADMINS_VIEW+M5_SCOPE`。
- **处理与边界**：返回有效/失效任命并对目标部门做范围过滤；二级包括分公司和总部直属部门，两者不可互相越界。
- **主要数据**：sys_admin、sys_dept。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `deptId` | query | `string` | 否 | 内部部门主键，字符串 |
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/admins?deptId=17&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "userId": "9",
        "adminLevel": 2,
        "deptId": "17",
        "status": 1
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### AD02 · `POST /api/v1/admins/appointments` — 任命同级或下级管理员

- **调用方/权限**：管理端；`IAM_ADMINS_WRITE+M5_SCOPE`。
- **处理与边界**：校验本人任命等级与管理根、目标部门和被任命等级；同级任命必须满足管理范围。角色授予不替代任命。
- **主要数据**：sys_admin、签名/审计。
- **权限状态影响**：可能扩大权限，数据库事务提交后生效。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | body | `string` | 是 | 内部用户主键，字符串 |
| `adminLevel` | body | `integer` | 是 | 管理员等级，数字越小级别越高 |
| `deptId` | body | `string` | 是 | 内部部门主键，字符串 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "userId": "110",
  "adminLevel": 3,
  "deptId": "17",
  "reason": "岗位管理任命"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "110",
    "adminLevel": 3,
    "deptId": "17",
    "status": 1
  }
}
```

**典型错误**：`OUT_OF_SCOPE`、`BUSINESS_RULE_VIOLATION`、`VERSION_CONFLICT`。

#### AD03 · `POST /api/v1/admins/{userId}/revoke` — 撤销管理员任命

- **调用方/权限**：管理端；`IAM_ADMINS_WRITE+M5_SCOPE`。
- **处理与边界**：允许同管理根、同等级管理员互撤，也允许本人自撤；立即撤销 M5 任命资格，但不自动回收本人管理功能角色或此前授予他人的角色。
- **主要数据**：sys_admin、签名/审计。
- **权限状态影响**：可能缩减权限，遵循数据库事务和会话版本规则。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "reason": "管理职责结束"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "110",
    "status": 0,
    "appointmentRevoked": true
  }
}
```

**典型错误**：`OUT_OF_SCOPE`、`BUSINESS_RULE_VIOLATION`。

#### AD04 · `GET /api/v1/admins/me/scope` — 查询本人管理资格与范围

- **调用方/权限**：管理端；`IAM_ADMINS_VIEW+SELF`。
- **处理与边界**：从 JWT 获取本人；展示任命状态、等级、管理根及可授权清单版本，仅用于页面提示，不能替代后端对象校验。
- **主要数据**：sys_admin、sys_dept、可授权角色清单。

**请求示例**：`GET /api/v1/admins/me/scope`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "appointed": true,
    "adminLevel": 2,
    "deptId": "17",
    "scopeRootIds": [
      "17"
    ],
    "delegableRolesVersion": "v4"
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### AD05 · `GET /api/v1/admins/{userId}/delegable-roles` — 查询管理员可授权角色清单

- **调用方/权限**：管理端；`IAM_GRANTS_VIEW+M5_SCOPE`。
- **处理与边界**：管理员本人可查询可授予角色，管理上级可查询其获准管理的下级清单；角色清单不等于本人已拥有的角色。
- **主要数据**：可授权角色清单、sys_admin。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |

**请求示例**：`GET /api/v1/admins/101/delegable-roles`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "110",
    "roleIds": [
      "5",
      "8"
    ],
    "delegationVersion": "v4"
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### AD06 · `PUT /api/v1/admins/{userId}/delegable-roles` — 一级管理员直接配置清单

仅一级管理员可以配置；目标为较低等级有效管理员，非一级调用返回 DELEGATION_OUT_OF_SCOPE。替换只更新目标本人，不影响其他管理员清单。

- **调用方/权限**：管理端；`IAM_GRANTS_DELEGATE+M5_SCOPE`。
- **处理与边界**：仅有委派功能资格的有效上级管理员可配置下级清单；下级须属于上级管理范围，且每个角色在上级自身可合法委派集合内；超级角色禁止。全量替换采用版本比较，清单缩减立即影响后续授权操作，不自动撤销已合法授予其他人的角色。
- **主要数据**：可授权角色清单、sys_admin、版本/审计。
- **权限状态影响**：可能扩大或缩减权限，在数据库事务中更新业务事实、签名与审计。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |
| `roleIds` | body | `array` | 是 | 可授予角色 ID 集合 |
| `expectedVersion` | body | `string` | 是 | 期望修订号；冲突不得覆盖 |
| `reason` | body | `string` | 是 | 操作原因和审计依据 |

**请求体示例**：
```json
{
  "roleIds": [
    "5",
    "8"
  ],
  "expectedVersion": "v4",
  "reason": "配置下级授权范围"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "110",
    "roleIds": [
      "5",
      "8"
    ],
    "delegationVersion": "v5"
  }
}
```

**典型错误**：`DELEGATION_OUT_OF_SCOPE`、`ROLE_NOT_DELEGABLE`、`VERSION_CONFLICT`。

### 4.6 M6 在线权限鉴定

#### AU02 · `GET /api/v1/auth/menus` — 查询本人指定应用有效菜单

- **调用方/权限**：已登录用户/业务端；`USER_SELF`。
- **处理与边界**：用户只能查本人菜单；部门冻结时返回空业务菜单，JWT 无效 401；补全祖先目录仅供显示不新增授权。
- **主要数据**：当前数据库授权关系、sys_menu、授权关系。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `appCode` | query | `string` | 是 | 应用编码，查询筛选；鉴权身份以服务凭据绑定为准 |

**请求示例**：`GET /api/v1/auth/menus?appCode=ATTEND`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "appCode": "ATTEND",
    "menus": [
      {
        "menuId": "301",
        "menuCode": "ATTEND_QUERY",
        "menuName": "考勤查询",
        "path": "/attendance/query"
      }
    ]
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### AU03 · `POST /api/v1/auth/check` — 业务服务端实时 API 鉴权

当前成功 data 仅含 allowed、reasonCode、decisionId；基础日志不保存完整角色/组织/签名事实快照。

- **调用方/权限**：可信业务服务端；`TRUSTED_SERVICE+USER_JWT`。
- **处理与边界**：服务端可信凭据绑定 appCode；HTTP 方法和实际路由由业务服务端识别，用户从 JWT 确认。检查账号、部门祖先、有效菜单、API 及集合修订号与签名，并可靠接收审计。仅 data.allowed=true 才能执行；对象级 M5 检查仍在实际业务入口进行。
- **主要数据**：当前数据库事实、集合修订号及签名、审计。
- **补充**：请求头同时要求用户 Bearer JWT 和可信服务凭据；不能让浏览器直接调用此接口。HTTP 200 仅表示成功取得决策，仍必须查看 data.allowed。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `httpMethod` | body | `string` | 是 | HTTP 方法，如 GET/POST/PATCH |
| `routeTemplate` | body | `string` | 是 | 业务服务端实际匹配的可信路由模板 |

**请求体示例**：
```json
{
  "httpMethod": "GET",
  "routeTemplate": "/attendance/records/{id}"
}
```

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "allowed": true,
    "reasonCode": "ALLOW",
    "decisionId": "AUTH-001"}
}
```

**典型错误**：`INVALID_SERVICE`、`INVALID_TOKEN`、`INVALID_ROUTE_TEMPLATE`、`AUTH_STATE_UNAVAILABLE`、`INTEGRITY_FAILURE`。

#### US06 · `GET /api/v1/users/{userId}/permissions` — 管理员查询用户当前有效菜单

- **调用方/权限**：管理端；`IAM_USERS_VIEW+M5_SCOPE`。
- **处理与边界**：读取当前当前数据库菜单视图，检查实际目标用户所属部门；不返回停用角色或冻结菜单带来的无效权限。
- **主要数据**：当前数据库授权关系、组织状态。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | path | `string` | 是 | 内部用户主键，字符串 |
| `appCode` | query | `string` | 是 | 应用编码，查询筛选；鉴权身份以服务凭据绑定为准 |

**请求示例**：`GET /api/v1/users/101/permissions?appCode=ATTEND`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "userId": "101",
    "appCode": "ATTEND",
    "menus": [
      {
        "menuId": "301",
        "menuCode": "ATTEND_QUERY"
      }
    ]
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

### 4.7 PUB 公共支撑：审计查询

#### LG01 · `GET /api/v1/audit/operations` — 查询管理及事件操作日志

- **调用方/权限**：管理端；`IAM_AUDIT_VIEW+M5_SCOPE`。
- **处理与边界**：按操作者/目标对象的实际可见范围限制审计，不返回敏感请求值；记录保留不被普通业务删除。
- **主要数据**：sys_operation_log。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/audit/operations?page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "eventId": "EV-001",
        "operationType": "USER_ROLE_GRANT",
        "actorUserId": "9",
        "targetId": "101",
        "occurredAt": "2026-10-12T10:00:00+08:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### LG02 · `GET /api/v1/audit/authorizations` — 查询历史鉴权决策

- **调用方/权限**：管理端；`IAM_AUDIT_VIEW+M5_SCOPE`。
- **处理与边界**：查看当时的角色、菜单、API 与签名/版本证据，不根据现时权限表反推历史。
- **主要数据**：鉴权审计证据（逻辑存储）。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | query | `string` | 否 | 内部用户主键，字符串 |
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/audit/authorizations?userId=101&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "decisionId": "AUTH-001",
        "userId": "101",
        "apiId": "41",
        "allowed": true,

        "occurredAt": "2026-10-12T10:00:00+08:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### LG03 · `GET /api/v1/audit/logins` — 查询登录日志

- **调用方/权限**：管理端；`IAM_AUDIT_VIEW+M5_SCOPE`。
- **处理与边界**：返回成功/失败、时间和来源摘要，不披露密码、短信验证码或完整 JWT。
- **主要数据**：sys_login_log。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `userId` | query | `string` | 否 | 内部用户主键，字符串 |
| `page` | query | `integer` | 否 | 页码从 1 开始 |
| `pageSize` | query | `integer` | 否 | 每页条数，最大 500 |

**请求示例**：`GET /api/v1/audit/logins?userId=101&page=1&pageSize=20`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "items": [
      {
        "userId": "101",
        "loginMethod": "PHONE_PASSWORD",
        "success": true,
        "occurredAt": "2026-10-12T10:00:00+08:00"
      }
    ],
    "page": 1,
    "pageSize": 20,
    "total": 1
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

#### LG04 · `GET /api/v1/audit/evidence/{eventId}` — 查询授权或变更审计证据

- **调用方/权限**：管理端；`IAM_AUDIT_VIEW+M5_SCOPE`。
- **处理与边界**：审计证据包括事件摘要、当时授权版本、签名信息和相关 requestId；普通接口只允许读取，不宣称当前库已具备 WORM 防篡改能力。
- **主要数据**：sys_operation_log、sys_data_signature、证据索引。

| 字段 | 位置 | 类型 | 必填 | 说明 |
|---|---|---|---|---|
| `eventId` | path | `string` | 是 | 外部唯一事件 ID |

**请求示例**：`GET /api/v1/audit/evidence/EV-001`。

**HTTP 200 响应示例**：
```json
{
  "success": true,
  "code": "OK",
  "requestId": "req-demo",
  "data": {
    "eventId": "EV-001",
    "requestId": "req-demo",
    "evidenceType": "GRANT_CHANGE",

    "integrityVerified": true
  }
}
```

**典型错误**：`INVALID_ARGUMENT`、`PERMISSION_DENIED`、`AUTH_STATE_UNAVAILABLE`。

## 5. 外部系统与关键调用时序

| 场景 | 请求与校验顺序 | 必须保障的结果 |
|---|---|---|
| 普通登录 | AU01（USERNAME 或 PHONE，两种均为密码登录）；需要时 AU05→AU07/AU08 设密或重置 | 部门冻结不阻止登录或密码安全操作，业务权限由 M6 决定 |
| 业务系统访问 | AU02 查询菜单；业务服务收到 HTTP 请求后可信映射真实路由→AU03 | 仅 `allowed=true` 可放行；管理业务还需 M5 对真实对象检查 |
| 员工调岗 | HR03 → Service 开启事务 → M4 仅回收旧岗位来源 → 更新部门及签名/审计 → 提交 → 新管理员后续 GR01 | 新部门不自动分配角色；保留其他独立合法来源 |
| OA 批准授权 | OA01 → 验证 OA 服务及审批范围 → M4 登记授权依据 → 事务提交 | 原申请人现时失权不当然撤销已批准事件 |
| 角色清单委派 | AD05 读取可委派范围 → AD06 版本检查 → 后续 GR01/GR02 | 下级只能授予上级允许的角色；自身授权同样受控 |
| 目录停用/移动 | RE06 → M3 更新目录树并计算影响范围 → 事务提交 | 保留已有菜单和授权关系，祖先停用时不再提供有效 API 权限 |
| 批量授权 | GR03 校验1–100人 → 逐项事务提交 → 同步返回全部结果 | 允许部分成功，失败项幂等重试，不能因一项失败隐式回滚全部 |

## 6. 契约测试与验收矩阵

| 编号 | 测试场景 | 相关接口 | 通过条件 |
|---|---|---|---|
| T01 | 用户名/手机号密码登录、验证码用途隔离 | AU01、AU05、AU07、AU08 | 凭据正确成功；错用途/重复核销拒绝；未设密账号拒绝登录 |
| T02 | 首次设密、重置密码、冻结部门仍可登录 | AU07、AU08、AU01 | 重置旧会话立即失效；冻结用户不能访问业务 |
| T03 | 临时账号手机号唯一，无初始密码；员工 SG 同步 | US03、HR02 | 临时无 SG；正式/外包仅 HR 同步；重名追加 SG 后六位 |
| T04 | HR 相同事件重试/同号改内容/乱序 | HR01—HR05 | 幂等响应；冲突 409；旧版本不得覆盖 |
| T05 | 调岗与两项独立授权来源 | HR03、GR05、GR02、AU03 | 仅旧岗位依据撤销，合法剩余来源保留 |
| T06 | 部门合并后原角色保留、冻结链重算 | HR05、DP01、AU03 | 原角色不自动清除，受冻结影响者拒绝 |
| T07 | 停用目录及跨目录移动 | RE06、AU02、AU03 | 后代菜单逻辑失效；菜单移动保持原角色授权 |
| T08 | API多菜单映射，部分菜单无效 | RE10、AU03 | 仍有其他有效菜单映射则允许 |
| T09 | 二级管理员修改全局菜单/角色 | RE03、RL06 | 即使持有功能菜单，缺 L1 任命也拒绝 |
| T10 | 清单委派与自我角色授权 | AD05、AD06、GR01、GR02 | 合法可操作，超出可委派清单禁止 |
| T11 | 同根同级互撤与自撤 | AD02、AD03、AD04 | 撤任命立即无管理资格；不追删历史授予角色 |
| T12 | OA 通过后申请人离职/失权 | OA01 | 仍按 OA 服务受控范围执行，不重验申请人现时任命 |
| T13 | OA 试授超级角色/超范围目标 | OA01 | 拒绝，记录事件，不能凭服务身份获得无限权限 |
| T14 | 1–100人同步批量、部分失败、超限拒绝 | GR03 | 返回完整逐项结果，成功项不被业务失败项回滚 |
| T15 | 撤权与鉴权并发/事务回滚/重启 | GR02、AU03 | 撤权成功后新鉴权错误放行数为0 |
| T16 | 签名失效、数据库中断、审计失败 | AU03、LG04 | 无法读取当前事实时拒绝；不可用不默认允许 |
| T17 | 审计当时权限及授权来源 | LG01—LG04、GR05 | 可验证历史证据，不以当前授权替代 |
| T18 | 课堂规模数据的完整业务链路与耗时记录 | AU01、AU03 | 正确演示授权、鉴权、回收、拒绝 |

## 7. 数据库适配与联调基线

数据库v3.1共23张表，取消原缓存发布版本和栅栏表；补齐用户会话/授权/HR版本、部门HR版本、事件 JSON 输入及结果、服务公钥/OA范围、nonce、批量任务和逐项结果。字段定义以数据库说明和建表SQL为准。

内部Service协作在同一MySQL事务执行；跨功能写表通过负责模块的Service入口。外部HR/OA由测试请求模拟，短信使用可替换发送通道；这些能力均须演示完整成功及失败链路。部署地址、真实短信供应商账号、公私钥和环境密码属于部署配置，不阻塞课程功能开发，也不写入仓库。

组织者负责工程和Git；A负责M1/M2与事件记录，B负责M3及角色配置，C负责授权/OA/批量/M5，D负责M6及签名审计；前端与测试各一人。A/C稍多、B/D稍少，采用两阶段：第一阶段独立完成基础功能及公共入口，第二阶段补齐跨模块规则与联调。阶段中途先联调登录、授权、菜单、API允许和撤权拒绝；最终覆盖本规范全部56项功能及签名审计。阶段划分不改变HTTP字段、鉴权要求或数据库定义；未接入必要校验的入口不默认允许。详见 [两阶段开发计划](../../DEVELOPMENT_PLAN.md)。
