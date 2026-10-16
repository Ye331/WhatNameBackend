# C 第一阶段验收记录

验证日期：**2026-10-16（Asia/Shanghai）**。分支：`feat/c-admin-grants-batch`；验收源码提交：`81627da`，后续文档提交不改变源码和测试。范围为 [功能说明](C_STAGE1.md) 中的 12 个接口及独立自测，不包含真实跨模块权限链路验收。

## 1. 环境与复现

本次环境：Windows、JDK **21.0.12.1**、Maven Wrapper **3.9.11**、Docker Desktop Linux 引擎 **29.2.1**。将 `JAVA_HOME` 指向本机 JDK 21，启动 Docker Desktop，在 `pom.xml` 所在目录执行：

```powershell
# 普通测试，不需要 Docker
.\mvnw.cmd -B test

# 完整验收：普通测试、打包、真实 MySQL 集成测试
.\mvnw.cmd -B verify -Pintegration-tests "-DtrimStackTrace=true"
git diff --check
```

集成测试使用与 Compose 一致的固定摘要 MySQL 镜像、仓库原建表脚本、随机端口和临时文件系统；禁用个人配置导入，不连接既有开发库。Docker 不可用时测试失败，不静默跳过。

## 2. 本次结果

**BUILD SUCCESS：125 项通过，0 失败、0 错误、0 跳过。**全量运行耗时 **2 分 24 秒**，结束于 **2026-10-16 20:40:17 +08:00**。数字来自本次 Surefire/Failsafe XML，参数化用例按实际执行次数统计。

| 测试范围 | 普通测试 | MySQL 集成测试 | 结果 |
|---|---:|---:|---|
| 管理员、清单、共享校验及公共异常处理 | 42 | 8（AdminIT） | 全部通过 |
| 单人授权、来源与默认接入边界 | 31 | 13（GrantIT） | 全部通过 |
| 批量与批量默认拒绝边界 | 1 | 30（BatchGrantIT 29、BatchAsyncIT 1） | 全部通过 |
| 合计 | **74** | **51** | **125 项通过** |

**2001 人批量授予及分页查询：67.144 秒。**计时包括提交、逐项执行和结果查询，不包括测试人员预置。该结果只验证功能规模，不代表并发或性能指标达标。

历史三轮累计测试数分别为 50、94、119，本次新增 6 项集成场景后为 125；这些是不同版本的累计数，不能相加。新场景先定向运行 6 项通过，再纳入本次全量回归。

## 3. 关键场景与证据

| 场景 | 验证内容 | 测试位置 |
|---|---|---|
| 管理规则与 HTTP 契约 | 任命等级/范围、自撤及同根同级互撤、清单版本、字符串 ID、分页、错误码和响应字段 | AdminRulesTest、AdminServiceTest、AdminControllerTest |
| 管理员真实事务 | 递归收缩、重任命、保留既有授权、签名/审计失败回滚、并发版本竞争和等待后资格复核 | AdminIT |
| 单人来源闭环 | 多来源、岗位部门、来源归属、重复请求、旧版本、最后来源回收、空集合签名和不一致数据拒绝 | GrantControllerTest、GrantServiceTest、GrantIT |
| 授权并发与回滚 | 同版本只有一个成功；等待期间任命撤销/清单缩减后拒绝；来源、关系、版本及证据一起回滚 | GrantIT |
| 批量正常与故障 | 持久接收、正式后台执行器、部分成功、重复目标、同键冲突、逐项 APPLIED 与业务同事务、调度失败及中断保持待处理 | BatchGrantIT、BatchAsyncIT |
| 执行中途资格变化（新增 2 项） | 首项提交后以 latch 暂停；真实管理员服务撤任命或缩减清单后继续。首项来源/关系/版本/证据保留，后项失败且无业务及成功证据残留 | `authorityChangeBetweenItemsPreservesCommittedSuccess` |
| 幂等键发起人隔离（新增 1 项） | 两个管理员同键同请求建立不同任务，各自重放命中自己的任务；同一来源业务不重复应用 | `sameKeyIsIsolatedByInitiator` |
| 查询范围变化（新增 1 项） | 当前范围缩小后计数、结果和分页同步缩小；无可见目标返回 404 | `queryUsesChangedScopeAndHidesBatchWhenNoTargetsRemainVisible` |
| 重放权限失效（新增 2 项） | 当前写权限或查看权限失效后重放返回 403，不重新调度、不增加版本或成功审计 | `replayRequiresCurrentFunctionPermissions` |
| 默认拒绝与规模 | 缺少正式接入时拒绝；2001 人完成且末页正确 | AdminIntegrationConfigurationTest、GrantIntegrationConfigurationTest、GrantDefaultBoundaryTest、BatchGrantIT |

新增四个方法均位于 `BatchGrantIT`。同步控制、功能权限替身与故障注入仅位于测试源码；并未向生产代码加入测试开关。

## 4. 交付检查与限制

- 本轮生产代码仅展开批量模块的密集语句，未修改 API/DTO、数据库结构、依赖或事务规则；未发现需要改变既定业务行为的缺陷。
- 应用 JAR 含管理员/授权/批量业务类和三份 SQL 映射，不含测试类、Fixture 或测试适配器。
- `git diff --check`、文档相对链接和旧文件引用检查通过。构建产物及个人配置不纳入提交。
- 结束后查询 Testcontainers 标签容器，结果为空，临时容器已清理；未改动既有开发数据库。
- 日志中的数据库约束失败为故障注入预期输出；Mockito 动态代理/JVM 类共享及已停止容器的连接池校验警告未导致测试失败。
- 测试签名与身份是替身。真实 JWT、当前会话/功能鉴权、密码学签名和完整审计仍需 A/D 接入；管理角色政策等暂定边界见功能说明。

本地结果文件：完整日志 `target/c-stage1-final-verify.log`；新增场景定向日志 `target/c-stage1-boundary-tests.log`；普通与集成 XML 分别在 `target/surefire-reports/`、`target/failsafe-reports/`。这些属于本地构建产物，不随 Git 上传，团队可使用上述命令复现。

验收结论：**C 第一阶段约定的 12 个接口及独立自测完成。**真实“登录→授权→菜单/API允许→回收→API拒绝”仍待团队集成验收，本报告不覆盖 GR04、OA/HR、批量恢复/重试或其他已明确暂缓的功能。
