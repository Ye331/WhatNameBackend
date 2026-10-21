历史归档：以下保留各阶段当时状态，当前交付以 [交付说明](C_STAGE2.md)、[测试报告](C_STAGE2_TEST_REPORT.md) 和 [当前交接](C_STAGE2_HANDOFF.md) 为准。

# C 第二阶段交接

## 2026-10-19 / C2-07：联合验收与交付收口

**当前状态：C 可运行的联合回归与交付整理完成；C2-07 及第二阶段整体仍依赖阻塞。** 20:55:06～21:19:04 +08:00 一次连续串行 verify，BUILD SUCCESS，耗时 23 分 56 秒，437 通过、122 跳过、0 失败/错误；本轮不排除 batch-scale。没有把生产认证或团队演练缺口记为完成。

### 基线与新增交付

- 分支仍为 `feat/c-stage2-integration`，HEAD `805db4cda8e4862179479d5915ed975393a4a3da`；C2-01～06 全部未提交工作保留。核对 C2-06 冻结源码全部一致后，冻结本轮 src/db/pom；测试中途复核无差异。
- fetch 后 origin/main=`7676f30aa9fd080ed45be26629a2760b59f0290a`，仅新增第一阶段报告/B 测试；B 独立候选=`89f3ae3257289b8b2472047573b5a8f0d27a9b0c`。均未合并。没有提交/推送/PR/main 合并、开发库修改、clean 或删除数据卷。
- 新 [交付说明](C_STAGE2.md)、[联合验收报告](C_STAGE2_TEST_REPORT.md)、[HTTP 演示步骤](../cleanup-20261021/requests/c-stage2-acceptance.http)、[本部分细化计划](superpowers/plans/2026-10-19-c2-07-acceptance.md)。README 和六阶段说明增加当前入口，保留各阶段历史记录。
- 本次只整理验收和文档，审查和完整回归未发现需修改的 C 生产行为，没有新增源码或测试变更。交付说明逐项记录真实组件、显式测试适配、同事务边界、锁序、迁移/密钥要求以及待 A/D 联审的内部接点。

### 本轮证据及准确边界

真实 D 2001 人已通过，`C2_05_REAL_D_2001_ELAPSED_MS=419274 ATTEMPTS=2001 SUCCEEDED=2001`；原 A/D 测试适配规模为 `BATCH_2001_ELAPSED_MS=73041`。真实 MySQL 1205 一次、两次尝试后成功；三类独立 JVM 退出/恢复已生成六份本轮日志。批量恢复 29 项组耗时 635.57 秒；最终完整构建另有成功终态，未单凭性能标记推定整轮通过。

证据在 `target/c2-07-evidence/`。`collect-final.ps1` 已只收集本轮开始后生成的两阶段 XML/TXT，检查九组 IT、规模用例、六份新子 JVM 日志和 252 个冻结文件均通过；无开始清单文件丢失。旧子进程原始日志可能被测试复用同名文件，本轮已在恢复组执行前复制至 `prior-child-logs/`；六阶段独立归档保留。最终日志 `final-verify.log`，退出码文件 `final-exit.txt` 为 0；`final-statistics.json`、`test-cases.csv`、`final-reports/`、`child-logs/`、`final-collection.log` 和最终哈希/状态均已保存。target 被忽略，清理前另存。

| 本轮组 | 通过 | 跳过 |
|---|---:|---:|
| Surefire 普通测试 | 149 | 122 |
| AdminIT | 9 | 0 |
| BatchAsyncIT | 1 | 0 |
| BatchGrantIT | 29 | 0 |
| BatchRecoveryIT | 29 | 0 |
| CStage2IntegrationIT | 49 | 0 |
| GrantHistoryIT | 53 | 0 |
| GrantIT | 13 | 0 |
| HrGrantCollaborationIT | 41 | 0 |
| OaDecisionIT | 64 | 0 |
| **合计** | **437** | **122** |

共发现 559 项，0 失败/错误，288 项 Testcontainers 集成全通过，排除数 0。冻结源码集/内容未变，最终 `git diff --check` 和交付文档链接检查通过。未把旧运行、远程 main 的测试或第一阶段覆盖率重复计入本轮。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
```

122 项来自六组未启用本地数据库开关的旧专项，不计为通过。C 默认入口/MVC、A JWT、D 数据签名审计是真实实现；AU01 未接通，AU03 内部判定不等于正式服务认证。HR/组织使用测试请求认证和 A 最终安全适配，OA 仅请求认证为测试提供者。1213/提交确认丢失为故障注入，1205/硬退出及新 JVM 为实际行为。HTTP 示例尚未做生产联合演示。

### 外部阻塞与后续起点

1. A ManagementDataSecurity 最终安全适配、D AU03/HR/OA/组织服务签名认证和防重放仍缺失，生产默认拒绝保持。
2. 管理角色等级规则仍按用户确认默认拒绝。超级用户初始化/恢复及两业务系统/IAM 自管演练需 A/B/C/D 和组织者共同交付，责任见交付说明，未假造完成。
3. 当前 B 仍沿用旧写入口，远程 main 第一阶段报告已记录 S1-SEC-01 未登录写入。B 候选已有部分 Authorization/C/D 适配，不应继续笼统说完全未实现；但候选未集成，首次读取/锁序及关联签名仍需 B/C/D 联审，不能据静态检查宣布修复验收。
4. 存量库迁移未执行；仅核对现有 schema、合并增量、canonical 增量和公开模板。dryRun 可选延期，HR/OA 非终态接管不扩展。
5. 下一步由相应模块交付并审查上述生产依赖，再按 HTTP 演示清单执行真实登录→授予→菜单/AU03→回收→拒绝，以及两业务系统/IAM 自管和受控恢复。B 候选集成后需重跑锁等待/撤权/签名审计回归。当前 C2-07 保持依赖阻塞，不启动 C2-08、不自行合并 main；保留全部现有文件和独立证据。

## 2026-10-19 / C2-06：授权历史 GR04

**当前状态：C2-06 已完成。20:43:17 续验 BUILD SUCCESS，同一冻结源码的分段回归去重后 436 通过、122 跳过、0 失败/错误；另明确排除未改动的 batch-scale 1 项。** 下方 C2-05 的“下一部分 C2-06”及更早的“历史查询未实现”均为保留的历史状态。下一部分为 C2-07 联合验收，外部依赖继续明确保留。

### 基线与交付

- 分支仍为 `feat/c-stage2-integration`，HEAD `805db4cda8e4862179479d5915ed975393a4a3da`。前序 C2-05 最终清单在本次开始时完全一致；本次起点状态/源码文档 SHA-256 存于 `target/c2-06-evidence/starting-*`。C2-01～06 工作区均未提交，没有推送、PR、合并、Maven clean、开发库变更或删除数据卷。
- 新 `GrantHistoryService`、`GrantHistoryEvidence`、`GrantHistoryMapper`/XML；扩展原 Controller 与 DTO。已有 `GET /api/v1/grants/history` 返回来源级 GRANT/REVOKE，不读取当前来源、关系或批量结果来反推历史。既有 GR05 继续表达来源状态。
- 复用 D `AuditQueryService` 原有封签与 actor/request/operation/target 等绑定检查，只增加 MANDATORY 内部验证入口；调用方 C 必须先做当前范围。D 公共 LG01～04、封签格式及日志含义未改变。内部入口需随本分支接受 D 审查，不冒称团队已经签收。
- 支持现有 C_CHANGE_V1、C_HR_GRANT_CHANGE_V1、C_OA_CHANGE_V1：人工/岗位及持久批量逐条变化，HR 多来源撤销逐条展开，OA changed=false 不产生历史；同事件重放及批量失败/重复运行不增加变更。
- 当前查询者使用真实 A JWT/C M5/D IAM_GRANTS_VIEW；在独立只读 REQUIRES_NEW / REPEATABLE_READ 中核对当前目标身份和部门范围，再验证可见历史。总数/响应页使用同一快照；外层旧事务不能借用旧范围，下一请求重新检查。历史作者现已撤任命/离职或范围外、旧角色/资源现已停用均不改写旧事实。
- SQL 按审计 ID 倒序，每块至多 200 候选；同审计来源 ID 升序。先来源展开、当前范围、用户/角色/时间过滤，再完整计数与选页，只保留一个候选块和响应页。精确总数仍是全候选遍历；未承诺生产大历史量 SLA，跨 HTTP 页各用新快照。
- GR04 按既有 OpenAPI **严格返回六字段**：eventId/userId/roleId/grantSourceId/operation/occurredAt。eventId 沿用 D 操作日志十进制主键；原因、来源类型/引用、当时资格及 PERSISTED_BATCH batchId/itemId 用该 ID 查 D LG04，另需 IAM_AUDIT_VIEW 与 D 范围。没有公共 DTO 字段、表、字段或迁移扩展。
- from/to 支持带时区的 RFC3339 闭区间。occurredAt 来自封存来源 createdAt/revokedAt，按业务时区 +08:00 输出；旧日志 created_at 未封签，不用它充当已验签发生时间，也不把 OA approvedAt 当执行时间。
- 可见缺签、篡改、错误元数据、不支持结构、矛盾来源/角色/revision 或来源删除/复活，均 INTEGRITY_FAILURE。可见坏记录即使在请求页之外也拒绝，避免可信 total 假象；范围外坏内容先过滤。只有当前来源、没有历史审计时不补造事件，空结果不证明旧系统从未授权。

接口与限制见 [历史接入](C_STAGE2_HISTORY.md)、[请求示例](../cleanup-20261021/requests/c-stage2-history.http)、[实施步骤](superpowers/plans/2026-10-19-c2-06-history.md)。

### 已取得的专项事实

- 初始 `history-red.log` / `schemas-red.log` 分别记录 5 / 8 项路由 404 红灯；实现后 `history-green.log` 首批 8 项通过。`consistency-red.log` 复现封存角色集合缺失/多余/重复被接受的 3 项红灯；补充历史快照自洽性检查后 `consistency-green.log` 4 项通过。
- `acceptance-fixture-failure.log` 中 **GrantHistoryIT 53 项全部通过**，0 失败/错误/跳过。覆盖当前会话和功能、当前目标范围/移动、历史作者失权、GR04 与 GR05、HR 来源展开、OA 无变化、过滤与分页、超过 200 个候选、外层旧快照、缺签/篡改/元数据及矛盾快照、页外坏证据和实际批量授予/回收的去重及 batchId/itemId 关联。独立保存了这一组的 XML/TXT。
- 实际 HR 调岗产生 3 条、离职产生 7 条来源回收，含已停用管理角色；首尾页共用事件 ID，角色过滤与事件重放后总数正确。实际 OA Controller/A 事件/C/D 的授予→同事件重放→不同事件同审批→回收，GR04 恰为 2 条变化、同来源、不同事件 ID。生产者边界沿用 HR/OA 原有显式测试认证/最终 A 安全适配；GR04 查询本身使用真实 JWT/权限链路。
- 第二次 producer 定向运行的 2 项 HR 及 2 项补强历史断言通过；`oa-history-green.log` 的实际 OA 查询通过。第一次辅助方法的反射泛型误选 Class 重载，第二次 OA 夹具缺 JWT issuer/audience/key-id，均已修正并保留失败日志，未当成生产代码红灯。最终续验已重新运行全部历史、单人授权和 HR/OA 类。
- 早期 `acceptance.log` 无终态，不计作完成；`acceptance-red.log` 因 Docker 不可用报错，亦不计作通过。Docker 正常启动遇到遗留 dockerInference 通信文件错误；自动审批以 blocked by policy 拒绝清理，该删除未执行。用户恢复 Docker 后已实际重跑通过上述专项；20:24 的再次不可用和 20:35 恢复后的最终续验见下文。

### 跨模块回归与证据

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-DexcludedGroups=batch-scale" "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

下午运行在 GrantHistoryIT 容器启动前中断，没有 BUILD SUCCESS 终态；`interrupted-verify.log`、`interrupted-start.txt` 及 `interrupted-reports/` 保留已完成的真实报告。普通测试 149 通过、122 跳过，AdminIT 9、BatchAsyncIT 1、BatchGrantIT 29、BatchRecoveryIT 28、CStage2IntegrationIT 49 均通过（合计 265 通过，0 失败/错误）。其中既有批量 2001 人为 71656 ms，恢复组实际包含三类子 JVM 重启及真实 MySQL 1205。以上只代表已结束套件，不能冒称整轮完成。

20:24 接续检查时没有存活的 Maven/JVM，Docker 引擎不可用；源码与冻结清单一致。20:25、20:28 的正常启动仍未恢复引擎，20:29 日志再次明确报 dockerInference 通信文件不可访问；没有再次尝试被自动审批拒绝的清理，也没有执行 Docker 重置。环境恢复后，只续验未结束的 GrantHistoryIT、GrantIT、HrGrantCollaborationIT、OaDecisionIT，并保留默认普通测试。最终按套件去重合并下午与续验报告，并逐项标明运行来源；不声称一轮未中断的完整 verify。

续验命令：

```powershell
.\mvnw.cmd -B verify -Pintegration-tests "-Dit.test=GrantHistoryIT,GrantIT,HrGrantCollaborationIT,OaDecisionIT" "-DexcludedGroups=batch-scale" "-Dspring.config.import=" "-DtrimStackTrace=true"
```

20:35 再次读取本计划和交接，Docker 29.2.1 引擎已可用；无其他 Maven/JVM，冻结源码集与内容再次核对一致。20:35:38（Asia/Shanghai）按上述命令串行启动四组续验，**20:43:17 BUILD SUCCESS，Maven 耗时 7 分 36 秒**，输出 `final-verify.log`，本轮开始时间保存在 `final-start.txt`。续验普通测试 149 通过/122 跳过，四组集成 171 全部通过，0 失败/错误。

| 测试组 | 去重通过 | 跳过 | 采用的运行 |
|---|---:|---:|---|
| Surefire 普通测试 | 149 | 122 | 20:35 续验，覆盖下午同名报告 |
| AdminIT | 9 | 0 | 下午已完成 |
| BatchAsyncIT | 1 | 0 | 下午已完成 |
| BatchGrantIT | 29 | 0 | 下午已完成，含既有适配路径 2001 人 |
| BatchRecoveryIT | 28 | 0 | 下午已完成，含实际 1205、独立 JVM 重启；排除 batch-scale |
| CStage2IntegrationIT | 49 | 0 | 下午已完成 |
| GrantHistoryIT | 53 | 0 | 20:35 续验，真实 C/A JWT/D/MySQL，220.3 秒 |
| GrantIT | 13 | 0 | 20:35 续验 |
| HrGrantCollaborationIT | 41 | 0 | 20:35 续验，含新增 2 项实际生产者历史查询 |
| OaDecisionIT | 64 | 0 | 20:35 续验，含新增 1 项实际生产者历史查询 |
| **不重复合计** | **436** | **122** | **0 失败、0 错误；共 558 项** |

本次新增 60 项（历史集成 53、历史投影单测 4、HR 查询 2、OA 查询 1）均已通过，已包含在上述去重计数中，不另叠加专项运行。122 项跳过与 C2-05 相同，为六组受环境开关控制的既有测试；本次 Testcontainers 各组均无跳过。另排除未改动的 BatchRecoveryIT `batch-scale` 1 项真实 D 2001 人基准，不计入通过或跳过；该项 C2-05 的 478084 ms 完整证据继续保留。其他批量恢复/独立 JVM 重启与既有 BatchGrantIT 的 2001 人测试已在下午完成，不重跑已通过的长耗时套件。

证据目录 `target/c2-06-evidence/` 已保存专项、失败/中断与最终日志、启动时源码 SHA-256、最终 XML/TXT、统计、六份子 JVM 日志副本、最终 SHA-256 和 Git 状态。`pending-statistics.json` 仅保留下午中断时的 265 通过/122 跳过快照，当前以 `final-statistics.json` 为准。

已执行 `collect-resumed.ps1`：优先采用续验新报告，回退到 `interrupted-reports/` 已完成套件，按 phase/类名去重；检查九个集成组齐全、四个续验组确由新运行完成、无失败/错误、源码集及内容与冻结清单完全一致，开始时已有文件全部仍在。`final-reports/`、`final-statistics.json`、`final-collection.log` 均已生成。`git diff --check` 通过；冻结后仅编辑文档，文档完结后重收最终哈希。旧 `collect-final.ps1` 不用于本次分段验收。日志含预期失败注入、Mockito agent 和已结束容器的 Hikari 告警，不声称零告警。target 被 Git 忽略，清理前保存全部六部分的独立证据。

反射重载问题按 self-improving-agent 保存为 `learning-candidate.json` 本地 candidate，未改持久规则；apb CLI 不可用，未声称完成独立行为评测或应用。

### 接续边界

1. C2-07 从六部分现有组合事实开始，补联合验收、交付报告与演示；没有预先宣称整个第二阶段完成。
2. A AU01/最终数据安全、生产 HR/OA/组织服务认证、B 管理角色等级及资源写入口、超级用户初始化/恢复/自管资源仍是外部依赖，本次没有改变默认拒绝。
3. dryRun 为可选增强，继续明确 INVALID_ARGUMENT；不扩展批量 HTTP 或 A HR/OA 非终态恢复协议。
4. 后续开发保留本分支 C2-01～06 全部未提交代码、文档和六份独立证据；不要以旧交接状态覆盖顶部当前事实。

## 2026-10-19 / C2-05：批量恢复与逐项资格复核

**当前状态：C2-05 已完成；完整回归 BUILD SUCCESS，377 通过、122 跳过、0 失败/错误。** 新增 29 项含真实 D 2001 人和三类独立 JVM 重启。下方 C2-04 的“批量恢复未实现 / 下一部分 C2-05”为历史状态。下一部分 C2-06 GR04；dryRun 作为可选增强延期，继续明确拒绝。

### 基线、改动和接口

- 分支 `feat/c-stage2-integration`，HEAD `805db4cda8e4862179479d5915ed975393a4a3da`。C2-01～05 仍为未提交工作区，没有提交、推送、PR 或合并。开始状态及 src/docs SHA-256 保存在 `target/c2-05-evidence/starting-*`，保留前四部分代码及独立证据，没有 Maven clean、开发数据库变更或删除数据卷。
- `BatchRecoveryService` 新增启动/定时发现和进程内有界调度。每次最多扫描 100 个未完成任务，按 ID 游标推进并绕回；进程内最多 100 个排队/运行 ID，完成/拒绝释放。GR03 afterCommit 交给这个接点；同幂等键仍只返回原状态。
- `BatchWorker` 用原任务行 `FOR UPDATE NOWAIT` 事务保护执行，防止其他 Worker 同时消耗次数；任务事务内的每个次数预留、逐项业务和失败记录各用 REQUIRES_NEW。每次读取最多 100 个 PENDING 项。没有新增恢复 HTTP、表、字段、迁移或公共 DTO。
- 原 `attempts` 改为提交后才执行的**持久次数预留**。首次 + 最多 3 次重试，最多 4 次预留；APPLIED/FAILED 更新不再另增一次。中断消耗已预留预算，第 4 次预留后中断不得发起第 5 次业务。旧终态次数和事实不改写。
- 每次异常后先在新事务锁项并检查真实状态，提交成功但确认丢失时保留 APPLIED。只有带明确瞬时数据库原因且预算未耗尽的项才重试；业务拒绝、无明确数据库原因的 503、未知异常和非瞬时 SQL 错误成为 FAILED。结果确认/写入也不可用则停止本轮，保留持久状态供后续扫描。
- 复用原 `CExecutionContext.batch`、GrantOperations 和真实 C/A-JWT/D 接点；每项及每次重试重新取得 M5 锁、校验当前账号/功能/任命/范围/角色/资源。持久任务不依赖原 HTTP 会话继续存活，也不复用原请求允许快照。此前成功项不随之后撤权回滚。
- 事实、revision、两次 D 用户签名、D 审计与 APPLIED 仍同事务。成功审计沿用 C_CHANGE_V1 和 PERSISTED_BATCH 的 batchId/itemId。GR06 从已提交预留推导 PROCESSING，从已提交项推导终态，保留范围过滤后计数/分页。
- `BatchMapper`/XML 新增锁任务、分页读取 PENDING、游标发现和预留次数四个内部方法。`BatchService` 内部构造依赖从 Worker/Executor 换为 RecoveryService，外部接口不变。
- `BusinessException` 增加保留 cause 的构造，保留原构造。D `DataSignatureService.sign`、`AuditService.persist` 保留底层异常，使明确数据库死锁/连接故障可被识别；没有改变 HTTP 错误码、固定消息或输出底层异常。此内部 D 错误链调整须随本分支接受跨模块审查，不冒称团队已签收。
- 正式配置：`iam.grant-batch.recovery.enabled=true`、`iam.grant-batch.recovery.delay-ms=5000`（默认值，无需改现有配置）。关闭扫描不关闭提交后的直接调度。默认单线程 Worker 运行时使用调度锁及逐项事务两个连接，现有 Hikari 最大 10 个连接不变。测试资源关闭自动发现以稳定夹具，自动执行测试/子 JVM 显式开启；BatchAsyncIT 结束后关闭上下文以释放调度器。

详见 [批量恢复接入、锁序和无进展处理](C_STAGE2_BATCH.md)、[实施步骤](superpowers/plans/2026-10-19-c2-05-batch.md)。

### 已核实的验证事实

- `worker-red.log`：5 项预期红灯，复现瞬时故障/未知异常停在 PROCESSING、提交确认异常后不刷新、已有 4 次尝试仍执行。`d-transient-red.log`：签名/审计吞掉底层数据库原因的 2 项红灯。`progress-red.log`：GR06 仍为 PENDING 的红灯；同次真实 MySQL 1205 用例已通过。
- `focused-green.log`：27 项恢复专项全部通过，0 失败/错误/跳过，耗时 3 分 33 秒。包含真实 C、A JWT、D 数据签名审计和 MySQL；队列执行器只控制调度时间，SQL 插件只注入明确故障。
- 本次最终 verify 中的 `BatchRecoveryIT` 运行 29 项，全部通过，0 失败/错误/跳过，类耗时 727.6 秒；新增的完整规模和实际 MySQL 1205 场景也已运行。最终统计只计入本次完整 verify 的 XML，不叠加此前 27 项重点和红灯运行。
- 三个硬退出点为业务 APPLIED 提交后、次数预留提交后、瞬时故障回滚后。独立 JVM `Runtime.halt(23)` 退出，没有清理内存状态；新 JVM 从相同数据库启动，使用正式启动/定时扫描和后台执行器恢复。分别证明不重执行 APPLIED、第 4 次预留不执行第 5 次、已耗次数跨进程保留。测试凭据仅通过 stdin 传给子进程，没有写命令行/文件。
- 重试耗尽实际 4 次后 FAILED；第 3 次成功实际 attempts=3 且仅一份来源、revision 只增一次、只有一笔成功审计。未知/非瞬时故障不重试；D 内部明确瞬时故障可重试，仍逐项验签、审计原子提交。真实 MySQL 场景持有目标用户锁，以 innodb_lock_wait_timeout=1 产生实际 1205，再释放阻塞者，不把合成异常冒充数据库实际锁超时。
- 7 类当前资格变化在**项间**和**重试前**分别覆盖：任命、功能、清单、目标移动、账号、角色、资源。测试夹具按当前事实重新签名，真实 C/D 据此拒绝后续项；断言已提交成功保留，以及被拒项来源、revision 和成功审计不变，重试路径同时核对有效关系。它们是已签名事实变化测试，不冒称全部经生产 A/B 变更入口。
- 101 个真实持久任务验证每轮/本地排队 100 个上限、游标越页和绕回；执行器拒绝后可发现，重复扫描不重复入队；并发 Worker 验证任务锁防重复应用。
- 本次完整回归已完成两个 2001 人规模场景：既有 A/D 测试适配路径 `BATCH_2001_ELAPSED_MS=85898`；真实 C/A-JWT/D 签名审计路径 `C2_05_REAL_D_2001_ELAPSED_MS=478084 ATTEMPTS=2001 SUCCEEDED=2001`，第 5 页仅 1 项、重复运行次数不变。真实计时含提交/执行/查询/重复检查/尾项验签，不含夹具预置；不是生产 SLA 或 AU01 登录性能验收。

一次误启动第二个 Maven 造成运行中测试类被重编译，已停止第二个进程并串行重跑。早期子 JVM 夹具的默认配置优先级和非法账号 status=0 也已改正，未把这些夹具/执行失误当作生产红灯。相关失败日志保留；`learning-candidate.json` 只记录串行等待的本地 candidate，没有修改持久技能或仓库规则。

完整回归命令、开始时间和输出：

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

2026-10-19 16:19:29（Asia/Shanghai）开始，**16:41:19 BUILD SUCCESS，耗时 21 分 48 秒**。输出保留在 `target/c2-05-evidence/final-verify.log`。

| 测试组 | 通过 | 跳过 | 边界 |
|---|---:|---:|---|
| Surefire 普通测试 | 145 | 122 | 既有单元/边界回归 |
| AdminIT | 9 | 0 | 既有管理员/MySQL |
| BatchAsyncIT | 1 | 0 | 正式执行器、自动发现启用 |
| BatchGrantIT | 29 | 0 | 既有 GR03/GR06 HTTP，A/D 测试适配，含 2001 人 |
| BatchRecoveryIT | 29 | 0 | 新恢复、有限重试、真实 D 2001 人、进程重启及资格变化 |
| CStage2IntegrationIT | 49 | 0 | 真实 C/A-JWT/D、生命周期/组织故障及并发 |
| GrantIT | 13 | 0 | 既有授权来源/版本 |
| HrGrantCollaborationIT | 39 | 0 | C2-03 A HR/事件、C、D/MySQL；生产 HR 认证等仍为测试适配 |
| OaDecisionIT | 63 | 0 | C2-04 真实 Controller/A 事件/C 范围/D 数据签名审计/MySQL；请求认证为测试提供者 |
| **不重复合计** | **377** | **122** | **0 失败、0 错误** |

122 项跳过是 DepartmentMapperTest 3、M1MySqlIntegrationTest 37、Task12M5MySqlIntegrationTest 22、DepartmentBusinessIntegrationTest 23、HrBusinessIntegrationTest 21、DMySqlIntegrationTest 16；对应 `DEPARTMENT_DB_TEST`、`TASK05_DB_TEST`、`TASK12_DB_TEST`、`TASK07_DB_TEST`、`D_DB_TEST` 未启用。新增恢复和本轮 Testcontainers 集成都没有跳过，未把本地专项跳过当成通过。

独立证据保存在 `target/c2-05-evidence/`：最终完整日志、`final-reports/surefire` / `final-reports/failsafe` 的 XML/TXT、`final-statistics.json`、六份 `final-children/` 子 JVM 日志、测试启动时源码 SHA-256、最终 SHA-256 和 Git 状态。收集按 final-start 时间筛选，排除旧 Surefire 中显式 IT 重跑；源码文件集及内容与完整运行启动时完全一致，开始清单中的前序文件全部仍在。`git diff --check` 通过。日志含预期故障注入、Mockito agent 和已结束旧容器的 Hikari 连接告警，不宣称零日志告警。target 被 Git 忽略，清理前须另行保存这五个阶段的独立证据。

### 依赖及下一部分

1. 原 A AU01/最终数据安全适配、生产 HR/OA/组织服务认证、B 管理角色等级及资源写入口、超级用户初始化/恢复和自管资源仍按 C2-01～04/C2-07 交接处理。本次没有解除这些外部依赖或改变默认拒绝。
2. `dryRun=true` 继续返回 INVALID_ARGUMENT，并有既有严格输入测试；它是可选增强，未把正式授权当作预检。没有新增批量外部恢复接口，也未改变 A 的 HR/OA 非终态恢复语义。
3. 后续起点为 C2-06 GR04：复用来源历史、C_CHANGE_V1 的当时事实和授权依据，以及 PERSISTED_BATCH 的具体 batchId/itemId；按当时审计证据及查询者当前范围实现，不根据现时关系反推历史。保留 C2-01～05 未提交代码、计划及五个 target 证据目录。

## 2026-10-19 / C2-04：OA 批准事件

**当前状态：C 的 OA01 业务及真实 MySQL 内部联调已验证；最后完整回归 348 通过、122 跳过、0 失败/错误。生产 D 服务请求认证依赖阻塞。** 下方 C2-03 的“OA 未实现 / 下一部分 C2-04”为历史状态。本次不扩展到批量恢复或 GR04。

### 基线与保留事项

- 仍在 `feat/c-stage2-integration`，HEAD `805db4cda8e4862179479d5915ed975393a4a3da`。C2-01～04 均未提交、未推送、未建 PR、未合并。
- 开始逐项核对 C2-03 最终 SHA-256 清单，全部一致；保存本次开始状态/清单至 `target/c2-04-evidence/starting-*`。保留前三部分全部代码、文档及独立证据，没有 Maven clean 或操作开发数据库/数据卷。
- A 当前事件编排、D 默认拒绝接点、OA01 OpenAPI/API 2.4 与现有服务范围字段均按实际代码核对。没有依据历史 D 草案猜测已实现认证，也没有修改共享 contracts 快照。

### 已实现与接点变更

- 新 `OaController` / `OaDtos.Decision` / `OaDecisionService` 实现既有 OA01。先把原始请求/字节交给 D，再严格解析内容；重复键、尾随 JSON、未知字段、非字符串 ID/时间、无时区、错误批准状态/操作、引用组合及显式 null 拒绝。无新增对外字段。
- D 内部接点新增 `TrustedOaCaller.authenticate` / `verifyTrustedEvent`，以及与 A 既有恢复授权对应的默认拒绝 `authorizeRecovery`。`PermissionIntegrationConfiguration` 默认 INVALID_SERVICE，不根据伪造头或构造上下文放行。D 必须实现真实请求证明与当前 key/资格复核，本分支接点不冒充 D 已交付或团队已签收。
- A `ExternalEventService` 新增可空 D OA 接点的六参数注入构造，保留原五参数构造及 HR 路由。OA_DECISION 调 D OA 接点；缺失提供者拒绝。恢复仍用原 claim 状态机：有授权可重放终态，非终态仍拒绝；没有事件框架、认领字段、在线接管或恢复 HTTP。
- 新 `AdminServiceLocks` 只取得并验证事实锁，不产生管理员资格；新 `OaScopeMapper` 只读/锁既有 sys_service_identity；新 `OaGrantOperations` 参加 A SERIALIZABLE 业务事务。锁序为事件→管理员→部门→角色→清单→目标用户→D 资源/映射→服务身份→来源/关系。锁后重查服务证明、状态、SERVICE_IDENTITY 签名及允许操作/角色/当前部门子树。
- GRANT 目标须正常且未冻结/合并，角色须启用且类型 1/2；超级角色始终拒绝。管理角色的服务资格来自显式 allowed_role_ids，不猜人类管理员等级、不改 canDelegate 默认拒绝，也不等于管理员任命。原申请人已失权/离职不参与现时执行资格。
- GRANT 固定 OA_APPROVAL + approvalId，复用来源唯一键和数据库比较规则；不同事件同审批复用原来源、不增 revision，失效旧来源拒绝复活。服务 granted_by/revoked_by=NULL。
- REVOKE 必须按 grantSourceId/originalApprovalId 唯一定位同用户/角色 OA 来源，不回退到本次 approvalId，不误删 MANUAL/岗位来源。独立来源仍支持角色时保留关系，最后来源才删关系；空集合签名仍可验证。失效来源无变化不增 revision。
- 复用 GrantState / GrantMapper、既有集合一致性检查（仅改为同包可见）、DGrantIntegrity 双签和真实 AuditService。每次集合变化只加一次 grant_revision。OA_DECISION / C_OA_CHANGE_V1 保存批准内容、实际来源、changed、当时服务范围/目标与角色资格、前后事实，D 同事务密封；服务 actor user_id=NULL。
- 同事件重放不写来源/revision/成功审计；返回原事实结果并标记 idempotentReplay=true，独立事务复核当前服务范围，防止重放越界读取。不同事件同审批的无变化结果记录 changed=false 审计，不能当新来源或新授权变更。
- 无新表/字段/迁移。GrantMapper 新增固定 OA 来源写入、有效关系插入和专用 OA 来源回收三个 SQL，不调用人工管理员入口。原 A 本地事件测试夹具显式实现新 D 接点并对失联提交测试传入该接点；旧 Grant 内存夹具对 OA 专用写入明确不支持，算法验证使用 MySQL。

详细规则见 [OA 接入](C_STAGE2_OA.md)、[实施步骤](superpowers/plans/2026-10-19-c2-04-oa.md)，报文示例见 `requests/c-stage2-oa.http`。

### 测试及证据边界

`OaBoundaryTest` 在生产默认装配验证伪造服务头拒绝；`OaDecisionIT` 以 MockMvc 驱动真实 Controller/MVC（无外部网络），使用 A 事件、C 当前配置/范围/来源处理、D 数据签名/审计及固定摘要 MySQL 8.0.46。**仅 D 请求认证为测试提供者**，签名/范围/数据库/事件没有替身。该提供者要求本次签发的对象证明，不接受单独构造的上下文；不把它当真实 Ed25519 请求认证或 nonce 成功证明。

阶段证据：`c2-04-boundary-red.log` 的 401/404 红灯、`business-red` 的 200/503 红灯、`wire-red` 的数字时间与显式 null 引用被接受、`audit-red` 的资格快照缺失，均先复现再修复。一次 numeric-id 测试误被全局 Long→字符串序列化器修正，已改为构造原始数字 JSON，未将夹具错误冒充生产缺陷。

`c2-04-green2.log` 的 52 项 MySQL + 1 项默认拒绝全部通过；随后 `c2-04-final-focused.log` 的 11 项新增重点（资格 seal、管理角色、数据库等价键、六个回收故障点、版本上限和失效目标回收）通过。完整回归不累加这些重跑计数，最终以 `target/c2-04-final-verify.log` 和 final-start 时间之后两阶段报告为准。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

最后完整运行于 **2026-10-19 15:55:03（Asia/Shanghai）BUILD SUCCESS**，耗时 **9 分 36 秒**。

| 测试组 | 通过 | 跳过 | 实际覆盖 |
|---|---:|---:|---|
| Surefire 普通测试 | 145 | 122 | 原 144 项 + 新 OaBoundaryTest 默认生产拒绝 |
| AdminIT | 9 | 0 | 既有 C 管理员/MySQL 回归 |
| BatchAsyncIT | 1 | 0 | 既有异步执行 |
| BatchGrantIT | 29 | 0 | 既有批量，含 2001 人 |
| CStage2IntegrationIT | 49 | 0 | 生产 C/A-JWT/D、组织故障及并发 |
| GrantIT | 13 | 0 | 既有来源/版本回归 |
| HrGrantCollaborationIT | 39 | 0 | C2-03 A HR/事件 + C + D/MySQL 回归 |
| OaDecisionIT | 63 | 0 | 本次 Controller/MVC + A 事件 + C 范围/来源 + D 数据签名/审计 + MySQL；仅请求认证替身 |
| **不重复合计** | **348** | **122** | **0 失败、0 错误** |

122 项跳过仍为 DepartmentMapperTest 3、M1MySqlIntegrationTest 37、Task12M5MySqlIntegrationTest 22、DepartmentBusinessIntegrationTest 23、HrBusinessIntegrationTest 21、DMySqlIntegrationTest 16；`DEPARTMENT_DB_TEST`、`TASK05_DB_TEST`、`TASK12_DB_TEST`、`TASK07_DB_TEST`、`D_DB_TEST` 未启用。旧 A OA 事件夹具已做新接点适配但该本地专项仍跳过，不能按编译通过冒称已运行。新增 OA/现有 HR 实际集成均没有跳过。

63 项 OA 集成覆盖成功、服务/范围/角色/目标拒绝、18 种严格输入、缺签/篡改/不一致/非法配置、同事件重放/内容冲突/非终态拒绝、同审批来源去重及数据库等价比较、原来源定位、独立来源保留/最后来源空集合、原申请人失权、管理角色、原审批不复活、版本上限、当时资格证据 seal。12 个持久化故障场景分别覆盖授予/回收的来源/关系写入、两次用户签名、审计 INSERT、APPLIED 更新之后；均断言实际命中并整体回滚。两个并发场景以 latch + performance_schema.data_lock_waits 证明真实等待，再验证当前撤权和同审批只产生一个来源。

本轮原 2001 人回归 `BATCH_2001_ELAPSED_MS=68548`，仍是原 A/D 替身性能，不能当真实 D 性能或 C2-05 恢复证明。最终日志保留预期故障注入、Mockito agent 以及已结束容器的旧 Hikari 连接告警，不夸称零日志告警。

独立证据：`target/c2-04-evidence/` 保存最终日志、按 final-start 筛选的 Surefire/Failsafe XML/TXT、`final-statistics.json`、测试源码冻结清单、最终 SHA-256、开始 Git 状态与红灯/重点日志。排除旧 Surefire 中显式 OA IT 重跑报告，不累计 52/11 项中间运行。源码在完整运行启动后保持不变。该目录被 Git 忽略，清理 target 前须保存；前三部分独立证据未覆盖。

通用测试经验以 `learning-candidate.json` 保留为本地 candidate：故意构造非法 wire JSON，避免应用序列化器把错误输入修正。未修改持久技能、仓库规则或宣称通用评测完成。

### 明确依赖与下一部分

1. **D 生产 OA 认证仍未交付：**真实五个服务签名头、原始 method/path/query/body SHA-256、Ed25519、client/key/服务类型绑定、±300 秒、独立 nonce 唯一写入/保留 10 分钟，以及业务锁后当前请求证明/key/状态复核。默认 OA01 返回 INVALID_SERVICE，不能勾为全链路完成。
2. **A/D 接点确认：**D 不得把入口服务行锁持有到 C 管理员锁之前；A 保留现有终态/失败/非终态含义。新 D 端口及 A 路由需共同接入验收，未凭本地实现宣称团队签收。
3. **既有依赖保留：**A AU01/最终数据安全适配、HR/组织认证、B 管理角色等级/资源写入、超级用户初始化/恢复及自管资源仍按 C2-01～03/C2-07 处理。
4. **下一部分 C2-05：**从已有批量逐项事务、持久项和后台身份开始恢复/重试。OA 认证依赖单独保留，不能借恢复扩大服务范围或重放已撤销审批。保留 C2-01～04 未提交源码、计划和四个 target 证据目录。

## 2026-10-19 / C2-03：HR 调岗、离职与复职协作

**当前状态：C2-03 实现及真实 MySQL 内部事务验证完成；最后完整回归 284 通过、122 跳过、0 失败/错误。生产 HR 服务认证和 A 最终数据安全适配依赖阻塞。** 本节为当前接续点，下方 C2-02/C2-01 的“HR 未实现 / 下一部分 C2-03”等描述是历史状态。

### 基线与保留事项

- 分支仍为 `feat/c-stage2-integration`，HEAD `805db4cda8e4862179479d5915ed975393a4a3da`；C2-01/02/03 均为未提交工作区，未推送、未建 PR、未合并。
- 开始时保存全部 src/docs 文件 SHA-256 和 Git 状态到 `target/c2-03-evidence/starting-*`，已保留全部上一部分源码、计划及 `target/c2-01-evidence/`、`target/c2-02-evidence/`。与开始清单对比无文件丢失；本次只改 HR 接点、专用回收/重审、必要测试夹具和文档。
- 2026-10-19 10:52 fetch 时默认本机代理连接失败；仅在单次命令清空 http/https 代理后 fetch 成功，没有改全局设置。HEAD、`origin/main`、`origin/feat/c-stage2-integration` 仍同为上述提交。A 当前接点按工作区实际代码核对，不按旧历史文档猜测。

### C2-03 实现与内部接点

- 新 `service/grant/HrGrantCollaboration.java` 实现原 `HrCollaboration`，全程 MANDATORY 加入 A SERIALIZABLE 事务。未调用人工 GrantOperations.revoke，未填假管理员、未绕过默认管理角色资格拒绝。
- `HrCollaboration` 保留四个原方法，新增默认拒绝的 `prepare(actor,eventId,eventType,employeeNo)`、`complete(userId)`。A `HrService` 三个人员事件入口在组织/人员读取之前 prepare，A 最终身份签名和业务审计之后 complete。其他适配必须显式支持阶段协议；旧 A 独立夹具已补两个测试方法，Task12 默认拒绝断言改为在事务内调用。
- 统一锁序：事件→管理员→部门→角色→清单→全用户主键序→D 资源/映射→目标来源/角色关系。C 准备复用 C2-02 锁与原事实验签；A 等待后再次检查服务权限。事件 Mapper 仅投影既有 event_type/target_type/target_ref，C 绑定原 PROCESSING 事件、类型及工号，不增加认领字段或恢复入口。
- HR03 / HR02 部门变化只回收旧部门有效 DEPT_POSITION 来源；同角色其他独立来源继续支持关系，最后来源才删除关系。MANUAL、OA、其他部门来源保留，不授新岗位。
- HR04 回收所有有效来源/关系，包括停用、清单外或管理角色；保留来源历史，revoked_by=NULL、revoked_at 留时间。grant_revision 每次集合变化只加一次，空集合及无匹配不加。
- 生命周期参与者增加独立 HR 单用户变化协议，组织协议仍拒绝混入 HR 状态/授权变更。离职撤任命及本人拥有/提供的递归清单，已失效任命的残余清单也清理；独立委派保留，每个变化管理员 revision 只加一次。调岗、新人员进入造成部门范围扩大的管理员需显式重任命，不更改原等级或扩大管理根。
- 复职检查有效来源/关系、有效任命、本人拥有/提供的清单均无残余；不恢复历史。A 维护部门、状态、session_version、hr_version，C 仅维护 grant_revision。C 签 USER_GRANTS；A 最终重读并签 USER_IDENTITY；C 完结时签管理员资格。提交前再核对完成阶段、最终身份/授权/管理事实与签名。
- C 审计经真实 D 追加/密封，来源操作 `HR_POSITION_REVOKE` / `HR_GRANTS_REVOKE_ALL`，资格操作 `ADMIN_HR_REVIEW`；结构 `C_HR_GRANT_CHANGE_V1` / `C_HR_ADMIN_REVIEW_V1`。服务来源、撤销来源 ID、原因和前后事实关联原 external_event_id，actor user_id=NULL。D 元数据 source_system 仍为 IAM，HR 看 origin。
- 无新表、字段、迁移、对外 HTTP 或 DTO。新 SQL 仅为 C 专用 `GrantMapper.revokeHrSource`。新增空 HR 用户由 C 首次签空集合；已存在用户缺签拒绝，不用补签修复。D 既有 USER_GRANTS 签有效集合，不夸称认证了既有失效来源的全部历史元数据。

详细调用顺序、权限边界和审计见 [HR 接入](C_STAGE2_HR.md)，执行清单见 [C2-03 计划](superpowers/plans/2026-10-19-c2-03-hr.md)。README 及 C2-01/02 接入页均补了当前更正。

### 验证记录

环境仍为 Microsoft JDK 21.0.12.1、Maven Wrapper、Docker 29.2.1、固定摘要 MySQL 8.0.46 临时 Testcontainers；未改开发数据库、迁移或数据卷。

新增 `HrGrantCollaborationIT` 使用真实 A HrService/ExternalEventService、C 参与者/Mapper、D Ed25519 签名/审计和 A JWT。仅在显式构建的内部测试服务中替换尚未交付的 HR 认证和 A 最终数据安全边界；生产 bean 不变，并断言生产信任接点仍拒绝。未声称 AU01 或生产服务认证端到端成功。

已完成的 6 项重点运行：真实双向锁等待、HR02 精确回收、已失效任命的残余清单清理、事件冲突/乱序、来源审计及 seal 验证；`target/c2-03-focused.log` 为 BUILD SUCCESS。完整回归以最后一次 `target/c2-03-final-verify.log` 和按 final-start 时间筛选的报告为准，不累加中间重跑。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

最后完整运行于 **2026-10-19 11:00:20（Asia/Shanghai）BUILD SUCCESS**，耗时 **10 分 30 秒**。

| 测试组 | 通过 | 跳过 | 实际覆盖 |
|---|---:|---:|---|
| Surefire 普通测试 | 144 | 122 | 既有服务、HTTP、规则、签名及组织协议回归 |
| AdminIT | 9 | 0 | C 管理员/MySQL，原 A/D 替身 |
| BatchAsyncIT | 1 | 0 | 既有异步逐项提交/执行 |
| BatchGrantIT | 29 | 0 | 既有批量回归，含 2001 人 |
| CStage2IntegrationIT | 49 | 0 | C2-01/02 的生产 C/A-JWT/D 与组织事务/并发回归 |
| GrantIT | 13 | 0 | 既有授权来源/版本回归 |
| HrGrantCollaborationIT | 39 | 0 | 本次真实 A HR/事件 + C + D/MySQL；仅生产认证和 A 最终安全接点为测试适配 |
| **不重复合计** | **284** | **122** | **0 失败、0 错误** |

122 项跳过仍为 DepartmentMapperTest 3、M1MySqlIntegrationTest 37、Task12M5MySqlIntegrationTest 22、DepartmentBusinessIntegrationTest 23、HrBusinessIntegrationTest 21、DMySqlIntegrationTest 16。对应本地库开关 `DEPARTMENT_DB_TEST`、`TASK05_DB_TEST`、`TASK12_DB_TEST`、`TASK07_DB_TEST`、`D_DB_TEST` 未启用，不算通过或生产端到端验收。

39 项新增覆盖：共享角色多来源/最后来源、HR02/03 精确回收、离职/复职及旧 sv 令牌失效、残余来源/关系/任命/清单拒绝、缺签/篡改/集合不一致拒绝且不补签、无操作版本、revision 上限、新建空集合、事件重放/冲突/乱序、来源审计 seal、事务阶段协议/挂起恢复/最终事实变化。七个持久化故障点均断言实际命中：USER_GRANTS、USER_IDENTITY、ADMIN_DELEGABLE_ROLES、C 来源审计、C 管理员审计、A HR 审计、APPLIED；人员、来源、关系、任命、清单、签名和成功证据全部回滚。两个并发用例通过 latch 及 `performance_schema.data_lock_waits` 确认真实等待，再验证锁后的当前事实。

本次 2001 人原回归记录 `BATCH_2001_ELAPSED_MS=87197`，仍使用原 A/D 替身，不是生产 D 性能或批量恢复证明。最终日志有预期故障注入、Mockito agent 及已结束容器旧 Hikari 连接告警，不声称零告警。恢复本对话时 Docker 已停止；最终日志和报告均已成功落盘，因此未为归档重新启动或重跑数据库。

独立证据位于 `target/c2-03-evidence/`：最终日志、按 final-start 筛选的两阶段 XML/TXT、`final-statistics.json`、源码/文档 SHA-256、开始状态及红灯/重点运行日志。排除早前显式 `-Dtest=HrGrantCollaborationIT` 留在 Surefire 目录的旧报告，未累计 6 项重点重跑。该目录被 Git 忽略，清理 target 前须保存。

红灯 `target/c2-03-behavior-red.log` 证实原 HR 默认接点拒绝调岗/离职。中间运行修正了测试夹具的部门路径、HR 来源必填字段及撤销时间约束、错误码对应的事件状态。一次夹具清理误触永久历史 DELETE 触发器，随后移除全部历史清理，使用唯一工号/手机号、仅签新夹具事实；未禁用触发器。`c2-03-hr-final.log` 是主动终止的旧夹具运行（Surefire fork 退出），不是最终测试结果。编译期断言泛型歧义记录于 `c2-03-compile.log`，已修正。

### 外部依赖与下一部分

1. **D/A HR 认证：**生产 `authenticateHr/verifyTrustedEvent` 仍拒绝。需客户端/key/操作及对象范围绑定、请求签名、时间窗、nonce，以及等待锁后当前服务权限复核。构造 TrustedEventContext 或插入事件行不能替代该边界。
2. **A 最终适配：**`ManagementDataSecurity` 仍默认拒绝；需真实 D 身份验签/最终重签及 HR 业务审计装配。C 本次已给出最终重读/阶段/同事务约束，测试适配不算生产完成。
3. **既有依赖：**管理角色规则用户已要求默认拒绝；B 资源写入锁序/签名、生产组织认证/最终签名及受控超级用户初始化/恢复/自管资源联合演练继续保留 C2-02/C2-07 交接。
4. **下一部分 C2-04：**OA 应使用 A 事件编排、D 可信服务身份和 C 专用事实处理；本 HR 参与者只允许 HR 三类人员事件，不可复用成 OA 绕行。OA、批量恢复和 GR04 尚未实现。不要覆盖本次及 C2-01/02 未提交改动和三份证据目录。

测试准备的可复用经验以 `target/c2-03-evidence/learning-candidate.json` 保存为本地 candidate：设计清理前先看数据库历史保护触发器/外键，优先唯一夹具。未改持久技能或仓库规则；apb CLI 不可用，未冒称完成行为评测或应用。

## 2026-10-19 / C2-02：管理员与组织生命周期规则

**当前状态：C 生命周期与组织重审已实现并通过完整回归。管理角色规则及生产组织事件入口依赖阻塞，超级用户跨模块通道未交付。** 下方 C2-01 是保留的历史交接；其中“下一部分 C2-02 / 尚未实现组织重审”已由本节及 [生命周期接入](C_STAGE2_LIFECYCLE.md) 更新。

### 基线与保留事项

- 仍在 `feat/c-stage2-integration`，HEAD 为 `805db4cda8e4862179479d5915ed975393a4a3da`；C2-01/C2-02 均未提交、未推送。
- 开始 C2-02 时核对 C2-01 冻结 SHA-256 清单，全部一致；确认其最终 BUILD SUCCESS 日志及 216 通过/122 跳过记录。没有丢弃上一部分未跟踪源码、计划或证据。
- 开始时 fetch 遇连接重置；2026-10-19 收尾重试成功，HEAD、`origin/feat/c-stage2-integration`、`origin/main` 均为上述 `805db4c`。新发现的 `origin/test/stage1-acceptance` 只增加第一阶段验收文档/B 测试，未合并到本任务分支。没有更改 Git 代理设置。
- C2-01 的“真实 C/A-JWT/D + MySQL”是已验证事实，真实 AU01 登录、D 服务认证、B 写入口装配并未因此完成。
- 用户明确答复：管理角色暂无规则，保留拒绝并记录依赖。`canDelegate` 不开放猜测性实现。

### C2-02 已实现

- `AdminData`/`AdminMapper.xml` 读取既有合并字段；`AdminRules` 拒绝合并根及合并祖先下的管理、新任命/授权，同时保留上级定位冻结/历史对象的能力。
- 新 `AdminOrganizationLifecycle` 参加 A SERIALIZABLE 事务。准备阶段锁管理员→部门→角色→清单→全用户→D 资源并验签；回调比较变化前后真实范围。
- 根失效、管理员本人归属改变、部门管理员新增子树/部门/范围外人员时撤销任命待显式重任命。仅范围缩小可保留有效任命；不改变原等级、不把根迁移到合并目标。
- 精确递归收缩失效委派，独立来源保留，每个管理员版本只加一次；重签 ADMIN 和 ADMIN_DELEGABLE_ROLES，记录 `ADMIN_ORG_REVIEW` / `C_ORGANIZATION_REVIEW_V1`。不修改用户角色、来源历史或 grant_revision。
- 事务上下文随挂起/恢复和完成清理；缺准备/回调、重复消费、未声明人员移动、回调后再次改变范围均拒绝。提交前校验 A 最终签名，防止 C 先成功、A 后失败。
- A `DepartmentCollaboration` 新增内部准备阶段，默认拒绝；`DepartmentService` sync/merge 和生产 CBacked 适配器已接入。未改变 HTTP/数据库契约，未新增 HR 授权回收/OA/批量恢复。
- 超级用户初始化、恢复、自管资源和 A/B/C/D 验收责任已写入 [生命周期接入](C_STAGE2_LIFECYCLE.md)；这是协作交付要求，不冒充团队签收或已实现入口。

### 验证记录

环境：Microsoft JDK 21.0.12.1、Maven Wrapper、Docker 29.2.1、固定摘要 MySQL 8.0.46 临时 Testcontainers；没有重建开发库或操作数据卷。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

2026-10-19 10:25:16（Asia/Shanghai）结束，**BUILD SUCCESS**，耗时 7 分 7 秒。

| 测试组 | 通过 | 跳过 | 实际覆盖 |
|---|---:|---:|---|
| Surefire 普通测试 | 144 | 122 | 其中 AdminRules 12、AdminPolicy 6、组织规则 6、最终重审事实协议 1；其余为既有回归 |
| AdminIT | 9 | 0 | 原 C/MySQL/A-D 替身；新增真实合并字段映射与任命限制 |
| GrantIT | 13 | 0 | 原授权回归 |
| BatchGrantIT | 29 | 0 | 原批量回归，包括 2001 人 |
| BatchAsyncIT | 1 | 0 | 原异步提交执行 |
| CStage2IntegrationIT | 49 | 0 | 原 32 项真实 C/A-JWT/D 加本次 17 项组织协作、失败、协议及并发用例 |
| **不重复合计** | **245** | **122** | **0 失败、0 错误** |

122 个跳过项与 C2-01 相同：DepartmentMapperTest 3、M1MySqlIntegrationTest 37、Task12M5MySqlIntegrationTest 22、DepartmentBusinessIntegrationTest 23、HrBusinessIntegrationTest 21、DMySqlIntegrationTest 16。`DEPARTMENT_DB_TEST`、`TASK05_DB_TEST`、`TASK12_DB_TEST`、`TASK07_DB_TEST`、`D_DB_TEST` 未开启，不将其算作已验收。

新增组织场景包括：范围扩大、人员移动、合并源限制、保留原根/等级、显式重任命、精确委派收缩、历史来源保留、管理员第二签 INSERT 后失败、审计 INSERT 后失败、A 最终组织审计失败、漏最终签名、缺/重复准备与回调、未声明人员移动、重审后再次改范围、事务挂起/恢复及清理、缺签不补签。实际 A 事件/部门服务的合并和同步验证 APPLIED/失败结果原子性；生产服务认证和 A 最终签名边界由明确的测试适配补齐。并发用例观测 `performance_schema.data_lock_waits` 后释放组织事务，等待授权因已撤任命而拒绝。

日志：`target/c2-02-final-verify.log`；本次独立副本与本轮实际执行的 XML/TXT 报告、统计、文件 SHA-256 清单在 `target/c2-02-evidence/`。该目录被 Git 忽略，清理 target 前应保存。统计只取最终完整运行，不重复累计中间显式 IT 运行留在 Surefire 的旧报告。

红灯证据保留在 `c2-02-rules-red.log`（合并根仍允许）、`c2-02-review-red2.log`（缺少重审）、`c2-02-org-red.log`（缺少实际组织参与者）、`c2-02-incoming-red.log`（范围外人员迁入）、`c2-02-final-facts-red.log`（重审后改范围）。中间一次组织审计证据用了 D 不支持的 Set，已改为有序 List；收紧故障断言后验证实际命中签名/审计 INSERT 故障。接收子部门增加管理对象的纯规则断言已修正并通过。

2001 人记录 `BATCH_2001_ELAPSED_MS=81018`，仍是原 A/D 替身测试，不是生产 D 性能或恢复证明。日志中的故障注入 SQL/调度警告、Mockito 动态 agent 及已结束容器的旧 Hikari 连接告警属已知测试现象。10 月 19 日恢复工作时 Docker 因运行时 socket 报错未启动；尝试重命名失败，随后重启恢复并确认引擎可用后才运行最终集成，没有重置 Docker 或删除数据卷。

### 外部依赖与下一部分

1. **B/C 管理角色资格：**团队需交付可判定规则和正反例；用户已要求当前默认拒绝。普通超级角色保护保持。
2. **A/D 生产组织入口：**`verifyTrustedEvent`、可信 HR 认证及 A 的 `ManagementDataSecurity` 最终签名仍拒绝。内部 A/C/D 测试只替换这两个尚未交付边界，没有伪造生产服务身份。D 真正接通后需验证真实请求→事件→组织→C重审→签名审计→APPLIED。
3. **B 真实写入：**共享 M5 资格已可复用，B 写入口尚需装配 C/D、先角色后映射的锁序及签名同步；不能把 Policy 测试当作完整 B 集成。
4. **超级用户协作：**A 受控身份/编排、B 自管资源和角色、C 资格/范围边界、D 认证/完整性/证据，联合初始化与紧急恢复演练属于 C2-07 依赖；没有普通绕过入口。
5. **C2-03 起点：**人工回收仍拒绝停用/清单外角色。可信 HR 专用清理必须独立于这些条件并保留来源历史；人员变更需在 C 的前置锁和验签之后发生。当前组织接点拒绝混入账号状态或角色事实修改，不能直接拿它充当 HR 离职回收。OA、批量恢复和授权历史查询继续按总计划分段实施。

文件与完整规则见 [C2-02 细化计划](superpowers/plans/2026-10-18-c2-02-lifecycle.md) 和 [生命周期接入](C_STAGE2_LIFECYCLE.md)。保留 `target/c2-01-evidence/`；本次证据另归档 C2-02，不能覆盖 C2-01。

## 2026-10-18 / C2-01：A/D 真实接入与事务基线

**状态：C 生产适配与事务基线完成；完整登录端到端依赖阻塞。** 下一部分为 C2-02，不等同 C 第二阶段整体完成。

### 1. 分支、基线与工作区

- 分支：`feat/c-stage2-integration`。
- 源码提交基线：`805db4cda8e4862179479d5915ed975393a4a3da`。本次全部变更尚未提交；该哈希不包含本次实现。未推送、未创建 PR、未合并 main。
- 开始时工作区只有用户已有的未跟踪 `docs/superpowers/` 计划目录。已保留并更新总计划，另加 C2-01 细化步骤；没有覆盖其他已有源码改动。
- 首次绕过失效本机代理的 fetch 成功，确认远程 main 与目标分支均为上述基线；晚间继续工作时 fetch 返回 connection reset，未能再次确认远程最新状态，本地分支/HEAD 未变。未修改 Git 全局代理配置。
- 原先不存在本交接文件；已阅读 C_STAGE1、D_STAGE1、task12-c-integration 顶部 Task20 更正及实际代码。

### 2. 已完成行为与文件

| 文件 | 当前责任 |
|---|---|
| `common/config/AdminIntegrationConfiguration.java`、`GrantIntegrationConfiguration.java` | 装配生产 A/D 接点；依赖缺失仍拒绝，管理角色资格仍默认拒绝 |
| `service/admin/integration/CRequestSecurity.java` | A JWT 密码学认证；锁后 D 当前会话与功能检查；HTTP 身份入口拒绝借入外层普通/只读事务；保存变更前安全证据 |
| `service/admin/integration/CExecutionContext.java` | 事务范围的 HTTP/持久批量证据；随挂起/恢复，提交/回滚后清除 |
| `service/admin/integration/DAdminIntegrity.java`、`mapper/admin/CResourceLockMapper.java` | 规范数据验签、首次一致性读前的资源锁、全部受影响管理员双签名 |
| `service/grant/integration/DGrantIntegrity.java` | USER_IDENTITY / USER_GRANTS 同事务验签、重签，覆盖空集合 |
| `service/admin/integration/CChangeEvidence.java`、`DAdminAudit.java`、`service/grant/integration/DGrantAudit.java` | 白名单前后事实、资格、来源、原因、身份与功能证据，交给 D 同事务追加及密封 |
| `service/admin/AdminService.java` | 在首次功能读取前完成生产完整性适配及资源锁 |
| `service/grant/BatchService.java`、`BatchWorker.java` | 提交拒绝外层事务；锁定持久项后建立后台 actor 依据；沿用已有逐项事务，不新增恢复 |
| `src/test/java/com/whatname/iam/service/grant/CStage2IntegrationIT.java` | 32 项真实生产装配、MySQL、JWT、签名、审计、失败注入与并发用例 |
| `docs/C_STAGE2_INTEGRATION.md`、两个计划文件、`docs/C_STAGE1.md` 顶部更正 | 接入、锁序、证据及当前/历史边界 |

上表主源码路径均相对 `src/main/java/com/whatname/iam/`。既有 AdminPorts/GrantPorts、A/D 公共方法签名、对外 ID/版本/错误码保持不变。新增 C 内部执行上下文和只读加锁 Mapper；无新 HTTP、表、字段或迁移。配置沿用 A JWT 与 D Ed25519，不能提交密钥或本地配置。

真实验证链路：生产 C Controller/Service/Mapper → 真实 A JwtTokenService → 生产 C 接点 → 真实 D PermissionService/DataSignatureService/AuditService → 隔离 MySQL。授予后真实 D 菜单及内部 API 判定允许；最后来源回收后双签名仍可验证，菜单空、内部 API 拒绝。来源历史保留。

会话覆盖：缺失、畸形、过期、sv 不匹配及注销；任命/功能/对象范围分别判断。事务覆盖：缺签不补签、篡改拒绝、第二次签名失败、审计实际 INSERT 后失败、管理员任命审计失败、自回收历史证据、递归清单所有管理员重签。并发覆盖：观测 MySQL `performance_schema.data_lock_waits` 确认真实等待，另一事务提交撤任命、移除功能映射、停用菜单、变更 sv 或注销后，等待方全部拒绝且无成功事实。

证据采用 `C_CHANGE_V1`，目标类型 USER；来源、变更前后版本/集合、管理员资格、reason 和授权前的功能依据随 D seal 留存。会话只保存版本/有效期/jti 摘要，不保存完整 JWT 或原始 jti。批量记录 batchId/itemId。详见 [接入说明](C_STAGE2_INTEGRATION.md)。

### 3. 测试命令、结果和证据

环境：Microsoft JDK 21.0.12.1、Maven Wrapper、MySQL 8.0.46（固定镜像摘要见测试源码）、Docker 29.2.1。只使用测试自动创建/销毁的临时容器，不重建已有开发库、不操作已有数据卷。C2-01 容器执行现有 `01_schema.sql` + `02_add_canonical_path_and_index.sql`。

在仓库根目录、JAVA_HOME 指向上述 JDK 的 PowerShell 中执行：

```powershell
# 最终完整回归；禁用个人配置导入，避免覆盖临时容器连接
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"

# 单独复测新增生产集成（显式指定 IT，避免普通 test 不选中）
.\mvnw.cmd -B "-Dtest=CStage2IntegrationIT" test

git diff --check
git status --short --branch
```

最终完整回归于 2026-10-18 19:18:45（Asia/Shanghai）结束，BUILD SUCCESS，耗时 3 分 41 秒。汇总：

| 测试组 | 通过 | 跳过 | 实际依赖 |
|---|---:|---:|---|
| Surefire 普通测试 | 133 | 122 | 含现有服务/HTTP/配置边界/签名测试；以下本地数据库专项未启用 |
| AdminIT | 8 | 0 | 真实 C/MySQL，原 A/D 接点替身 |
| GrantIT | 13 | 0 | 真实 C/MySQL，原 A/D 接点替身 |
| BatchGrantIT | 29 | 0 | 真实 C/MySQL，原 A/D 接点替身 |
| BatchAsyncIT | 1 | 0 | 真实 C 异步提交执行，原接点替身 |
| CStage2IntegrationIT | 32 | 0 | 真实生产 C/A-JWT/D；只在测试 MyBatis 拦截器注入签名/审计故障 |
| **不重复合计** | **216** | **122** | **0 失败、0 错误** |

122 个跳过项：DepartmentMapperTest 3、M1MySqlIntegrationTest 37、Task12M5MySqlIntegrationTest 22、DepartmentBusinessIntegrationTest 23、HrBusinessIntegrationTest 21、DMySqlIntegrationTest 16。对应 `DEPARTMENT_DB_TEST`、`TASK05_DB_TEST`、`TASK12_DB_TEST`、`TASK07_DB_TEST`、`D_DB_TEST` 未启用；不把这些跳过算作 A/D 专项或真实登录通过。

最终日志为 `target/c2-01-final-verify.log`，标准报告在 `target/surefire-reports/` 与 `target/failsafe-reports/`。另保留本次独立副本 `target/c2-01-evidence/`；该目录是被忽略的本地证据，后续清理 target 前应先保存。本节计数按最后完整回归的两阶段汇总，不累计中间重跑，也不把早前显式 `-Dtest=CStage2IntegrationIT` 留在 Surefire 目录的旧报告重复计数。

红灯证据：`target/c2-01-red.log` 的授权 HTTP 401、`red2` 的无请求批量 FAILED 与缺失资源证据、`red3` 的外层事务错误类型、`red4-retry` 的外层只读事务触发 SQL 拒绝，均先复现后修复。继续工作时第一次 red4 因 Docker 未运行而失败，启动 Docker 后重跑才得到业务断言红灯，未把环境失败算测试覆盖。

现有 BatchGrantIT 的 2001 人回归记录 `BATCH_2001_ELAPSED_MS=58258`；它仍使用 A/D 替身，不能作为真实 D 签名下的性能或恢复证明。日志保留了预期 SQL 故障/拒绝调度、Mockito 动态 agent 及已结束容器对应旧连接池的告警；不把这些误写成生产异常，也不声称日志零告警。

### 4. 未完成事项与明确依赖

- **A/D 真实登录：**`M1SecurityConfiguration` 中的 `ManagementDataSecurity` 仍默认拒绝；A 的 AuthService 登录/注销写入等尚未接上真实签名审计。本测试凭据由真实 A JWT 签发器对测试夹具签发，未调用真实 AU01 成功登录。总计划 C2-01 因此保留“依赖阻塞”。
- **D 服务认证：**`TrustedApiCaller` 仍默认 INVALID_SERVICE；本次直接调用 D 内部 check 验证授权结果，没有用测试头冒充生产 AU03 服务认证。可信服务请求签名、绑定、nonce 由 D 完成。
- **B 写入及全局锁序：**C 已增加角色→资源/映射锁；B 部分入口仍先删映射再更新角色，也未接通相关签名/审计。B 需先统一管理资格/锁序，再按 D 规范同步签全部对象。未声称现有 B 写接口已可作为完整并发生产链路。
- **C2-02：**管理角色资格仍默认拒绝；合并部门生命周期、组织重审和超级用户受控初始化/恢复规则尚未实现。
- **C2-03/04：**A 事件→组织→用户与 C 管理员优先顺序不能直接拼接。已在接入说明提出“事件锁后、组织/用户之前取得统一业务锁”的协作约束，仍需 A/C/D 落实专用可信服务入口。没有 HR 回收、OA 接入或伪造管理员调用。
- **C2-05/06：**未新增批量恢复/重试、HR/OA 来源处理或 GR04；已有后台逐项真实身份接入可作为 C2-05 起点，历史查询可使用 C_CHANGE_V1，但不能宣称尚未实现的来源已覆盖。

### 5. 下一部分起点及保留事项

下一对话先读本交接、C_STAGE2_INTEGRATION 和阶段总计划，再执行 C2-02。先与 B 确认可判定的管理角色/等级规则，核对 A 的合并状态字段和组织重审接点；保留当前会话复核、资源锁、双签名及证据结构。

不要删除未跟踪的新适配器、测试和计划文件；它们属于本次完整实现，尚未纳入 Git 提交。不要用旧 C_STAGE1 的“未接通”描述覆盖当前生产配置，不要把旧 Task12 历史合并规则当当前实现。提交审查时应同时纳入实现、32 项测试和本次文档；现有 `config/application-local.yml`、密钥目录及 target 证据不提交。
