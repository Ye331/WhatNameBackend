# Task15：资料写入与密码历史故障分类修复

2026-10-16，北京时间。固定HEAD `abd473dd8cbca4d24b15f389fccee8a12098982f`，分支 `feat/backend-a-rbac0`；本报告对应未提交工作树，精确原始字节SHA见仓库外 `tmp/task15-source-whitelist.txt`、`tmp/task15-verification-evidence.json`。仅处理R14-01/02，不改契约、表结构、迁移、POM、C/D或负责人五条规则。

## 缺陷与实际复现

问题来自 [Task14审查](task14-a-independent-review.md) 的R14-01/02。原详细设计§3.2要求临时人员绑定手机号后可设密登录，§3.5/9.1要求用已知身份执行密码策略；§9.2/10.2区分完整性故障与普通业务拒绝。本任务没有将姓名限制解释为负责人新批准的字符集规则。

修复前选取三个真实MySQL/HTTP用例，报告冻结在 `tmp/task15-red-02`：M1两项、HR一项，**3 tests / 3 failures / 0 errors / 0 skipped，Maven=1**。

| 发现 | 实际HTTP失败断言 | 原因及修复位置 |
|---|---|---|
| R14-01，US03 | `realName="··"`的唯一手机号临时账号，预期422，旧实现200/OK | `UserService.createTemporary`原来仅strip/encrypt；现第85行在会话/范围/部门检查后、随机密码与账号/签名/审计写入前调用既有`NamePinyin.variants` |
| R14-01，HR02 | 已有HR账号，坏姓名+SUSPEND+跨部门，预期422，旧实现200 | `HrService.applyEmployee`现第60行对完整姓名校验，位于状态、sv、C撤权/归属及profile写入之前；新建与已有用户共用同一检查 |
| R14-02，AU08 | 当前密码和最新历史有效且相等；较旧一条非空ASCII无效编码；有效短信证明及合规新密码，预期503，旧实现422/BUSINESS_RULE_VIOLATION | `AuthService.changePassword`原先丢弃validate的具体结果；现第153—160行保留结果并分类 |

以上复现证明资料写入接受坏姓名及历史错误分类。姓名随后阻断设密的控制流仍由AU07/AU08的必经`NamePinyin.variants`及Task14实际源码分析支持；红轮未额外执行“已接受坏姓名账号再设密”流程，不把该子流程虚报为动态复现。

## 最小行为变化

US03/HR02直接复用现有完整姓名转换方法，没有新增正则、汉字库、姓名/用户名算法或改变转换结果。只有分隔符而无可转换内容、无法转换的汉字（包括现有库不支持的补充平面汉字）、超过现有256个完整拼音组合上限，资料写入返回既有422。正常中文、拉丁、多音姓名保留原行为；HR新建原有`HrLoginName`登录名限制仍生效。这与US01姓名keyword搜索范围无关。没有扫描、改名或修补既有坏资料，也没有在设密时跳过姓名规则。

`PasswordPolicy.validate`结果映射：

| 结果 | 对外错误 |
|---|---|
| VALID | 继续原设密流程 |
| IDENTITY_CONTEXT_REQUIRED / HISTORY_REQUIRED / INVALID_HISTORY | INTEGRITY_FAILURE，503 |
| HISTORY_VERIFICATION_FAILED | AUTH_STATE_UNAVAILABLE，503 |
| 真实密码策略不合规，包括弱密码、身份片段、历史复用 | BUSINESS_RULE_VIOLATION，422 |

没有新增错误枚举。所有异常仍在原事务内抛出；不删除或修补损坏历史，不核销故障时的证明后继续设密。原密码规则、scrypt参数、JWT、签名审计、事务传播及管理/HR锁序均未改变。

## 回归及原子性证据

新增4个JUnit方法（M1 +3、HR +1），并加强原弱密码HTTP用例以同时覆盖真实历史复用422：

- `unsupportedTemporaryNamesRejectBeforeAnyAccountOrEvidenceWriteOverHttp`：`··`、`𠀀`、九个`重`分别422；无新账号、操作者审计和签名行数不变。即使旧实现意外创建，也在finally登记夹具后退休。
- `supportedTemporaryNamesCanInitializeAndResetWithDeliveredProofOverHttp`：`张三`、`Test Person`、`单明`均通过真实HTTP创建、发送短信、首次设密和重置；各设密后由同一装配AuthService复核登录成功。历史两条、证明均已核销，初始化标记及sv符合原规则。SMS是测试发送器，不是外部短信联调。
- `olderCorruptHistoryReturns503AndPreservesAllPasswordFactsOverHttp`：只对新建隔离账号插入合法约束内的较旧无效编码，当前/最新有效相等；同一有效证明重试两次均503/INTEGRITY_FAILURE。逐行比较完整账号、密码历史、短信行（含attempts/consumed_at）、测试签名、审计均不变，原密码仍可登录。
- `unsupportedProfileNamesRejectCreationAndSuspendMoveWithoutChangingBusinessFactsOverHttp`：三类姓名均覆盖HR新建及已有用户SUSPEND/跨部门/换手机/就业类型更新；账号、凭据、初始化、sv/hr_version/grant_revision、归属、全部来源、角色关系、管理员任命、测试签名/审计完整保留。事件另行持久化REJECTED及BUSINESS_RULE_VIOLATION，重放仍422且事实不变；拒绝事件落库是既有设计。
- 原有密码签名/审计回滚、HR各故障阶段事务回滚、会话/冻结/合并/版本及生产依赖缺失拒绝测试全部保留。

最终使用仓库 `scripts/test/Invoke-ASelfTest.ps1`，私有辅助 `tmp/task15-run-private.ps1` 从已有秘密文件设置A_TEST_*环境，finally恢复，不输出密码、不放入命令参数。JDK21、已有settings，统一FileShare.None锁；不clean。冻结输出：`tmp/task15-database-final`。

| 套件/类 | classes | tests | failures / errors / skipped | Maven | 北京时间完成 |
|---|---:|---:|---|---:|---|
| AMySql：M1 30 + HR 20 + Department 18 + Mapper 3 | 4 | 71 | 0 / 0 / 0 | 0 | 19:32:43 |
| ACM5：Task12M5MySqlIntegrationTest | 1 | 17 | 0 / 0 / 0 | 0 | 19:33:16 |

**最终5类、88个不重复JUnit testcase**，入口检查本次随机后缀、时间窗口、报告缺失/skip/重复/计数及源码稳定。独立XML重解析、哈希和case清单见`tmp/task15-verification-evidence.json`。组件未改，Unit未重跑；catalog当前138（50+71+17）不等于Task15新跑138。Task13原134项历史和C自有95项不累加到本次成绩。

失败与中间轮均保留：`task15-red-01`在默认沙箱下因环回Socket连接被拒，2个BeforeAll生命周期错误、0业务用例执行、Maven=1；获正常环境权限后`task15-red-02`才取得上述3个确定业务失败。首次只读Docker快照及一次TCP列表读取也被沙箱拒绝，未造成业务写入，随后正常升级批准。`task15-database-01`先通过同样88项；复核发现新测试应按操作者而非target_id核对创建审计，补强审计及签名不变断言后运行最终88项。重复轮不累计。没有自动审批拒绝后的绕行。

## 数据收尾、交付与边界

`tmp/task15-db-before.json` / `task15-db-after.json`比较覆盖全部红轮及两轮完整回归：389新增账号（182暂停、207离职）全部退休，281新增夹具部门全部冻结；新增账号有效来源、角色关系、有效管理员任命均0。账号/事件/来源/签名/审计历史保留。计数：账号938→1327、来源218→319、事件1006→1327、审计1297→1777、签名839→1208。

111基础部门完整字段指纹前后均为`f47ad78eb5e9e8d7904fb9b19f43d6e5ab0bed3a9ad7c44af2173804b9751d98`；23表/18触发器、全部触发器定义和约束清单一致，基础部门合并去向仍全NULL。未关触发器/CHECK、清库或修改真实账号。19:33:33只读收尾证明18085—18090无监听，统一锁可重新独占并释放，无停止他人进程。

仅8个仓库文件交付：三个A Service、两份M1/HR测试、catalog脚本、Task13当前计数及本报告。精确白名单/原始字节SHA由`tmp/task15-source-whitelist.txt`提供；仓库外交接`D:\Desktop\OOAD\子任务15_交接摘要.md`，完整最终输出哈希由`tmp/task15-output-manifest.json`记录（不自哈希）。没有Git索引或历史操作、推送、PR、他人消息或其他任务。

运行故障HISTORY_VERIFICATION_FAILED→AUTH_STATE_UNAVAILABLE映射已实现并静态核对；未通过真实装配可靠注入scrypt库运行故障，不声称该分支已经HTTP执行。缺失上下文/历史映射也未新增专项HTTP注入。AMySql仍使用C/D/SMS测试替身；ACM5是真A+真C M5，D仍测试替身。真实D、C HR精确回收/资格/组织重审、姓名搜索口径、真实员工导入和性能仍未完成。本任务未更改或重问五项负责人规则。JavaEE验证/事务及软件工程缺陷追踪的集中讲解问题见交接，学习状态仍待A完成后讲解/复述。
