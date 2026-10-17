# Task12：真实 C M5 与 A 管理入口接入

**2026-10-18 Task20现行状态，优先于下方Task12历史正文**：A对C的4个生产文件和1个测试文件已恢复main原样，C本身不再包含A增加的管理上下文方法或合并部门校验。A只调整自身适配和测试；HTTP18项、C/D团队公共接口和DB23表不扩展。

当前A按对象调用C既有`requireUserScope(actor,target)`和`requireDepartmentScope(actor,dept)`；调用先于A会话用户及组织读取/锁，完整actor+target由C排序取得。列表先取得C当前管理员资格及锁，再用`requireBatchReadContext`和C自身规则投影范围。写权限来自前述C带锁校验，读上下文不能替代它。A管理入口要求自身新事务，参与外层已有事务则503拒绝，防旧RR快照；没有改成REQUIRES_NEW拆分上层业务事务。

A继续负责本入口的组织生命周期检查：验签后拒绝已合并管理根或操作者人员部门、拒绝向已合并源新建/分配人员。C自己的任命和授权入口缺少合并生命周期判断，须由C处理，不能计为A已代为完成。A原部门参数范围外403保持。测试配置隔离通过运行参数解决，不再改C测试文件。

事件表已恢复main原字段；普通重复只重放/拒绝，不接管。APPLIED实际结果优先于提交确认异常；非终态recover在无法证明旧执行者/事务停止时503，当前没有可执行的在线接管或人工离线自动入口。C HR回收/组织重审、D服务认证/功能/签名/审计及跨HR全局锁序仍待真实接入，生产缺失时默认拒绝。

Task20新增覆盖人员部门在管理根外、范围外403、合并人员部门、列表等撤销后复核、既有事务拒绝，以及事件认领到业务锁空隙、收据及业务commit确认丢失。最新自测入口及预期见[自测说明](task13-a-self-test.md)，实际结果以Task20新报告为准。下方Task12的旧179项、调用方法、C修改和在线令牌恢复描述仅为当时历史，不能作为当前设计或测试结论。

## 以下为Task12历史记录

基线为 A `ef671ac22e64e683f366a5756bfa88b016bc32c6`，本地兼容合并唯一 C 提交 `7a449d0139915e991212845924d6e1babd427f59`。C 原始管理员、单人授权和批量实现归原作者；本次 A 贡献为协作适配、锁序调整、合并生命周期补充和验证。合并尚未提交，未推送或创建 PR。

## 调用链与真实边界

`UserController → UserService → M1SecurityGateway.prepareManagement → AManagementPolicy → AdminPolicyService.requireManagementContext → AdminStateLoader/AdminMapper → AdminRules`。

`DepartmentController` 只验证 JWT 密码学声明，将完整 SessionPrincipal 带到 Service；`DepartmentService → DepartmentCollaboration.prepareManagement → 同一真实 C M5`，随后 `AuthService.requireCurrentSession` 检查当前账户、sv、jti 和身份完整性。范围来自数据库任命、等级、parent_id 树及当前状态，客户端不能提供 scope 或可信 actorId。

`CBackedM1SecurityGateway` 和 `CBackedDepartmentCollaboration` 是生产装配；`ManagementDataSecurity` 是 A 侧内部接点，尚不是已冻结的团队 D 接口，表示 D 尚未实现的身份、M6、组织/人员验签、签名审计能力，缺少时逐项拒绝。C 的 `AdminPorts.Integrity` 同样仍须 D 提供完整规范字段验签。新的投影不是签名字节协议，不能直接签名投影。测试完整装配只有 D 是替身，未 mock C Service、规则或 SQL，不等于真实 A/C/D 全链路验收。

此次实际接入 US01—US04、DP01—DP04 的管理资格和范围；AU 登录等沿用 A 身份/会话实现，仍依赖 D。C 自有 AD/GR 接口保留原有身份和功能接点，没有给它们增加免鉴权入口。批量原功能保留，不因合入代码便计入 A 功能验收。

## 锁序和事务约束

每个 A 管理事务先取得 C 写上下文：管理员按 user_id → 部门按 id → 角色按 id → 委派清单 → 去重排序后的全部已有相关用户。US02/US04 一次传入 actor 和 target；US01/US03、DP 传 actor，US03 新建账户在这些锁后插入。后续重读已经持锁的同组事实不会引入新的反向首锁。组织冻结先取得上述上下文，才读取/锁组织。

使用 C loader 的 `FOR UPDATE` 当前读，等待结束后才运行资格/范围检查。没有把 `requireBatchReadContext` 的 RR 快照当作写权限，没有拆事务、使用 REQUIRES_NEW 保存签名审计或缓存 scope。管理读取也使用同一锁定上下文，DP01/02 因而不再标为 readOnly。宽范围锁是当前兼容方案，会限制吞吐，不承诺无死锁或性能达标。

SQL/锁超时、死锁和事务开启失败通过公共处理返回 503 AUTH_STATE_UNAVAILABLE，事务回滚；A 管理入口不在内部自动重试。客户端若重试，须重新进入完整认证/当前 M5/业务校验；不得只重试最后一条 UPDATE。未知提交结果不能据 HTTP 错误盲目重复创建账户，需先核查业务结果。

此相容锁序只覆盖本次管理入口及 C 原管理/授权链。HR 现有事件→组织→用户流程与缺失的 C HR 写链尚未统一；正式 HR 服务认证/协作仍拒绝，不能据本次证明全局锁序完成。

## 合并生命周期最小扩展

C `AdminData.Department` 增加 `mergedIntoDeptId`，保留三参数构造兼容旧测试；Mapper 读取 A 已有字段。MyBatis 明确指定四参数构造。`AdminRules.departmentChain(..., requireUnfrozen=true)` 拒绝已合并部门及其祖先，因此用户可用性、管理员任命根、新任命和新授予都不能以合并空源为依据。

普通可见范围计算仍保留空源历史展示，A 的人员创建、冻结和 HR 归属规则各自继续检查生命周期。未删除或自动转移 sys_admin 任命、未自动扩大管理根、未实现组织重审业务。该投影依赖 A 已有 `20261016_01_department_merge.sql` 迁移；已有个人库不重复执行，临时 C 容器直接使用包含字段的现有空库 schema。

## HR 与 D 的准确缺口

C 的 `GrantOperations.revoke` 是真实人工单来源回收，要求管理员、范围、角色可授权资格和版本；它不是 HR 身份回收，不允许伪造管理员循环调用。仍缺 HR 专用旧岗位来源精确回收、离职全部来源/有效关系/管理员资格失效、复职残余资格检查，以及组织变化后限制/重审管理员范围的公共入口。这些能力还需共同确定排序锁、grant_revision、完整签名与审计证据，并与 A 事件和人员写入同事务。

`HrCollaboration` 默认拒绝未改；`CBackedDepartmentCollaboration` 的 HR 认证、事件信任/恢复和 organizationChanged 明确拒绝。D 仍缺可信服务身份/nonce、完整 Ed25519 规范及验签/重签、M6 当前功能和审计实现；测试指纹不具备这些能力。负责人五项规则及原 HR Service、DTO、迁移和 contracts 快照均未改。

## 验证与复现

仓库外 `tmp/task12-test.ps1` 使用 JDK21、原 Maven settings 和共享独占构建锁。`-Run <唯一名称> -Tests <精确类名>` 用于本机测试，`-Container -Tests AdminIT,GrantIT` 用于 C 独立临时 MySQL；脚本拒绝覆盖冻结报告，不运行 clean。先阅读脚本，命令不得记录本地密码或完整 JWT。

新增 `Task12M5MySqlIntegrationTest` 验证真实 A+C、MySQL/HTTP、任命范围/等级/撤销/暂停离职/祖先冻结/合并根、分页计数、目标变动、等待 C 撤销/重任命提交后复核、当前会话和多阶段故障回滚。目标移动场景用隔离 fixture SQL 改归属，只证明等待后的复核，不是已实现真实 HR 调岗。D 替身只在 src/test。

2026-10-16 最终验证：C 规则/HTTP 74 项和独立容器 AdminIT/GrantIT 21 项；A 原有回归 67 项；最新 Task12 17 项，合计 **179 项不重复用例，0 失败、0 错误、0 跳过**。A 与较早 16 项 Task12 同跑的 83 项报告保留；统计只采用最新 17 项，不把重复运行累加。最新 M5 测试 14:55:29 完成，最终 JAR 14:56:57 打包成功。不将未运行的批量规模/性能测试计入本次验收。A原有67项沿用原C/D替身，仅作为A旧行为回归；最新Task12 17项才是本次真实A+真实C、测试D的接入验证。

C `AdminIT` 显式禁止个人配置导入。A 原测试配置改用 TestConfiguration，M1 手工扫描排除 TestConfiguration，防止 C 测试接点被扫描进 A 测试或默认拒绝证明；未删减旧业务断言。冻结日志、报告、源码哈希、精确 C/A 白名单、数据库指纹和最终计数见仓库外 `子任务12_交接摘要.md` 与 `tmp/task12-*`。

## 集中讲解索引

| 定位 | 待集中讲解和教师追问 |
|---|---|
| UserService；CBackedM1SecurityGateway；AManagementPolicy | 三层调用与依赖注入；为什么 JWT 中的 ID 不能直接当作当前管理资格？ |
| AdminStateLoader；AdminPolicyService.requireManagementContext | 为什么必须先排序完整 actor/target，再取得用户锁？等待行锁后为何普通 RR 快照不足？ |
| AdminRules.departmentChain；AdminData；AdminMapper.xml | 生命周期、冻结与组织外键分别约束什么？为什么不能把精简投影当签名字节？ |
| DepartmentService.freeze；CBackedDepartmentCollaboration | 为什么 authenticateManager 后仍需事务内检查 sv/jti 和范围？签名审计为什么必须与业务同事务？ |
| Task12M5MySqlIntegrationTest；C AdminIT/GrantIT | 真 MySQL、真 C、D 替身各证明什么？如何用失败注入和不可变报告证明原子性？ |
| MERGE_HEAD、ORIG_HEAD、POM；本次 C/A 差异 | no-commit merge 的索引是什么？如何保留 C 原作者和历史，又区分 A 适配贡献？ |

这些知识仍待 A 全部完成后集中通读和复述验证，不能记为用户已掌握。
