# C 第二阶段测试报告

**C 已实现范围回归通过；第二阶段整体依赖阻塞。** 分支 `feat/c-stage2-integration`。测试基线为 `805db4c` 加 C2-01～07 实现，完整运行时冻结源码/数据库/构建文件 252 个，内容与文件集均未变。

## 完整回归

2026-10-19 20:55:06～21:19:04（+08:00），一次连续执行，**BUILD SUCCESS，退出码 0，Maven 耗时 23 分 56 秒**。

环境：Windows、Microsoft JDK 21.0.12.1、Spring Boot 3.5.16、Maven Wrapper、Docker 29.2.1、隔离 Testcontainers MySQL 8.0.46。镜像固定摘要 `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`。只使用测试容器，未操作开发库。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

| 测试组 | 通过 | 跳过 |
|---|---:|---:|
| Surefire 普通测试 | 149 | 122 |
| AdminIT / BatchAsyncIT | 9 / 1 | 0 |
| BatchGrantIT / BatchRecoveryIT | 29 / 29 | 0 |
| CStage2IntegrationIT | 49 | 0 |
| GrantHistoryIT / GrantIT | 53 / 13 | 0 |
| HrGrantCollaborationIT / OaDecisionIT | 41 / 64 | 0 |
| **合计** | **437** | **122** |

共发现 559 项，失败/错误均为 0；九组 Failsafe 集成共 288 项全通过。未排除 batch-scale；只收集本轮开始后报告，按阶段/类去重，不叠加此前专项。

122 个跳过来自显式本地数据库开关未启用：DepartmentMapperTest 3、M1MySqlIntegrationTest 37、Task12M5MySqlIntegrationTest 22、DepartmentBusinessIntegrationTest 23、HrBusinessIntegrationTest 21、DMySqlIntegrationTest 16。对应开关为 DEPARTMENT_DB_TEST、TASK05_DB_TEST、TASK12_DB_TEST、TASK07_DB_TEST、D_DB_TEST；跳过不计通过。远程 B 专项及第一阶段覆盖率不计入本轮。

## 规模、并发与故障

| 验证 | 实际结果 |
|---|---|
| 真实 C/A-JWT/D 签名审计/MySQL，2001 人 | 2001 成功，0 失败/待处理，尝试 2001，419,274 ms |
| 原 A/D 测试适配路径，2001 人 | 全部成功，73,041 ms；单独记录，不作为真实 D 性能 |
| 规模一致性 | 每人 revision=2、2001 条授予审计、第五页仅 1 项；重复执行不增次数，尾项双签验签通过 |
| 实际 MySQL 1205 | 行锁超时 1 次，释放阻塞后第 2 次成功；版本和成功审计只一份 |
| 独立进程恢复 | 三种 Runtime.halt(23)：APPLIED 后、预留后、瞬时回滚后；新 JVM 同库正式扫描恢复，六份日志 |
| 并发与撤权 | latch 与 data_lock_waits 确认真实等待；会话/任命/功能/范围变化后重新检查，同批 Worker 不重复应用 |
| 有限重试和部分失败 | 初次加最多三次；七类资格变化在项间/重试前拒绝后续项，已成功项保留 |
| 原子回滚 | 签名、审计及 APPLIED 实际写入后注入失败，业务与成功证据共同回滚 |
| 历史 | 实际人工/岗位/批量/HR/OA 生产者记录；当前范围、跨页快照、篡改/缺签/矛盾及页外坏证据拒绝 |

真实规模计时含提交、执行、查询、重复检查和尾项验签，不含夹具预置/初始签名，不承诺生产 SLA，也不等于逐条调用 2001 份 LG04。1213 和提交确认丢失为受控注入；1205、硬退出和新 JVM 为实际行为。预期 SQL/调度告警、Mockito agent 和旧容器 Hikari 告警不计测试失败。

## 真实性及验收缺口

- C 最小链使用 MockMvc HTTP 授予、真实 A JWT/D 菜单和内部 API 判定、Service 精确回收及随后拒绝；未经过 AU01 或 AU03 正式服务认证。
- HR/组织使用真实 A 事件/C/D 数据链，服务认证及 A 最终安全为显式测试适配；OA 仅请求认证为测试提供者。
- 原 AdminIT、GrantIT、BatchGrantIT、BatchAsyncIT 使用 C/MySQL 与 A/D 测试适配；不计生产全链路。
- 未通过的外部问题：当前 B 旧基线的 S1-SEC-01，来自远程第一阶段实测报告；本轮静态复核，未在开发库重放写入。
- 依赖阻塞：A 最终安全/登录、D 真实服务认证、B 写入/等级规则、受控超级初始化恢复及两业务系统/IAM 演练。责任见 [交付说明](C_STAGE2.md)。
- 未执行：外部网络端到端、真实 nonce/时间窗、实际死锁/TCP 断连、存量库迁移及生产组织/历史 SLA。dryRun 可选未实现，当前 INVALID_ARGUMENT；HR/OA 非终态接管不扩展。

## 证据与发布整理

本机 `target/c2-07-evidence/` 保存 final-verify.log、final-exit.txt、final-statistics.json、final-reports、test-cases.csv、六份 child-logs 和源码 SHA-256。收集检查九组齐全、规模用例执行、源码未变及原文件保留；target 被忽略，清理前单独留存。

发布整理仅精简文档和将关键注释改为中文。2026-10-19 21:29:04 补跑普通测试及编译 BUILD SUCCESS：149 通过、122 跳过、0 失败/错误；不与完整回归重复累计。8 个修改类的 javap 编译方法指令前后一致，业务行为未变。补验日志及对照存于 `target/c-stage2-publish/`。原始过程见 [历史交接](C_STAGE2_HANDOFF_HISTORY.md)。
