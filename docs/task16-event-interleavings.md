# Task16：外部事件两种真实事务交错验证

2026-10-16（北京时间），分支 `feat/backend-a-rbac0`，起止HEAD `7db8f5c3a53d7c56fdb088e9229e120576b8778d`。仅补 Task14 第5节已确认的两个证据缺口，无生产代码、POM、契约、schema、迁移或C/D修改；原18项测试断言、opt-in与退休机制保留。

## 活跃owner与recover争同一事件锁

新增 `activeOwnerRecoveryWaitsForCommitAndReplaysWithoutRotatingToken`。实际 `ExternalEventService` / `ExternalEventMapper` / `TransactionTemplate` 与本机MySQL运行，不替换SQL或事务。OA测试身份由原TestPort授权。

1. 先用既有部门Service建立版本10的隔离部门。owner线程进入真实SERIALIZABLE业务事务，已锁事件行；callback把该部门版本加1、名称设为事件ID，并写一条有事件ID的测试审计、一条 `TASK16_DEPT` 签名标记。`CompletableFuture`只在这些SQL完成后发布owner连接ID和claim，随后用latch暂停提交（最多8秒）。
2. 外部连接仍读到部门版本10、零新审计/签名、事件PROCESSING且无result。另一个独立事务对同一OA/event执行 `FOR UPDATE NOWAIT`，实际错误码 **3572 / ER_LOCK_NOWAIT**，证明记录已被锁。探测连接finally回滚关闭，不改会话全局参数。
3. recover线程通过正常授权进入实际Mapper。仅src/test的MyBatis拦截器在其第一次事件lock查询前记录真实连接ID，随后无条件执行原调用；不替换生产方法、不伪造等待。连接ID与owner不同，按该ID轮询本账号 `information_schema.PROCESSLIST`，观察到事件表的实际 `FOR UPDATE` 正在执行。此后future的150ms有界get超时；事件已提交快照、原claim和外部业务版本仍不变。
4. 放开owner，owner正常提交APPLIED，recover返回保存结果；恢复callback若执行会直接fail。新事务确认部门版本11、名称匹配事件、持久审计1条及签名标记1条，结果JSON语义一致，原claim未轮转。finally释放latch、shutdownNow和有界awaitTermination，防失败路径残留工作线程。

**锁证据强度**：开发账号SELECT `performance_schema.data_lock_waits`被1142拒绝，`information_schema.innodb_trx`需PROCESS权限被1227拒绝；未加权限。采用“同一行NOWAIT失败＋指定真实恢复连接执行锁SQL＋有界未完成＋释放后重放”的组合证据，未取得data_lock_waits等待边图，也没有把一段sleep或AtomicInteger当作证明。20ms sleep只用于最多2秒的PROCESSLIST条件轮询，成功条件是观察到SQL。业务callback不以进程计数器证明一次性。

## 提交成功后测试交付结果丢失

新增 `committedResultLostBeforeDeliveryRetriesSavedResultWithOnePersistentApplication`。测试包装层 `discardCommittedDelivery` 先等待实际execute返回，再抛 `LostTestDelivery`；抛错位于callback之外，因此发生在TransactionTemplate提交后、包装层返回调用者之前。

捕获丢交付异常后，用新的REQUIRES_NEW/READ_COMMITTED事务检查部门版本11、APPLIED/COMPLETED及保存结果、审计1条、签名标记1条。随后同eventId、同业务内容、不同payload requestId/nonce及可信上下文requestId重试；execute与APPLIED recover均只返回保存结果，callback若执行即fail。比较持久事件行（含digest、claim、result、summary、stage）、部门完整行及证据行数，均不变。结果先序列化再解析JSON以统一整数表示，避免Java Long/Integer差异造成假失败；审计evidence也按JSON对象比较。

**故障边界**：这是内部提交后响应交付模拟，未真的切断HTTP/TCP、kill进程、制造数据库未知commit结果、崩溃重启或覆盖全部交错。新增用例走Service，整类已有HTTP测试仍运行；不能把新两项称为HTTP断网测试。固定88字符测试签名标记仅用于持久一次性核验，不是Ed25519/D生产签名，测试审计也不是D实现。

## 实际执行与可复核证据

JDK21 `C:\Users\zhu-y\.jdks\ms-21.0.12.1`；settings `D:\Desktop\OOAD\tmp\maven-settings-task03.xml`；本机3307个人测试库，沿用 `LocalTestDatabase` 私有A_TEST_*环境；密码由本机辅助读取既有专用秘密文件，不在命令参数/日志/报告中输出。所有Maven均持 `D:\Desktop\OOAD\tmp\maven-build.lock` 的FileShare.None独占锁，不clean。

临时精确入口 `tmp/task16-invoke-exact.ps1` 沿用Task13的锁、JDK/环回环境保护、源码稳定哈希、随机Surefire suffix、报告时间窗口/唯一case/失败skip验证和日志脱敏，只选择Department类。`tmp/task16-run-private.ps1` 设置/恢复私有环境。仓库通用入口仅同步catalog计数。

| 不可覆盖证据目录（仓库外tmp） | 实际选择 | cases | failures/errors/skipped | Maven | 北京时间完成 |
|---|---|---:|---|---:|---|
| `task16-targeted-01` | 仅新增两方法 | 2 | 0/0/0 | 0 | 19:45:56 |
| `task16-department-final` | DepartmentBusinessIntegrationTest整类 | 20 | 0/0/0 | 0 | 19:46:45 |

两轮都首轮通过，无生产缺陷失败或弱化断言。新增两项包含在最终20项中，**最终为20个不重复case，不能相加称22个**。Unit50、其他M1/HR/Mapper、ACM5及C自有95项未重跑。当前catalog是Unit50＋AMySql73（M1 30＋HR20＋Department20＋Mapper3）＋ACM5 17＝140，只是可执行计数。Task13原134和Task15的88项历史事实保留。

每轮summary保存全部源码/构建输入哈希、独立suffix和时间、确切case列表、XML哈希；裁去properties/stdout/stderr后的XML及脱敏maven.log保存在对应目录。两条 `TASK16` 日志记录实际成功断言。`tmp/task16-verification-evidence.json` 独立解析报告、校验当前源码与测试时哈希、白名单和秘密扫描；`tmp/task16-output-manifest.json` 给出报告、交接、辅助及证据最终字节SHA-256（排除自身）。这些tmp是本机证据，同伴可用仓库自测入口显式AMySql复现新两项，不依赖本机tmp。

## 数据与资源收尾

`tmp/task16-db-before.json` / `task16-db-after.json`：全部新增11账号均离职且sv≥2，有效来源/用户角色关系/有效任命均0；49个新部门均冻结。保留账号、来源、事件、审计、签名历史。111基础部门完整字段指纹保持 `f47ad78eb5e9e8d7904fb9b19f43d6e5ab0bed3a9ad7c44af2173804b9751d98`，合并字段仍NULL；23表18触发器、触发器定义及约束清单前后一致，无关CHECK/改权限/清库。

历史计数前→后：users1327→1338、sources319→320、events1327→1411、audit1777→1850、signatures1208→1212。`tmp/task16-persistent-facts.json`额外只读核验两轮共4个新测试事件均APPLIED、业务版本11、部门冻结，分别审计1条/签名标记1条；剩余同开发账号连接0。`tmp/task16-runtime-closeout.json`确认18085—18090无监听、共同锁可重新独占并立即释放；Maven与工作线程已结束，不停止他人进程/容器。Docker管道在普通沙箱读取被拒后，正常申请只读升级获批；没有自动审批拒绝后绕行。

## 交付范围与未证明内容

仅四个仓库路径：本测试文件、`scripts/test/Invoke-ASelfTest.ps1`、`docs/task13-a-self-test.md`、本报告；未新增独立src/test辅助。未操作Git索引/提交/推送/分支/合并/PR，不改14报告或15交接。五项负责人业务规则完全保持，不重问。真实D身份/功能/签名审计、C HR专用回收/资格/组织重审、姓名搜索口径、真实员工导入与性能仍未验收，不扩大为全A通过；此前push待人类明确授权状态保持。仅本任务完成后停止。

集中讲解仍待A全部完成。实际答辩定位：本测试的两方法、`RecoveryLockObserver`、`applyInterleavingFact`、`assertInterleavingCommitted`及 `ExternalEventService.run/claim`、`ExternalEventMapper.lock/applied`。可追问：认领和业务为何分事务？一致性读为何能看到旧版本但锁读必须等待？PROCESSLIST与NOWAIT分别证明什么？为什么包装层抛错必须位于execute返回后？为什么保存结果、版本、审计行比AtomicInteger更强？为何无法由这些试验证明真实HTTP断网或全部崩溃恢复？
