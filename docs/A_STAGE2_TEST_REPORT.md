# A 第二阶段测试报告

交付分支：`feat/a-stage2-integration`。

验证日期：2026-10-18（北京时间）。基于 main `805db4c` 验证[A 第二阶段说明](A_STAGE2.md)中的既有业务、演示短信、身份实现及 A 对 D 的适配，以下均为本次实际执行结果。第一阶段交付记录单独见[第一阶段测试报告](A_STAGE1_TEST_REPORT.md)，不与本次数字累加。

## 1. 已执行结果

| 范围 | 通过 | 证明边界 |
|---|---:|---|
| A Unit | 50 | 密码、历史策略、JWT、加密编码及拼音，无数据库 |
| A AMySql | 84 | M1 37、HR 21、部门业务 23、部门 Mapper 3；真实 MySQL / HTTP，C/D 及需要时 SMS 使用测试替身 |
| A ACM5 | 22 | 真实 A + C 既有 M5 / SQL、MySQL / HTTP；D 使用测试替身 |
| A 对 D 适配单测 | 10 | 真实 D 序列化、Ed25519 和审计封印，存储与事务生命周期为内存夹具 |
| A 演示短信及身份单测 | 15 | 短信通道 8、请求身份 7；真实本机文件与 JWT，未装配 C 生产接点 |
| A 账号 MySQL / HTTP 专项 | 25 | 真实 A、D 和既有 C 范围处理；含独立文件通道设密 / 重置；C 完整性接点为测试装配 |
| A HR MySQL 专项 | 23 | 真实 A、D、MySQL 及事务；HR 服务认证、C 回收 / 组织重审为测试替身 |
| C 及公共普通回归 | 74 | C 管理员、单人授权、Controller、默认拒绝等 73 项及公共事务响应 1 项；未运行 C Docker 专项与批量验收 |
| D 普通回归子集 | 9 | 签名 5、默认拒绝 2、权限夹具 2 |
| D MySQL / HTTP 专项 | 16 | 独立临时库、真实 D / MySQL / HTTP；可信服务身份和 C 完整性接点为测试替身 |
| 唯一 JUnit 合计 | **328（A 229 + 其他回归 99）** | 实际执行用例全部通过，失败 / 错误为 0；重复执行的 A Unit 50 不累加 |
| A 数据工具测试 | 26 | 部门及员工事件准备工具的独立 unittest，不计入 JUnit |
| 应用打包 | 通过 | Maven 退出码 0；打包不重复计为测试 |

普通回归显式使用 `*Test,!AStage2DMySqlIntegrationTest,!AStage2HrDMySqlIntegrationTest` 选择器，158 项通过，另有 122 项数据库用例因专项开关关闭而跳过；这不是默认全量命令。随后已分别执行 A 既有数据库回归 106 项、A 新增数据库专项 48 项，以及 D 数据库专项 16 项，覆盖这 122 个先前跳过的用例。A Unit 50 项在普通回归与 A 回归中重复执行，合计按类名和方法名去重。以上结果不与 [C 独立验收](C_STAGE1_TEST_REPORT.md)或 [D 独立验收](D_STAGE1_TEST_REPORT.md)数字相加。

## 2. 关键覆盖与限制

| 场景 | 测试位置与覆盖 |
|---|---|
| 密码 / JWT / 编码 | PasswordHasherTest、PasswordPolicyTest、JwtTokenServiceTest、SensitiveDataCodecTest、NamePinyinTest：密码历史、异常编码、过期 / 签名拒绝及输入边界 |
| 账号与姓名搜索 | M1MySqlIntegrationTest：初始化 / 重置 / 注销、唯一约束、临时账号、状态 / 来源 / 范围交集、跨 200 人批次及准确分页、损坏候选拒绝和一致视图 |
| 组织与事件 | DepartmentBusinessIntegrationTest、DepartmentMapperTest：六条组织路由、冻结 / 合并 / 版本、幂等及乱序、同事件并发、提交确认异常、非终态恢复拒绝、签名审计故障回滚 |
| HR 人员 | HrBusinessIntegrationTest：人员新建更新、暂停恢复、复职、调岗、离职、未来生效时间拒绝、版本 / 重复事件及故障回滚；C HR 写入仍为替身 |
| 真实 C 管理范围 | Task12M5MySqlIntegrationTest：资格 / 等级 / 范围、冻结和合并生命周期、等待撤销 / 重任命后复核、范围外拒绝、旧外层事务拒绝及回滚 |
| 演示短信与身份实现 | LocalDemoSmsSenderTest、ARequestIdentityTest：显式演示配置、通道可替换、目录及投递失败拒绝、用途隔离；当前请求 JWT 验签及缺失 / 伪造 / 过期拒绝，不代表 C 的生产装配完成 |
| 真实 A 对 D 适配 | AStage2DMySqlIntegrationTest：账号及空授权集合签名、密码 / 状态 / 会话变更、注销、冻结、篡改 / 缺签名 / 旧快照拒绝和审计故障回滚；真实文件通道的 HTTP 设密 / 重置及“文件已投递但数据库回滚后验证码不可用” |
| HR 对真实 D 的签名审计 | AStage2HrDMySqlIntegrationTest：HR01—HR05 业务事务、人员与组织签名、C 最终授权集合核验、迁移用户逐人审计、证据脱敏及故障回滚；伪造上下文、事件号不符、跨事务凭据及协作者额外篡改拒绝。HR 服务认证、C 回收 / 重审为测试替身；新部门签名验证后主动回滚，不代表新部署初始化已验收 |
| C/D 普通回归 | 管理规则、Controller、共享校验、签名与默认拒绝；不替代各模块专项验收 |
| D MySQL / HTTP 回归 | DMySqlIntegrationTest：菜单/API、授权来源撤销、旧会话、签名篡改与历史审计、故障回滚；测试中服务身份和 C 完整性接点为替身，不证明两项生产接点已实现 |

既有 A 数据库套件写入带随机前缀的隔离夹具，收尾暂停或离职账号、冻结测试部门、回收有效来源和资格，保留业务历史。两个新增 A 专项账号均已退役，无有效来源、关系、任命或未用验证码；原组织行及原签名字节已恢复，基础部门及表结构前后一致。D 专项在单独新建的临时库中运行，不向既有 A 开发库写入其夹具。中断后应按本轮前缀核查，不能直接清库或物理删除历史。

这些结果验证 A 既有编排、演示文件短信和真实 D 适配。C 生产接点及 HR 回收 / 组织重审、D 服务认证 / nonce、完整“登录→授权→菜单 / API 允许→回收→拒绝”链路和规模性能仍未验收；未宣称达到 20ms / 50ms。演示采用独立模拟短信通道，不以商业短信接入作为验收前提。

## 3. 复现入口

需要 Windows PowerShell 5.1+、Git、JDK 21 和仓库 Maven Wrapper。在 `pom.xml` 所在目录执行，将 `JAVA_HOME` 指向本机 JDK 21；需要已批准镜像时给脚本追加 `-MavenSettings` 私有文件参数。

```powershell
# 默认仅运行五个纯单元测试类，不连接数据库
& .\scripts\test\Invoke-ASelfTest.ps1 -JavaHome $env:JAVA_HOME

```

[A 自测脚本](../scripts/test/Invoke-ASelfTest.ps1)支持 `Unit`、`AMySql`、`ACM5`，组合使用 PowerShell 数组。数据库套件须显式启用，并先按[项目启动说明](../README.md)和[数据库说明](../db/README.md)准备本地开发库、现有 23 表、111 条受控基础部门及部门合并迁移；脚本不执行导入或迁移。

```powershell
$env:A_TEST_DB_URL = 'jdbc:mysql://127.0.0.1:3307/unified_permission?characterEncoding=UTF-8&connectionTimeZone=Asia/Taipei'
$env:A_TEST_DB_USERNAME = 'iam_dev'
$privatePassword = Read-Host '本地测试库密码' -AsSecureString
$credential = [pscredential]::new('local-test', $privatePassword)
$env:A_TEST_DB_PASSWORD = $credential.GetNetworkCredential().Password
try {
    & .\scripts\test\Invoke-ASelfTest.ps1 -Suite Unit,AMySql,ACM5 -JavaHome $env:JAVA_HOME
    if ($LASTEXITCODE -ne 0) { throw 'A 回归失败，请检查本次报告' }
    $buildLock = [IO.File]::Open((Join-Path (Split-Path (Get-Location).Path -Parent) 'tmp/maven-build.lock'),
        [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    try {
        $env:A_STAGE2_DB_TEST = 'true'
        & .\mvnw.cmd -B -ntp '-DskipTests=false' '-Dmaven.test.skip=false' '-DfailIfNoTests=true' '-Dtest=DBackedManagementDataSecurityTest,LocalDemoSmsSenderTest,ARequestIdentityTest,AStage2DMySqlIntegrationTest,AStage2HrDMySqlIntegrationTest' test
        if ($LASTEXITCODE -ne 0) { throw 'A 适配专项失败，请检查本次报告' }
        & .\mvnw.cmd -B -ntp '-DskipTests' package
        if ($LASTEXITCODE -ne 0) { throw '打包失败' }
    } finally {
        Remove-Item Env:A_STAGE2_DB_TEST -ErrorAction SilentlyContinue
        $buildLock.Dispose()
    }
} finally {
    Remove-Item Env:A_TEST_DB_PASSWORD -ErrorAction SilentlyContinue
    $credential = $null
    $privatePassword = $null
}
```

数据库地址须为显式环回地址，密码只经进程环境传递，不打印或写入仓库。既有回归使用 HTTP 18085—18090，端口占用时失败，不自动终止进程；新增适配专项由系统分配环回端口。

脚本使用仓库同级 `tmp/maven-build.lock` 独占构建锁，所有 Maven 构建须串行共用，不运行 clean。结果保存在新建的仓库外目录，可用 `-OutputDirectory` 指定；检查脚本退出码和 `summary.json`，不能只看 BUILD SUCCESS。报告与日志不上传仓库。

上例依次执行 A 原有 156 项、演示短信 / 身份 / D 适配 73 项及打包，共 229 个 A 用例。普通回归使用前述选择器，在所有专项数据库开关关闭时于同一构建锁内运行 `.\mvnw.cmd -B -ntp -DskipTests=false -Dmaven.test.skip=false -DfailIfNoTests=true "-Dtest=*Test,!AStage2DMySqlIntegrationTest,!AStage2HrDMySqlIntegrationTest" test`；与 A 专项重复的用例按类名和方法名去重。打包不增加通过数量，当前分支数量以实际执行的 XML 为准。

D 专项在按现有 `db/01_schema.sql` 和 `db/02_add_canonical_path_and_index.sql` 初始化的独立空测试库中运行。将上述 `A_TEST_DB_URL` 指向该库，保留安全注入的账号密码，临时设置 `D_DB_TEST=true` 后运行 `-Dtest=DMySqlIntegrationTest`；该类会留下测试账号和日志，不能在既有业务数据所在库直接执行。本次独立执行 16 项，0 失败 / 错误 / 跳过。该验证仅复用 D 的现有测试，不涉及修改 D 代码或分支。

数据工具本轮另行执行 `python -m unittest discover -s scripts/dev-data -p 'test_*.py'`，26 项通过；该数字不计入 328 项 JUnit。

## 4. 数据准备与第二阶段状态

[员工事件准备工具](../scripts/dev-data/prepare-employee-events.py)历史验证了 10000 条输入，状态为 `PREPARED_NOT_IMPORTED`，实际导入 **0**。工具只准备 HR02 UPSERT 输入，不发送 HTTP、不生成凭据、不授予角色；`--employment-type`、`--event-version`、`--occurred-at`、`--batch` 均须显式提供，开发参数不能代表真实 HR 分类或版本。输入文件由调用者受控提供，输出只允许仓库同级的私有工具目录；原表和明文输出不得提交。

本轮已完成上述 A 侧适配、自测和打包。C 的回收 / 组织重审、C/D 管理接点、D 服务认证及防重放仍需对应模块配合；受控初始化、人工异常处理及完整链路由团队共同验收。HR / OA 可用测试请求模拟，不需要外部人事或审批平台。
