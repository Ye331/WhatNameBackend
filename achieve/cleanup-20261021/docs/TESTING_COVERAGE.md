# 测试与覆盖率使用说明

## 运行方法

准备 JDK 21、Maven 和正在运行的 Docker，在后端目录执行：

```sh
mvn -Pcoverage clean verify
```

这会运行默认测试（含资源/角色的独立 MySQL 容器测试），生成 `target/site/jacoco/index.html`，用浏览器打开即可逐包、逐类查看覆盖情况。XML 报告在同目录的 `jacoco.xml`。使用 `clean` 清除上一次数据，避免把多次不同范围的测试混成一次结果。

只运行资源、角色和登录边界，适合学习与调试：

```sh
mvn -Pcoverage -Dtest=BResourceRoleMySqlIntegrationTest clean verify
```

要同时执行现有的全部 `*IT` 扩展集成测试：

```sh
mvn -Pcoverage,integration-tests clean verify
```

扩展集成测试含大批量与恢复场景，可能耗时很长。默认测试不运行这些 `*IT`；部分历史 A/D 数据库测试仍由各自的环境开关控制，跳过不等于通过。覆盖率只代表本次实际执行的代码。

## 从哪个测试开始读

`src/test/java/com/whatname/iam/service/resource/BResourceRoleMySqlIntegrationTest.java` 使用真实 Spring 服务、登录、数据签名和临时 MySQL，已有登录→授权→菜单/API 允许→撤权→拒绝的完整流程。

本次新增五个场景，使用中文 `@DisplayName` 说明业务含义：

| 测试方法 | 业务预期 |
| --- | --- |
| `fiveWrongPasswordsLockLogin` | 连续五次错误密码触发锁定，随后正确密码也被拒绝 |
| `managementRoleAloneDoesNotPermitAdministration` | 有管理功能角色但没有管理员任命，不能管理资源或角色 |
| `appointmentAloneDoesNotSupplyFunctionPermissions` | 有一级管理员任命但没有功能权限，操作仍被拒绝 |
| `levelTwoCanReadButCannotWriteResourcesOrRoles` | 二级管理员可以按已有功能权限查询，但不能执行一级维护操作 |
| `sessionVersionChangeRejectsOldTokenButAllowsNewLogin` | 更新会话版本后旧令牌失效，新登录令牌恢复正常访问 |

测试按“准备数据→执行操作→检查结果”阅读。拒绝写入不仅检查错误码，还查询数据库确认没有新增记录。每个新增场景使用独立用户，不依赖测试执行顺序；数据只写入临时容器。测试工具方法只减少重复准备和断言，不增加新的测试框架。

## 如何理解报告

- 行覆盖率：本次测试执行过的代码行比例。
- 分支覆盖率：条件判断的不同路径被执行的比例。
- 测试断言：检查实际行为是否正确，例如拒绝越权后数据库没有变化。

覆盖率高不保证断言充分。当前没有设百分比门槛，也没有排除业务代码来提高数字；优先检查业务上的正常、拒绝和回滚路径。报告是构建产物，不提交 `target` 目录。

配置采用 JaCoCo 的 `prepare-agent` 和 `report`，参考 [官方 Maven 插件文档](https://www.jacoco.org/jacoco/trunk/doc/maven.html)；固定版本为 0.8.15，见 [官方版本记录](https://www.jacoco.org/jacoco/trunk/doc/changes.html)。

## 首次接入 JaCoCo 实测（重构前，2026-10-21）

执行 `mvn -Pcoverage clean verify`，退出码 0。377 项默认测试中，207 项通过、170 项按历史环境开关跳过，无失败或错误。资源/角色完整应用测试共 11 项全部通过，其中 5 项为本次新增。

本次报告统计全部生产类：行覆盖率 **55.97%**（2367 / 4229），分支覆盖率 **33.90%**（1097 / 3236），方法覆盖率 **58.52%**（656 / 1121）。这不是全量集成测试覆盖率：本轮未启用 `integration-tests`，上轮测试的执行数据也未混入。

运行日志位于工作区 `tmp/jacoco-tests.log`。上述数字是本次快照，后续运行应以新生成的报告为准。

后续按功能重构与扩展集成测试的最新结果见 [重构记录](REFACTORING.md)。当前生成的 HTML 报告可能已包含该轮新增执行数据，不应与上面的历史快照混淆。

本轮重构后的实际结果：默认测试与所选扩展集成测试合计 477 项通过、170 项跳过；行覆盖率 78.33%、分支覆盖率 58.44%、方法覆盖率 78.67%。执行范围、追加数据方式和未重跑场景详见上述重构记录，不能与仅默认测试的历史快照直接作同口径比较。
