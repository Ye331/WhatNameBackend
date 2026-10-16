# 批量授予与精确回收：第一阶段基础

复用单人授权业务，实现 GR03、GR06。沿用 `sys_grant_batch` 和 `sys_grant_batch_item`，不增加表或迁移，不使用 Redis、消息队列或 DAO 层。

## 接口

### 提交批量任务

`POST /api/v1/grants/user-roles/batch`，必须提供 `Idempotency-Key` 请求头。

```json
{
  "operation": "GRANT",
  "roleId": "5",
  "targets": [
    {"userId": "101", "sourceType": "MANUAL", "sourceRef": "WORK-101"},
    {"userId": "102", "sourceType": "DEPT_POSITION", "sourceRef": "POST-102", "sourceDeptId": "20"}
  ],
  "reason": "工作需要",
  "dryRun": false
}
```

回收使用 `operation=REVOKE`，每个目标只提供 `userId` 和 `grantSourceId`，精确回收该依据；保留其他有效来源和历史。

- 新任务：HTTP 202、`code=ACCEPTED`，data 为 `batchId`、`processingStatus=PENDING`、去重后的 `total`。表示持久接收，不代表授权完成。
- 同一发起人、同一键、相同内容：HTTP 200，返回已有任务第一页结果，不再次调度。已失败项修正后使用新键、仅提交失败目标。
- 同键不同内容：409 `IDEMPOTENCY_CONFLICT`。幂等键区分大小写，为 1—128 个不含空白的可打印 ASCII 字符。
- 目标按用户 ID 排序；完全相同的重复目标合并，同一用户参数不同返回 400。摘要覆盖操作、角色、原因及所有规范化目标，目标顺序和完全重复次数不影响摘要。来源引用和原因不做大小写或空白改写。
- ID 为正数十进制 BIGINT 字符串；来源、原因长度限制沿用单人接口。数值 ID、未知字段、空目标、非法来源和混用授予／回收字段均拒绝。
- `dryRun` 本轮暂不实现。省略、null 或 false 视为正式提交；true 明确返回 400，字符串布尔值也拒绝。
- 提交检查真实身份、`IAM_GRANTS_WRITE`、有效管理员任命及角色可授权资格。目标是否存在、是否在范围内、是否可接收授权及来源是否有效，在每项执行时检查，业务失败保留为 `FAILED`。
- 批量契约没有 `expectedVersion`。在逐项事务持锁后读取当前授权版本；单人四接口的版本保护和响应保持不变。

### 查询任务结果

`GET /api/v1/grants/batches/{batchId}?page=1&pageSize=20`。

需要真实身份、`IAM_GRANTS_VIEW` 和当前有效管理员范围。页码从 1 开始，每页默认 20、最大 500，超界返回 400。

响应 data：`batchId`、`processingStatus`、`succeeded`、`failed`、`pending`、`items`、`page`、`pageSize`、`total`。每项包含 `userId` 和 `status`；成功项提供 `grantSourceId`，失败项提供 `code`。

先按当前可管理用户过滤，再按用户 ID 排序分页。计数和 total 只统计可见目标；processingStatus 表示整体任务状态，可能与可见子集不同。没有任何可见目标时返回 404。不存在的用户没有可验证管理范围，因此其失败记录保存在数据库中，但不通过 GR06 展示；目标后来存在且进入范围后可见。其他管理员也只有在当前范围允许时才能查看相应目标。

同键重放需要当前写权限和结果查看权限；不因持有幂等键绕过当前鉴权。

## 共用业务与事务

- `GrantService` 保留 HTTP 单人业务入口；`GrantOperations` 接收可信操作人并强制加入调用方事务。内部空 expectedVersion 仅用于批量，HTTP 单人入口仍强制要求合法版本。
- `BatchService` 在一个事务中写入任务和全部输入目标，每 500 项拼装一次 SQL。任何一段失败均回滚整个接收事务，不调度。
- 事务提交后，专用有界单线程执行器调度任务。队列最多等待 1000 个任务；调度失败保留 PENDING 并记录不含业务输入的日志。
- `BatchWorker` 每项使用独立 REQUIRES_NEW 事务：先锁任务项，跳过非 PENDING 项，再按既有 M5 锁顺序检查当前操作者与目标，随后检查功能权限、授权事实完整性，执行共用业务。
- 来源、有效角色关系、用户 grant_revision、签名、审计、逐项结果和 APPLIED 状态同事务。单项失败不撤销已完成的其他项。
- 明确业务异常在原事务回滚后独立记录 FAILED；数据库或其他无法可靠确认的异常停止本任务本次执行，未完成项保持 PENDING。成功状态写入失败也必须回滚业务事实。
- 任务汇总状态为 PENDING、PROCESSING、COMPLETED、PARTIAL_SUCCESS、FAILED。查询以逐项事实计算数量，并在所有项完成时推导终态，避免最后汇总未写入却隐藏已完成结果。
- APPLIED 表示该项业务要求已满足，包括同一有效来源重复授予、已经回收来源的重复回收；业务本身的无变化操作不重复递增版本、签名或成功审计。

## 接入及交付边界

单人模块的身份、M5、M6、签名及审计接口继续复用。后台线程不复制请求 ThreadLocal，不接受请求指定操作人；使用持久任务中的发起人逐项检查当前资格。正式 `AdminPorts.Functions` 适配器必须能够对该可信发起人进行当前功能权限检查，不能只依赖 HTTP 请求上下文。

生产默认身份、功能权限、完整性和审计适配器仍明确拒绝。测试身份、非密码学签名和故障注入只在测试源码中，不构成真实认证、签名或审计验收。

按本轮确认范围，**没有启动扫描、重启续跑、自动重试或手动恢复 HTTP 接口**。服务退出、调度失败或基础设施故障留下的未完成项只能查询；同键重发不会恢复它们。不要通过新键盲目重提整个未完成任务，后续恢复阶段须读取实际结果后处理。OA／HR、历史审计查询、管理角色细分政策及其他未确定口径不在本轮新增范围。

当前保留管理员模块的宽范围锁，串行执行单个进程中的批量任务；2001 人测试是功能规模验证，不是性能承诺。

## 本地验证

JDK 21、Maven Wrapper、Docker Desktop Linux 引擎：

```powershell
.\mvnw.cmd test
.\mvnw.cmd verify -Pintegration-tests
```

集成测试使用原建表脚本和固定摘要的 MySQL 镜像，随机端口、临时文件系统，禁用个人配置导入；不连接或修改既有开发库。实际结果见 `BATCH_STAGE1_TEST_REPORT.md`。
