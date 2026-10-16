# 单人授予与精确回收：第一阶段

实现 v3 契约中的 US05、GR05、GR01、GR02，构成查询版本、授予、查询来源、精确回收闭环。沿用 `sys_user.grant_revision`、`sys_user_role_source` 和 `sys_user_role`，没有数据库迁移。

## 接口

| 接口 | 功能权限 | 返回重点 |
|---|---|---|
| `GET /api/v1/users/{userId}/roles` | `IAM_USERS_VIEW` | `userId`、`grantRevision`、`roles`（`roleId/active/activeSourceCount`） |
| `GET /api/v1/users/{userId}/roles/{roleId}/sources` | `IAM_GRANTS_VIEW` | `userId`、`roleId`、`grantRevision`、`sources`（含已回收记录） |
| `POST /api/v1/grants/user-roles` | `IAM_GRANTS_WRITE` | `userId`、`roleId`、`grantSourceId`、`roleEffective`、`permissionRevision` |
| `POST /api/v1/grants/user-roles/revoke` | `IAM_GRANTS_WRITE` | `userId`、`roleId`、`revokedSourceId`、`roleEffective`、`remainingActiveSources` |

全部接口还须有效管理员任命和用户管理范围；写入额外检查角色可授权资格。响应统一包装为 `ApiResponse`。来源查询仅返回 ID、类型、业务引用、可选来源部门和有效状态，不返回额外审批材料。

授予请求示例：

```json
{
  "userId": "101",
  "roleId": "5",
  "sourceType": "MANUAL",
  "sourceRef": "WORK-2026-001",
  "reason": "工作需要",
  "expectedVersion": "v1"
}
```

岗位授予使用 `sourceType=DEPT_POSITION` 并提供 `sourceDeptId`，必须等于用户当前部门。普通授予不接受 `OA_APPROVAL`；`MANUAL` 不接受非空 `sourceDeptId`，避免将独立来源误写成岗位来源。

回收请求示例：

```json
{
  "userId": "101",
  "roleId": "5",
  "grantSourceId": "901",
  "reason": "授权依据结束",
  "expectedVersion": "v2"
}
```

ID 是正整数十进制字符串，范围为 Java/MySQL 有符号 BIGINT；版本格式为 `v` 加正整数，支持超过 INT 的修订号。`reason` 非空白且最多 512 字符，`sourceRef` 非空白且最多 128 字符。数值 ID、非字符串字段、未知字段、缺失必要字段均返回 `400 INVALID_ARGUMENT`。回收成功后的最新版本通过两个查询接口取得，保持 v3 的回收响应字段不变。

## 业务与临时口径

- 每项来源独立保存。同一用户角色可有 MANUAL、DEPT_POSITION、已有 OA_APPROVAL 等多个有效来源；新增来源只在必要时创建一条有效关系。回收只更新指定来源，记录回收人和时间，不物理删除来源。
- 来源必须属于请求中的用户和角色，否则返回 `404 GRANT_SOURCE_NOT_FOUND`。只有最后一个有效来源被回收时才删除有效关系，其他来源和角色不受影响。
- 先校验当前资格和版本，再判断重复。相同有效来源与相同来源部门返回原来源 ID，不新增、递增版本、重复签名或重复写成功审计。重复回收已回收来源也不改动事实。响应丢失后若旧版本冲突，先查询当前版本再重试。
- 稳定来源键沿用数据库唯一键 `(user_id,role_id,source_type,source_ref)`，查重使用数据库本身的排序规则，不另设大小写规则。同键来源部门不同返回 `409 IDEMPOTENCY_CONFLICT`；已回收来源不能复活，返回 `409 DUPLICATE_RESOURCE`，新的业务授予使用新的引用。
- 实际授予或回收每次递增用户 `grant_revision` 一次，不改 `session_version`。普通撤权由后续 M6 按当前数据库事实鉴权，不通过强制注销用户实现。版本到达 BIGINT 上限时拒绝变更。
- 管理员可在相同资格检查下对自己授予、回收；查询权限、授予清单与本人角色分别判断。授予要求目标用户及部门链可用；目标暂停或部门冻结不阻止有资格的其他管理员回收。
- 角色启用、管理角色资格、超级角色禁止等沿用上一阶段 `AdminRules`。管理角色政策未接入时拒绝；停用角色的人工回收也沿用当前可授权资格规则，后续若调整需单独确认。原授权人的任命撤销、清单缩减不追溯否定已经成立的授权来源。
- `active`、`roleEffective` 表示角色关系仍有有效来源支撑，不等于某次 API 请求一定获准。账号、部门、角色、资源状态及 API 映射仍须 M6 判断；查询保留有来源支撑的角色关系，包括资源状态变化后仍存在的关系。
- 读取和写入均检查目标用户有效来源与关系集合是否一致。不解释无来源的预置关系，也不自动修复来源缺关系等历史数据；不一致返回 `503 INTEGRITY_FAILURE`，需要可信初始化或人工核查。

批量任务、OA 事件入口、HR 调岗/离职入口、历史审计查询、管理角色政策细化和旧数据迁移不在本轮范围。普通回收不按来源类型额外区分 OA 来源；只要符合当前管理员范围及角色资格，即可精确回收指定来源，沿用 GR02 契约。

## 事务和接入边界

新增 `GrantController → GrantService → GrantMapper`；DTO 及授权事实位于对应 `grant` 包。`AdminPolicyService.requireGrantContext(actorId, userId, roleId, granting, lock)` 在调用方事务内一次读取操作者与目标用户，复用原管理员规则。

写入锁顺序保持为管理员、部门、角色、委派清单、按 ID 排序的操作者与目标用户，再读取用户修订号并锁定其来源和关系。全部资格使用等待锁后的当前读复核；M6 功能检查在 M5 加锁后、事实变更前执行。当前沿用第一阶段宽范围锁，并不宣称具备高并发性能。其他模块接入时必须采用相容锁顺序，不能先锁用户再调用 M5。

只读查询在同一个 REPEATABLE READ 事务中取得范围、来源、关系和版本。写事务将来源、有效关系、版本递增、签名、审计一起提交；任何运行时异常导致回滚，提交失败不返回成功。

| Java 接入点 | 要求 |
|---|---|
| `AdminPorts.Identity` | 从真实认证上下文取得操作人；不接受请求声明的操作人 |
| `AdminPorts.Functions` | 校验当前会话及功能权限；写入时读取等待锁后的当前事实 |
| `AdminPorts.Integrity` | 验证管理员范围、委派清单及相关主体的完整规范字段 |
| `GrantPorts.Integrity.verify` | 验证完整用户身份及修订号、来源和有效关系集合，含空集合 |
| `GrantPorts.Integrity.sign` | 为本次变更后的完整用户身份及授权集合签名，含最后回收后的空集合 |
| `GrantPorts.Audit.record` | 保存本次原因、操作人、来源 ID、管理资格证据、变更前后事实；与业务同事务 |

`GrantState` 是不可变读取快照，不是签名序列化协议。签名适配器须按 ID 读取完整规范用户身份等字段，不能仅给用户 ID/修订号投影签名。`Change.authority` 保存当前管理规则所用事实，M6 的功能鉴权与会话证据仍须正式审计适配器补齐。接入点不得另开事务、异步写入或吞掉失败。

生产默认身份、功能权限、签名和审计适配器均明确拒绝。没有开发免鉴权开关、可用的测试登录凭据或测试身份请求头。测试中的假签名、假身份和故障注入仅位于 `src/test`；本轮只验收这些边界及事务机制，不验收真实 JWT、密码学签名和完整审计链路。

## 本地验证

使用 JDK 21 和 Maven Wrapper：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration-tests
```

第二条需要 Docker Desktop Linux 引擎。`GrantIT` 使用与 Compose 一致的 MySQL 镜像摘要及仓库原建表脚本，创建随机端口、临时文件系统容器；显式禁用个人配置导入，不读取个人数据库凭据、不连接既有开发库。Docker 不可用时集成测试失败，不静默跳过。

实际结果见 [测试报告](GRANTS_STAGE1_TEST_REPORT.md)。
