# G · 批量OA审计与公共安全

职责：批量、OA、审计；另主讲签名、事务、初始化和恢复。主讲 3 个接口。

核心源码：BatchService、SignatureService、AuditService、InitializationService。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [G01](#G01) | `POST /api/v1/grants/batch` | 同步批量授予或回收一个角色 |
| [G02](#G02) | `POST /api/v1/integrations/oa/roles` | OA审批后执行角色授予或回收 |
| [G03](#G03) | `GET /api/v1/audit/operations` | 分页查询操作与鉴权日志 |

HTTP示例文件：[本组请求](../../../requests/learning/G.http)。变量均为待填写占位，密码和密钥不要提交。

## G必须额外主讲的公共能力（不是HTTP接口）

| 能力 | 源码入口 | 演示重点 |
|---|---|---|
| 基础签名 | [SignatureService.sign](../../../src/main/java/com/whatname/iam/service/SignatureService.java#L28) / [SignatureService.verify](../../../src/main/java/com/whatname/iam/service/SignatureService.java#L49) | 用户签名覆盖角色集合，角色覆盖菜单集合，菜单覆盖API集合；篡改拒绝，不自动重签 |
| 业务日志 | [AuditService.record](../../../src/main/java/com/whatname/iam/service/AuditService.java#L15) | 日志随变更提交；部门快照和服务操作者为空的含义 |
| 全量初始化 | [InitializationService.initialize](../../../src/main/java/com/whatname/iam/service/InitializationService.java#L115) | 空库、111部门、10000员工、来源资源和IAM自举；不是HTTP接口 |
| 本地恢复 | [InitializationService.recover](../../../src/main/java/com/whatname/iam/service/InitializationService.java#L279) | 恢复初始化账号管理角色、范围和密码，不修复篡改数据 |
| CLI入口 | [InitializationRunner.run](../../../src/main/java/com/whatname/iam/common/config/InitializationRunner.java#L30) | IDEA“初始化数据库”配置，密码环境变量，不提交个人配置 |
| 集成身份 | [ServiceKeys.require](../../../src/main/java/com/whatname/iam/common/security/ServiceKeys.java#L20) / [ServiceKeys.requireApplication](../../../src/main/java/com/whatname/iam/common/security/ServiceKeys.java#L25) | HR/OA集成密钥和业务鉴权密钥不可互换 |

自动证据：[SimplifiedBusinessTest.fullSourceImportIsCheckedSeparatelyFromTheSmallBusinessFixture](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L584)、[SimplifiedBusinessTest.tamperingFailsClosedAndAuditCanBeFiltered](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L570)。初始化全量用例需开启coursework.fullImport=true，不应在日常已有数据库上重复执行。

<a id="G01"></a>
## G01 · 同步批量授予或回收一个角色

`POST /api/v1/grants/batch` · 主讲：G · 业务接口

**入口与调用链：** [ManagementController.batch](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L106) → [BatchService.execute](../../../src/main/java/com/whatname/iam/service/BatchService.java#L16) → BatchService → GrantService.change代理 → DomainRepository.lock/query/save/remove。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 权限分配批量栏

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：每一项内部仍要求 IAM_GRANTS_WRITE。

**范围：** 每个目标都独立检查操作者的部门范围。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| userIds | ID array | 必填 | 原始数组最多100个；去重排序后至少1个 |
| roleId | ID | 必填 | 本批次同一个角色 |
| operation | string | 必填 | GRANT或REVOKE |

```http
POST /api/v1/grants/batch
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "userIds": [
    "101",
    "102"
  ],
  "roleId": "6",
  "operation": "GRANT"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "items": [
    {
      "userId": "101",
      "success": true
    },
    {
      "userId": "102",
      "success": false,
      "message": "目标不在管理范围内"
    }
  ],
  "succeeded": "1",
  "total": 2
}
```

### 怎么实现、改哪些表

1. 校验批量输入。
2. 同步循环，每人通过另一个Service代理进入独立事务并重新检查权限/范围。
3. 业务4xx逐项记录并继续；401或5xx直接中断。
4. 正常完成返回每项结果、成功数和总数。

**数据库：** 逐人改 sys_user_role、更新用户签名、写日志；没有批量任务表。

**事务与锁：** BatchService无外层事务；每个GrantService.change独立事务。后续项失败不回滚前面成功项。

**签名与日志：** 每项USER_ROLE_CHANGE；没有独立任务日志或任务ID。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.batchReportsPartialSuccessAndCommitsOnlyAllowedTargets](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L471)

**正向演示：** 同一批包含范围内和范围外用户，前者成功，后者失败。

**反向演示：** 批量超过100应400；中途基础设施失败不能认为此前全部回滚。

常见错误：

- 400 INVALID_ARGUMENT：超过100或空数组
- 200内success=false：单项越界等业务失败
- 401/503：身份或基础设施问题中断；应查询当前结果

**老师可能问：** 为什么不把整个循环放一个事务？

**回答要点：** 这里需要部分成功；整个循环一个事务会改变已确认语义。


<a id="G02"></a>
## G02 · OA审批后执行角色授予或回收

`POST /api/v1/integrations/oa/roles` · 主讲：G · 业务接口

**入口与调用链：** [IntegrationController.oa](../../../src/main/java/com/whatname/iam/controller/IntegrationController.java#L66) → [GrantService.oa](../../../src/main/java/com/whatname/iam/service/GrantService.java#L60) → DomainRepository.lock(Account)/query(UserRole)/save/remove。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** OA服务或IDEA HTTP Client

**权限：** X-Service-Key 必须匹配 iam.integration.oa-key；不接受应用鉴权密钥替代。

**范围：** OA是可信全局集成，不套人类操作者范围；输入决定目标员工和角色。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| userId | ID | 必填 | 目标用户 |
| roleId | ID | 必填 | 角色ID |
| operation | string | 必填 | GRANT或REVOKE |

```http
POST /api/v1/integrations/oa/roles
X-Service-Key: <OA_SERVICE_KEY>
Content-Type: application/json

{
  "userId": "101",
  "roleId": "6",
  "operation": "GRANT"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 验证OA专用密钥。
2. 锁目标用户并验签；授予时检查用户/部门有效和角色启用。
3. 已有关系重复授予不重复插入，不存在关系重复回收不报来源错误。
4. 签名并记录OA_ROLE_CHANGE，不执行审批流程。

**数据库：** 改 sys_user_role；更新用户签名；写日志。

**事务与锁：** 单目标普通事务，角色关系、用户签名、日志原子提交。

**签名与日志：** OA_ROLE_CHANGE；没有人类操作者时userId为空，targetUserId为目标。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.multipleRolesFormAUnionAndReplacingOneAssociationPreservesTheOther](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L197)

**正向演示：** 审批结果重复授予同角色，关系仍只有一条。

**反向演示：** 先手工授予，再OA回收，此角色应直接移除，因为没有多来源保留。

常见错误：

- 401 INVALID_SERVICE：密钥错
- 400 INVALID_ARGUMENT：操作不是GRANT/REVOKE
- 422 BUSINESS_RULE_VIOLATION：不能给无效目标授予

**老师可能问：** OA是否能知道是手工还是审批授予？

**回答要点：** 不能区分，也不需要；本版两者修改同一份关系。


<a id="G03"></a>
## G03 · 分页查询操作与鉴权日志

`GET /api/v1/audit/operations` · 主讲：G · 业务接口

**入口与调用链：** [ManagementController.logs](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L239) → [QueryService.logs](../../../src/main/java/com/whatname/iam/service/QueryService.java#L55) → DomainRepository.page(OperationLog)/query(count)；AccountService.visibleDepartments。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** AuditView.vue

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_AUDIT_VIEW。

**范围：** ALL看全部；DEPT_TREE按日志当时保存的deptId快照与当前可管理部门集合筛选。全局/无部门日志只有ALL可见。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| operation | query string | 可选 | 按operationType精确匹配；省略看所有类型 |
| page | query integer | 默认1 | >=1 |
| pageSize | query integer | 默认20 | 1–100 |

```http
GET /api/v1/audit/operations?page=1&pageSize=20
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "items": [
    {
      "id": "1",
      "userId": "101",
      "targetUserId": "101",
      "deptId": "2",
      "operationType": "AUTH_CHECK",
      "targetType": "APPLICATION",
      "targetId": "3",
      "detail": "GET /demo/items/{id} ALLOW",
      "success": true,
      "createdAt": "2026-10-25T10:00:00"
    }
  ],
  "total": "1",
  "page": 1,
  "pageSize": 20
}
```

### 怎么实现、改哪些表

1. 校验日志查看权限与分页。
2. 构造类型过滤及部门快照过滤。
3. 按id倒序分页，返回items/total/page/pageSize。

**数据库：** 读 sys_operation_log 和范围/授权表；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 查询本身不写日志，避免循环审计。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 筛选LOGIN看到成功及失败记录；筛选AUTH_CHECK看到允许和拒绝。

**反向演示：** 部门管理员不能看到范围外或deptId为空的全局日志。

常见错误：

- 400 INVALID_ARGUMENT：分页非法
- 403 PERMISSION_DENIED：缺日志权限

**老师可能问：** 为什么不能只看当前员工所在部门来过滤历史日志？

**回答要点：** 日志保存当时部门快照，当前调岗不能重写历史操作归属。
