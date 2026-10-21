# 最终整合测试记录

日期：2026-10-21。后端基础提交 `3d868a6`，前端基础提交 `f46e8db`；当前工作分支均为 `integration/final-rbac0`。

## 已完成验证

| 验证 | 结果 | 证明范围 |
| --- | --- | --- |
| 前端 `npm ci`、`npm run build` | 通过 | 当前锁定依赖可安装，正式前端可构建 |
| 前端 `npm test -- --reporter=line` | 52 通过 | 原型、应用入口、第一及第二阶段页面交互；使用模拟服务，不等于真实后端页面验收 |
| 完整后端资源/角色测试 | 最终干净编译后 6 项全部通过 | 真实密码登录、真实 A/C/D 依赖和独立 MySQL；包括授予、菜单/API 允许、精确回收后拒绝、可授权角色清单与菜单/API 名称字段 |

新增的完整后端测试位于 `src/test/java/com/whatname/iam/service/resource/BResourceRoleMySqlIntegrationTest.java`。它不使用项目开发库，启动临时 MySQL 容器并在测试结束后清理；仅测试账号初始化直接写入测试库，后续登录和业务操作使用正式服务。JWT、签名密钥只在测试进程中生成。

新增边界包括：注销令牌不能读写资源或角色；应用签名异常时创建菜单/API 失败且无新增记录；管理角色授予不会创建管理员任命；一级管理员可获得真实可授权角色清单；下级不能委派未获上级许可的管理角色。

## 最终回归

完整命令为 `mvn -P integration-tests verify`，需要 JDK 21、Maven 和运行中的 Docker。首次运行结果：

| 范围 | 总数 | 通过 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Surefire 常规测试 | 372 | 202 | 0 | 0 | 170 |
| Failsafe 扩展集成测试 | 316 | 306 | 1 | 9 | 0 |

首次完整命令退出码为 1，不能记为一次全绿运行。失败原因与修正：

- `CStage2IntegrationIT` 的 HR 非可信上下文旧断言预期 `AUTH_STATE_UNAVAILABLE`，实际生产认证返回 `INVALID_SERVICE`。修正断言，保留拒绝未认证请求的行为。
- `ServiceRequestAuthenticationIT` 的独立测试配置漏装真实 `DataSignatureService`，9 项均在启动时出错。补齐签名服务、Mapper、进程内测试密钥及服务身份签名，不使用模拟验签服务。

修正后执行 `mvn -Dtest=BResourceRoleMySqlIntegrationTest,CStage2IntegrationIT,ServiceRequestAuthenticationIT clean test`，退出码为 0：

| 重跑范围 | 通过 | 失败/错误/跳过 |
| --- | ---: | --- |
| 第二阶段跨模块 `CStage2IntegrationIT` | 49 | 0 / 0 / 0 |
| 服务认证 `ServiceRequestAuthenticationIT` | 9 | 0 / 0 / 0 |
| 资源与角色 `BResourceRoleMySqlIntegrationTest` | 6 | 0 / 0 / 0 |

这次清理后重新编译了全部生产和测试源码，覆盖最后补充的资源返回字段及删除无调用者转发类后的完整应用启动。未重复执行已通过的耗时批量测试，因此结论是“完整回归发现问题后，修正并通过相关 64 项重跑”，不是声称最终源码又完整跑过一次全部测试。

按各用例最后一次结果汇总，后端 518 项通过、170 项跳过；前端另有 52 项通过，重跑不重复计数。原始执行摘要保存在工作区 `tmp/final-integration-summary.json`，完整与重跑日志分别为 `tmp/final-integration-verify.log`、`tmp/final-integration-final-resource.log`。

大批量用例耗时观察：`BatchGrantIT.batchLargerThanTwoThousandExecutesAndPaginates` 约 2513 秒；`BatchRecoveryIT` 真实签名的 2001 项执行记录为 381218 毫秒，2001 项成功。两者测试准备与测量口径不同，不能直接比较，也不能据此宣布达到详细设计中的性能指标。

历史 A/D 数据库测试仍通过环境开关单独启用，其默认跳过结果不能记为通过。本轮新增完整应用测试和现有 `*IT` 使用独立容器，避免向个人开发库写测试数据。

## 不属于已完成验收的项目

- 正式初始化、超级用户恢复、真实前端连接后端的手工/浏览器演示。
- 全量员工数据导入、两个演示业务系统的实际运行。
- 一万人集中登录、鉴权 20/50ms 指标及完整性能测试。
- 备份恢复演练、部署环境与 HTTPS 验证。

以上须分别提供执行证据，不能用本页局部通过结果代替。
