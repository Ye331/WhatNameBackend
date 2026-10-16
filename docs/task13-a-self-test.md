# A 自测入口（Task13）

在任意工作目录运行仓库内 `scripts/test/Invoke-ASelfTest.ps1`。需要 Windows PowerShell 5.1+、Git、JDK 21、仓库 Maven Wrapper 可用；不安装模块，不修改全局 Java/Maven 配置。使用已经批准的 Maven settings 时显式传 `-MavenSettings`。首次下载依赖仍需正常网络权限。

```powershell
$repo = (Resolve-Path '.\WhatNameBackend').Path  # 根据自己的克隆位置调整
$jdk = 'C:\path\to\jdk-21'
& "$repo\scripts\test\Invoke-ASelfTest.ps1" -JavaHome $jdk
# 如需要镜像：在上述命令追加 -MavenSettings 'C:\private\maven-settings.xml'
```

默认仅 `Unit`，不创建 Spring 数据源、不写库。默认证据为仓库同级 `tmp/task13-<随机ID>`，可以显式传 `-OutputDirectory` 指定不存在的仓库外目录。脚本根据自身路径定位仓库，与当前目录无关。

| 套件 | 精确测试类（类名均唯一，实际 Maven 使用完整包名） | 当前用例数与证明边界 |
|---|---|---|
| `Unit`（默认） | PasswordHasherTest、PasswordPolicyTest、JwtTokenServiceTest、SensitiveDataCodecTest、NamePinyinTest | 50（含参数化实例）；密码/历史规则、JWT、编码和拼音，无数据库 |
| `AMySql`（显式） | M1MySqlIntegrationTest、HrBusinessIntegrationTest、DepartmentBusinessIntegrationTest、DepartmentMapperTest | 30+20+20+3=73（Task16更新）；真实 MySQL/HTTP，C/D 及需要时 SMS 为测试替身 |
| `ACM5`（显式） | Task12M5MySqlIntegrationTest | 17；真实 A+C M5/SQL，D 是测试替身；包含生产依赖缺失时拒绝的断言 |

Task15于2026-10-16北京时间19:32:43、19:33:16使用本入口分别验证当前AMySql 71项、ACM5 17项，均0失败/错误/跳过，Maven均为0；新增4项JUnit，Unit50未重跑。当前结果与限制见 [Task15修复报告](task15-account-password-fixes.md)，下文Task13原134项历史验证保持原事实。

Task16新增两个事件事务交错用例，Department当前20项、AMySql catalog为73项；本任务仅精确新增两项与Department整类回归，不代表全AMySql/Unit/ACM5重跑。执行证据与边界见 [Task16事件交错报告](task16-event-interleavings.md)。

## 显式写库套件

仅用于自己已准备好的本地开发 MySQL：已有 23 表、111 条 `DEV-DEPT-R0002` 至 `DEV-DEPT-R0112` 受控基础部门，已按项目流程应用 `db/migrations/20261016_01_department_merge.sql` 和 `20261016_02_external_event_owner.sql`。本入口绝不执行 SQL 导入或迁移。保留 CHECK、外键、禁止物理删除与历史触发器。测试会创建随机前缀隔离部门、账号、授权/事件/审计夹具；收尾按既有机制暂停或离职账号、冻结部门、撤销夹具资格和有效关系，保留账号、来源、事件、签名和审计历史。故障/中断后应核查本次前缀，不能直接清库。

启动前用私有终端环境设置三个变量；密码不要输入到带字面值的命令，也不要放入仓库模板。以下交互读取不会将密码值写到 PowerShell 命令历史：

```powershell
$env:A_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3307/unified_permission?characterEncoding=UTF-8&connectionTimeZone=Asia/Taipei'
$env:A_TEST_DB_USERNAME = 'iam_dev'
$privatePassword = Read-Host '本地测试库密码' -AsSecureString
$credential = [pscredential]::new('local-test', $privatePassword)
$env:A_TEST_DB_PASSWORD = $credential.GetNetworkCredential().Password
try {
    & "$repo\scripts\test\Invoke-ASelfTest.ps1" -Suite AMySql,ACM5 -JavaHome $jdk
} finally {
    Remove-Item Env:A_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    $credential = $null
    $privatePassword = $null
}
```

也可由私有启动脚本读取已知用途的秘密文件并设置环境，不要用正则猜 YAML 密码。数据库地址必须明确指定 `127.0.0.1` 或 `localhost` 和端口/库名；拒绝远程地址、凭据 URL 和部分危险 JDBC 选项。不自动发现个人配置。`LocalTestDatabase` 只在 `src/test`，把这三个环境值传入测试进程内的 Spring 配置并关闭个人配置导入；数据库密码不会出现在 Maven/JVM 的操作系统命令行参数中。禁止把环境或本地配置打印到日志。

现有断言使用固定环回 HTTP 端口：AMySql 18085—18088，ACM5 18089—18090。占用时明确失败，调用方须自行选择空闲时间，不杀进程。JUnit 原有 `TASK05_DB_TEST`、`TASK07_DB_TEST`、`DEPARTMENT_DB_TEST`、`TASK12_DB_TEST` 条件仍在；脚本只为当前所选套件启用条件，默认禁用其余条件。Mapper 测试需要的 DEPARTMENT_DB_* 由脚本从同一私有环境映射。

组合套件请在 PowerShell 中使用数组，如 `-Suite Unit,AMySql,ACM5`，按指定顺序执行，遇到失败停止后续套件。通过 `powershell.exe -File` 从其他 shell 传数组时，建议改用 `-Command` 或每套件单独运行，以免逗号被作为一个字符串传入。

## 锁、证据和失败判定

所有 Maven 调用持有仓库同级 `tmp/maven-build.lock` 的 FileShare.None 独占锁；占用默认立即非零失败，可传 `-LockWaitSeconds 30`（最大60秒）。其他克隆可显式传 `-LockPath`；本任务 OOAD 工作区强制使用共同约定的同一把锁，不能改路径绕过。没有 `clean`。退出时恢复脚本改动的环境、工作目录并释放锁。

每次输出目录只能新建，已有目录即非零拒绝。`summary.json` 记录 HEAD、分支、源码及构建输入 SHA-256、实际选定类、每套件 Maven 退出码/时间/唯一报告后缀、逐类 tests/failures/errors/skipped、唯一 `class#case` 列表和问题。参数化实例按 XML testcase 名分别计数，重复运行不累加。源码含未提交的测试配置及入口脚本，以哈希为准；不能只看 HEAD。

原始 Surefire 报告仍位于被 Git 忽略的 target，文件名带不可预测的本次后缀；仅读取完整类名匹配、本次时间窗口内的报告。每套件证据目录保存脱敏 Maven 日志和裁去机器属性、应用 stdout/stderr 的 JUnit XML，并记录报告哈希。日志在落盘前遮盖传入的完整数据库密码、JWT 和 PEM 私钥块；这不是通用秘密扫描保证，调用方仍不得把秘密写进测试输出。原始 target 报告也应视为本地私有构建产物。

Maven 非零、预期类/报告缺失、陈旧时间戳、零执行、计数变化、重复 testcase、失败、错误或任何 skip 均非零失败；不以 `BUILD SUCCESS` 作为唯一依据。源码新增用例后应审查并同步脚本中明确的预期计数，不能为使脚本通过而降低断言。已有输出/缺配置/锁占用等启动前失败只输出原因，不改已有证据；已启动的运行会留下失败 summary 与已取得报告。

Task12 历史本机命令见 `docs/task12-c-integration.md`，同伴复现应使用本页仓库内入口。此前 C 自有 95 项不在此入口范围，不能与Task13历史134项（Unit50+A67+M5 17）重复累加为新的完整联调成绩。当前catalog共140项（Unit50+A73+M5 17）；这是可执行计数，具体新跑结果见对应任务报告。真实 D、HR 专用 C 来源回收/组织重审、姓名搜索口径、真实员工导入和性能仍未验收。

## Task13历史实际验证（2026-10-16，北京时间，保留原134项证据）

最终入口实际执行：19:13:33 完成 AMySql 67 项，19:14:08 完成 ACM5 17 项，19:15:12 完成默认 Unit 50 项，均为 0 失败/错误/跳过，Maven 退出码均为 0。Unit 最终运行还验证了调用者遗留全部数据库 opt-in=true 时，默认仍只执行五个纯单元类，并恢复环境和工作目录。

首轮数据库执行因参数化时重复传入空 `spring.config.import`，HR 在 BeforeAll 启动失败：实际 XML 49 条（48 条正常用例、1 条生命周期错误），Maven=1，未执行后续 ACM5。已移除重复参数、保留原断言，并保留失败证据；只用最终通过报告计数。另以无效 Maven settings 实测 Maven=1、0 用例仍返回失败。缺配置/已有输出/锁占用/有限锁等待/错误 JDK/远程地址/占用端口/缺失类，以及陈旧、缺失、零用例、skip、失败、错误、重复和不一致报告等边界均验证拒绝。

本次含失败轮新增 277 个账号（164 暂停、113 离职）和 186 个冻结夹具部门；全部新增样本的有效来源、用户角色关系和管理员任命为 0。111 基础部门指纹保持 `f47ad78eb5e9e8d7904fb9b19f43d6e5ab0bed3a9ad7c44af2173804b9751d98`，23 表/18 触发器、约束定义和历史保留均经前后只读比较。以上是隔离夹具验证，真实员工输入仍未导入。

本机冻结证据位于仓库外 `tmp/task13-unit-final`、`tmp/task13-database-02`（通过）、`tmp/task13-database-01`（失败）、`tmp/task13-*-boundaries` 与 `tmp/task13-db-before.json`/`task13-db-after.json`。同伴使用新输出目录重跑入口，不需要这些私有本机文件。
