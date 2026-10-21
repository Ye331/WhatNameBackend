# C2-05：批量恢复与逐项资格复核

**C2-07 当前入口：**本轮重新执行真实 D 2001 人、并发/故障及独立进程恢复；最新数据见 [联合验收](C_STAGE2_TEST_REPORT.md)。本文耗时与下一部分说明保留为 C2-05 历史证据，GR04 后续已实现。

日期：2026-10-19。分支 `feat/c-stage2-integration`，沿用 GR03/GR06、现有任务/逐项表和 C2-01 的后台身份，不增加 HTTP、DTO、表、字段或迁移。`dryRun=true` 继续返回 `INVALID_ARGUMENT`，本次作为可选增强延期。

## 触发和有界调度

`BatchRecoveryService` 在 ApplicationReady 后发现任务，之后默认每 5000ms 扫描一次。配置 `iam.grant-batch.recovery.enabled` 默认 true；`iam.grant-batch.recovery.delay-ms` 默认 5000。关闭发现不会关闭 GR03 提交后的直接调度。测试资源默认关闭后台扫描，生命周期测试和独立子 JVM 显式开启。

每轮按任务 ID 游标读取最多 100 个 PENDING/PROCESSING 任务；到末尾后的下一轮从零开始。一次没有空位也推进游标，后续绕回，避免老任务占满首页。进程内最多保留 100 个正在排队/运行的任务 ID，重复调度不再入队，执行完成或执行器拒绝后释放；没有入队的持久任务留给后续扫描。提交事务只在 afterCommit 调度，Idempotency-Key 重发仍只返回原结果，不是恢复触发器。

Worker 用独立事务 `SELECT ... FOR UPDATE NOWAIT` 锁原任务行，直到本轮完成；其他 Worker 立即退出。这个事务只保护任务状态和调度，不包含逐项业务。进程退出/连接结束由 MySQL 释放锁，不用超时、PROCESSING 字样或内存标志猜测接管。逐项再加行锁和状态检查；APPLIED/FAILED 永不重执行。

任务锁事务会占用一个连接，每项独立事务再占一个；默认单线程执行器与现有 Hikari 最大 10 个连接配合。自定义执行器并发数及连接池需留出每个 Worker 的这两个连接及 HTTP 容量。任务锁持有整批期间，GR06 以逐项已提交结果展示进度；不能把任务行事务误解为整批原子事务。

## 持久次数及失败处理

现有 `attempts` 现在记录**已经提交的执行预留次数**。首次 + 最多 3 次重试，即最多 4 次；APPLIED/FAILED 更新不再额外加一次。每项业务前单独事务锁项、检查 PENDING/次数并递增，提交后才开启业务事务。逐项业务、两次 D 用户签名、D 审计和 APPLIED 同事务。

预留后进程中断也消耗该次预算，这是防止重启突破上限的保守语义。第 4 次预留后中断，恢复确认仍为 PENDING 就记 FAILED，不发起第 5 次业务。终态旧数据的 attempts 保持原值。

业务异常返回后，先在新事务锁项并读取真实状态：

- 已是 APPLIED/FAILED：保留原结果，包括实际提交成功但确认丢失的情况。
- 仍是 PENDING 且原因明确为数据库瞬时故障、未到第 4 次：下一次执行前重新预留并重新鉴权。
- 业务规则拒绝、验签失败、无明确数据库瞬时原因的 503、未知异常和非瞬时 SQL 错误：记 FAILED，不重试。业务拒绝保留原错误码；无业务错误码的技术故障使用现有 AUTH_STATE_UNAVAILABLE。
- 不能读取真实状态或不能写入结果：停止本轮，不宣称失败/成功，保留已提交结果和预留次数，数据库恢复后由扫描再处理。

识别范围以代码为准：Spring TransientDataAccessException、MySQL 1205/1213、SQLSTATE 40001 和连接故障。D 的签名保存、审计持久化现在保留原异常 cause，Worker 仅对带明确数据库原因的 AUTH_STATE_UNAVAILABLE/AUDIT_UNAVAILABLE 开放有限重试；没有把所有 503 当成可重试。HTTP 仍只返回既有错误码/固定消息，日志只记录异常类型。

## 当前资格、事务及锁序

每项/每次重试都从持久任务中建立新的 `CExecutionContext.batch(actor,batch,item)`，调用现有 `GrantOperations`。没有使用原请求 JWT、请求 ThreadLocal、提交时权限快照或跨事务允许缓存。

锁序：任务行（仅调度）→该任务逐项行→管理员→部门→角色→清单→有序操作者/目标用户→D 当前资源/映射→来源/关系。每项等待 M5 锁后验证当前账号状态、部门生命周期、任命、对象范围、可授权角色、功能及资源状态，再读取目标授权集合并验签。此前成功项不随之后撤权回滚。

后台任务代表已持久接收的命令，检查发起人**当前**业务资格，不要求原 HTTP 会话继续存活。它不会绕过停用、撤任命、功能/清单收缩或角色/资源停用。HR/OA 不通过这个人类管理员通道执行。

GR06 保持当前功能/任命/范围检查，先过滤可见目标再计数分页，终态由已提交项推导；范围外对象不新增可见性。批量审计继续使用 C_CHANGE_V1，authorization.origin 关联 PERSISTED_BATCH、batchId、itemId，供 C2-06 查询历史。

## 无进展与运维边界

数据库/执行器暂不可用时任务持久保留，扫描只记录类型并延期；它不是按轮数无限执行授权。业务尝试上限依赖已提交预留，不依赖进程存活。FAILED 必须在原因纠正后用**新幂等键、仅失败目标**重新提交；不会把 FAILED 或已撤销来源恢复为有效。

排查依次查看 GR06、原任务项 processing_status/attempts/result_code 和扫描/执行器异常类型；不要删历史、清零 attempts、把终态改回 PENDING 或补签旧数据。合法提交均至少有一项；历史数据若缺项或被手工改坏，扫描不会替它伪造目标/成功结果，应按保留历史的团队运维流程处理。

## 验证与交接

`BatchRecoveryIT` 使用隔离 MySQL、真实 C、A JWT、D Ed25519 签名/审计；队列执行器仅控制时间，SQL 拦截器仅作明确的故障/中断注入。`BatchRecoveryProcess` 运行真正独立 JVM，ApplicationReady/定时发现及正式执行器均为生产实现；测试凭据经 stdin 传递，不写命令行或文件。`Runtime.halt(23)` 无清理退出后，新 JVM 使用同一数据库恢复。另有持有目标用户行、真实 MySQL 1205 锁超时的场景。

1213 死锁路径由 SQL 插件注入，提交确认丢失由 afterCommit 抛错模拟；没有把它们记作真实数据库死锁或 TCP 断连。1205 是 MySQL 实际行锁超时，硬退出及新 JVM 启动也是真实进程行为。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B test "-Dtest=BatchRecoveryIT" "-DexcludedGroups=batch-scale" "-Dspring.config.import=" "-DtrimStackTrace=true"
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

2001 人真实 D 场景标记 batch-scale，仅上述重点命令省略，完整 verify 会执行；旧 BatchGrantIT 的 2001 人 A/D 替身回归也保留，二者时间分别记录。最终实际结果和证据清单见 [当前交接](C_STAGE2_HANDOFF.md)，没有把测试 JWT 签发当成 AU01 登录或生产 HR/OA 请求认证成功。

本次完整回归的规模观测：

| 路径 | 目标数 | 结果 | 总尝试数 | 实际耗时 |
|---|---:|---|---:|---:|
| 既有 BatchGrantIT，A/D 测试适配 | 2001 | 全部成功，分页通过 | 每项首次执行 | 85,898 ms |
| BatchRecoveryIT，真实 C/A-JWT/D 签名审计/MySQL | 2001 | 全部成功，第 5 页仅 1 项，重复运行未增加次数 | 2001 | 478,084 ms |

真实路径计时从提交开始，包含执行、GR06 查询、终态重复调度和尾项验签，不包含预置用户及初始签名；它是隔离测试环境的实际观测，不是生产 SLA 或真实登录性能验收。

最终完整 verify 于 2026-10-19 16:41:19（Asia/Shanghai）BUILD SUCCESS，耗时 21 分 48 秒；377 通过、122 项本地数据库专项因配置跳过、0 失败/错误。其中 BatchRecoveryIT 29 项全部通过，Testcontainers 集成共 232 项均未跳过。源码集及 SHA-256 与完整运行启动时一致，最终报告冻结在 `target/c2-05-evidence/`。下一部分 C2-06，既有 A/B/D 外部依赖仍以交接为准。
