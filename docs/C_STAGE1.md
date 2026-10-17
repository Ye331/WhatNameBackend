# C 第一阶段：管理员与角色授权

本分支实现下表 12 个接口，供独立自测及团队集成。阶段范围依据 [开发分工](../DEVELOPMENT_PLAN.md)；测试证据见 [统一测试报告](C_STAGE1_TEST_REPORT.md)。真实认证、功能鉴权、签名和审计尚未接入，默认适配器明确拒绝，不能据此宣称完整权限链路验收通过。

## 1. 接口与验收映射

路径统一以 `/api/v1` 开头，响应使用 `ApiResponse`。除 AD04 查询本人任命状态外，接口均要求当前有效管理员资格，并按相应对象范围检查。

| 编号 | 方法与路径 | 功能权限 | 主要行为 | 对应测试类 |
|---|---|---|---|---|
| AD01 | GET `/admins` | `IAM_ADMINS_VIEW` | 按范围、可选部门子树过滤有效/撤销任命，再分页 | AdminControllerTest、AdminIT |
| AD02 | POST `/admins/appointments` | `IAM_ADMINS_WRITE` | 任命同级或下级；不得超出管理范围 | AdminControllerTest、AdminRulesTest、AdminIT |
| AD03 | POST `/admins/{userId}/revoke` | `IAM_ADMINS_WRITE` | 撤销、自撤、同根同级互撤；保留历史授权 | AdminRulesTest、AdminIT、GrantIT |
| AD04 | GET `/admins/me/scope` | `IAM_ADMINS_VIEW` | 本人任命状态、等级、管理根、清单版本 | AdminControllerTest、AdminServiceTest |
| AD05 | GET `/admins/{userId}/delegable-roles` | `IAM_GRANTS_VIEW` | 本人或管理上级查询可授权清单 | AdminControllerTest、AdminServiceTest |
| AD06 | PUT `/admins/{userId}/delegable-roles` | `IAM_GRANTS_DELEGATE` | 严格上级全量替换下级清单、版本检查、递归收缩 | AdminServiceTest、AdminIT |
| US05 | GET `/users/{userId}/roles` | `IAM_USERS_VIEW` | 有来源支撑的角色、有效来源数及授权版本 | GrantControllerTest、GrantIT |
| GR05 | GET `/users/{userId}/roles/{roleId}/sources` | `IAM_GRANTS_VIEW` | 有效与已回收来源、来源部门及授权版本 | GrantControllerTest、GrantIT |
| GR01 | POST `/grants/user-roles` | `IAM_GRANTS_WRITE` | 单人授予、来源去重、角色资格与版本检查 | GrantControllerTest、GrantServiceTest、GrantIT |
| GR02 | POST `/grants/user-roles/revoke` | `IAM_GRANTS_WRITE` | 按指定来源精确回收，保留其他来源 | GrantControllerTest、GrantServiceTest、GrantIT |
| GR03 | POST `/grants/user-roles/batch` | `IAM_GRANTS_WRITE` | 持久接收、幂等提交、异步逐项授予/回收 | BatchGrantIT、BatchAsyncIT |
| GR06 | GET `/grants/batches/{batchId}` | `IAM_GRANTS_VIEW` | 按当前范围过滤计数和逐项结果，再分页 | BatchGrantIT |

ID 是正整数十进制字符串，最大为有符号 BIGINT；不接受 JSON 数值 ID。`reason` 必填、非空白、最长 512 字符；`sourceRef` 非空白、最长 128 字符。管理员等级为 JSON 整数 1—65535。分页从 1 开始，默认 `pageSize=20`，最大 500。授权和批量 DTO 拒绝未知字段；不修改其他模块的全局 JSON 配置。

版本字段保留原契约：AD04 为 `delegableRolesVersion`，AD05/AD06 为 `delegationVersion`，US05/GR05 为 `grantRevision`，GR01 为 `permissionRevision`，写入请求使用 `expectedVersion`。清单版本为正 INT，用户授权版本为正 BIGINT，格式均为 `vN`；达到上限后拒绝变更。GR02 不返回新版本，客户端通过 US05/GR05 重查。

## 2. 已实现的业务规则

- **管理员**：一级管理集团范围，其余按任命管理根及部门子树管理；账号不可用、部门祖先冻结或任命撤销时拒绝管理。组织链缺失/循环时拒绝。本人角色、管理员任命、可授权清单分别判断，不能互相替代。
- **清单**：只允许范围包含目标的严格上级配置，不允许本人或同级修改；可清空，不允许重复角色 ID。缩减清单立即影响后续授权，并递归收缩可追踪的下级项；每个受影响管理员一次请求只递增一次版本，不追删既有用户授权。重新任命会清空旧清单并收缩相关下级项。
- **授予**：普通入口只创建 `MANUAL`、`DEPT_POSITION`；岗位来源部门必须等于目标用户当前部门，MANUAL 不带来源部门。授予要求目标账号及部门链可用；管理员自授予、自回收使用同样的资格检查。超级角色禁止普通授予。
- **精确回收**：来源必须属于指定用户和角色，否则返回 `GRANT_SOURCE_NOT_FOUND`。只将该来源置为已回收，保留回收人、时间及历史；最后一份有效来源消失才移除用户角色关系。目标账号暂停或部门冻结不阻止有资格的其他管理员回收。
- **并发与重复**：单人写入先检查当前资格和版本，再判断重复。同一有效来源重复授予、已回收来源重复回收不重复递增版本、签名或成功审计。来源唯一键沿用 MySQL 排序规则；同键不同来源部门冲突；已回收来源不复活，新业务使用新 `sourceRef`。旧版本返回 `VERSION_CONFLICT`。
- **完整性**：用户授权来源和有效关系集合必须一致；不一致返回 `INTEGRITY_FAILURE`，不自动修复历史数据。实际变更递增 `grant_revision`，不修改 `session_version`。`active`/`roleEffective` 只表示关系有来源支撑，API 是否允许仍由 D 判断。

授权来源历史不等于 GR04 审计历史查询。原授权人的任命撤销或清单缩减，也不会自动否定其此前合法建立的授权来源。

### 单人请求示例

先通过 `GET /api/v1/users/101/roles` 取得当前 `grantRevision`，再授予：

```http
POST /api/v1/grants/user-roles
Content-Type: application/json

{"userId":"101","roleId":"5","sourceType":"MANUAL","sourceRef":"WORK-001","reason":"工作需要","expectedVersion":"v1"}
```

使用授予响应中的来源 ID，重新查询当前版本后精确回收：

```http
POST /api/v1/grants/user-roles/revoke
Content-Type: application/json

{"userId":"101","roleId":"5","grantSourceId":"901","reason":"授权依据结束","expectedVersion":"v2"}
```

示例 ID、版本均为占位值，不是可用测试账号；真实认证上下文由 A 接入。

## 3. 批量执行与查询

```http
POST /api/v1/grants/user-roles/batch
Content-Type: application/json
Idempotency-Key: WORK-BATCH-001

{"operation":"GRANT","roleId":"5","targets":[{"userId":"101","sourceType":"MANUAL","sourceRef":"WORK-101"}],"reason":"工作需要","dryRun":false}
```

REVOKE 模式将 `operation` 改为 `REVOKE`，目标使用 `{"userId":"101","grantSourceId":"901"}`，不混入授予来源字段。批量契约没有 `expectedVersion`，逐项持锁后读取当前版本，单人接口仍强制检查版本。

- 新任务返回 **202/ACCEPTED**，data 为 `batchId`、`processingStatus=PENDING`、去重后 `total`；仅表示持久接收。任务和全部输入在同一事务落库，提交后调度。
- 幂等键在发起人范围内唯一，区分大小写，为 1—128 个不含空白的可打印 ASCII 字符。同键同内容返回 **200** 及原任务第一页结果，不再次执行；不同内容返回 **409 IDEMPOTENCY_CONFLICT**。重放仍检查当前写权限、查看权限和管理员范围。
- 目标按用户 ID 排序，完全相同的重复目标合并，同一用户参数不同则拒绝。摘要包含操作、角色、原因和全部规范化目标；不改写原因或来源引用的大小写/空白。
- 每项独立事务，重新检查当前发起人资格。来源、关系、版本、签名、审计及 `APPLIED` 结果一起提交；后续项失败不回滚已成功项。无变化的重复业务也可为 APPLIED，但不重复写成功审计。
- 业务异常回滚后独立保存 `FAILED`；数据库或无法确认的故障停止本次任务，未完成项保留 `PENDING`。单线程有界执行器最多等待 1000 个任务，调度失败保留持久任务。

查询 `GET /api/v1/grants/batches/7001?page=1&pageSize=20`，data 示例：

```json
{"batchId":"7001","processingStatus":"COMPLETED","succeeded":1,"failed":0,"pending":0,"items":[{"userId":"101","status":"APPLIED","grantSourceId":"901"}],"page":1,"pageSize":20,"total":1}
```

计数、total 和 items 只包含当前可管理目标，先过滤再分页；失败项提供 `code`。整体任务状态可能与可见子集不同，取值为 PENDING、PROCESSING、COMPLETED、PARTIAL_SUCCESS、FAILED。无可见目标返回 404；不存在用户的失败保存在数据库中，因无可验证范围暂不展示。查询可根据逐项结果推导终态，避免汇总未更新掩盖已完成事实。

**同键重发不是恢复操作。**当前无重启续跑、自动重试或恢复接口；中断任务仅可查询。业务失败修正后用新键只提交失败目标，不盲目重提整批。`dryRun=true` 明确拒绝；省略、null 或 false 均为正式提交。

## 4. 公共接入契约

沿用 Controller → Service → Mapper、现有表及数据库事务，无迁移、DAO、Redis 或内部 HTTP。单人和批量共用 `GrantOperations`，调用方必须已有事务；批量使用逐项 `REQUIRES_NEW`。

| 内部接入点 | 调用/实现约束 |
|---|---|
| `AdminPolicyService` | A/B/C 写服务在已有事务中传入可信 actor，复用管理员、一级资格、人员/部门范围、可授权角色检查；不能代替功能鉴权 |
| `AdminPorts.Identity` | A 的适配器从认证上下文取得操作者，禁止信任请求声明的 actor |
| `AdminPorts.Functions` | D 检查当前会话/功能权限；写入必须在等待 M5 锁后读取当前事实。批量线程按持久任务的可信发起人检查，不能只依赖请求 ThreadLocal |
| `AdminPorts.Integrity` / `GrantPorts.Integrity` | D 按 ID 读取完整规范字段验签，不能直接给最小投影签名；变更后签名包含空集合 |
| `AdminPorts.Audit` / `GrantPorts.Audit` | D 保存操作原因、主体、来源、资格证据及变更前后事实，并补齐功能鉴权/会话证据；与业务同事务，失败必须抛出 |
| `AdminPorts.ManagementRoles` | 管理角色等级及任命规则的适配点，目前默认拒绝 |

锁顺序：管理员 → 部门 → 角色 → 委派清单 → 按 ID 排序的相关用户 → 用户授权来源/关系；批量先锁本项，再进入上述流程。A/B 不应先锁用户再调用 M5。当前宽范围锁优先保证正确性，未承诺并发性能；读取使用事务内视图，不设跨请求允许缓存。

默认缺少身份返回 `INVALID_TOKEN`；缺少功能判断、签名或审计时返回相应拒绝错误。适配器不得另开事务、异步写证据或吞掉失败。测试替身只在 `src/test`，不会打入应用 JAR。

## 5. 暂定规则与后续事项

| 暂定规则 | 当前行为及待确认边界 |
|---|---|
| 管理角色等级 | 业务角色正常处理；管理角色资格默认拒绝，待团队确认映射和政策 |
| 委派来源 | 按单个 `configured_by` 追踪递归收缩；不支持同一清单项多来源。NULL 预置项、外部未知关系及组织调整后的重审批不纳入完整验收 |
| 停用角色回收 | 沿用当前可授权资格规则，人工回收也要求角色启用；不自行扩大清理权限 |
| 任命管理根 | 校验目标人员在操作者范围内和新管理根合法，不额外要求目标人员属于新管理根 |

后续仍需 GR04 授权历史查询、OA 事件、供 A 调岗/离职调用的回收入口、事件幂等接入、批量恢复/重试、可选 dryRun，以及超级用户受控初始化/恢复。真实认证、功能鉴权、签名审计及“登录→授权→菜单/API允许→回收→API拒绝”由团队集成验收，本分支不提供临时放行入口。
