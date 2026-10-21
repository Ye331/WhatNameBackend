# 测试与验证

## 统一入口

需要 JDK 21、Python 和可用的 Docker。在 backend 目录执行：

```powershell
.\scripts\dev.ps1 test
.\scripts\dev.ps1 test -Integration
```

入口先运行数据准备脚本的测试，再执行 `-Pcoverage clean verify`。`-Integration` 追加 Maven 的 integration-tests 配置，运行全部 `*IT`，包含耗时较长的大批量与恢复测试。

默认 `*Test` 中的数据库测试不再由环境开关控制，也不再连接个人数据库。TestDatabase 为每个测试类创建临时 MySQL，只加载唯一的 db/schema.sql 和最小测试夹具，结束后释放。测试按默认串行方式执行。测试夹具不能替代 Excel 正式入库或初始管理员创建。

手工联调只保留 [system.http](../requests/system.http)。令牌、ID 和修订号从真实响应取得；HR/OA 请求必须使用服务签名。

## 本轮验证范围

2026-10-21 的学生可读性重构使用下面的命令，从空的 target 重新编译、运行和收集覆盖率：

```powershell
.\mvnw.cmd '-Pcoverage,integration-tests' '-Dit.test=AdminIT,GrantIT,GrantIntegrationIT,GrantHistoryIT,HrGrantCollaborationIT,OaDecisionIT,ServiceRequestAuthenticationIT,TrustedServiceE2EIT,BatchAsyncIT' clean verify
```

这会运行全部默认测试和指定的 9 组集成测试。BatchGrantIT、BatchRecoveryIT 的完整大批量/恢复验证不包含在本轮结果中；它们仍保留在完整测试入口。不能据此声称达到正式性能指标。

本轮干净重测通过：后端 **651 项通过、0 失败、0 错误、0 跳过**，包括默认测试 393 项和上述集成测试 258 项。Maven 完整执行成功。

JaCoCo 全部正式代码：**行覆盖率 91.53%（5391/5890），分支覆盖率 71.39%（2266/3174）**。未排除业务包、DTO 或异常分支来提高数字。

| Service 包 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | ---: |
| 账号 user | 100.00% | 87.96% |
| 认证 auth | 93.01% | 84.92% |
| 部门 department | 87.39% | 66.84% |
| 管理员 admin | 94.11% | 75.00% |
| 授权 grant | 92.48% | 68.17% |
| 在线权限 permission | 94.59% | 71.33% |
| 资源 resource | 74.28% | 49.02% |
| 角色 role | 70.25% | 50.00% |

上表按对应 Service 包统计，不含 Controller 和其他包。资源、角色分支覆盖仍是后续补测重点；本轮结果不能声称所有异常组合已验证。

第一次整套运行暴露日志测试依赖全局日志级别的问题；修复为测试自行设置并恢复级别后，再次执行上述 clean verify，得到本页最终结果。未用单独重跑失败用例拼接覆盖率。

前端正式页面：37 项测试通过，生产构建与格式检查通过。执行 `npm test`、`npm run build`、`npm run format:check`。旧原型的 16 项测试已随原型归档。

数据准备脚本：4 项测试通过，检查重复员工标识、错误表头、公式拒绝，以及错误 dimension 和合并单元格展开。脚本只整理来源，不执行正式导入。

## 如何阅读测试和覆盖率

覆盖率入口是 target/site/jacoco/index.html；XML 和 CSV 位于同目录。业务代码没有通过排除配置隐藏。行覆盖率表示执行过的行，分支覆盖率表示条件路径，不能代替结果断言，也不能证明代码简单。

按功能阅读测试：AccountAuthenticationIntegrationTest、AccountManagementIntegrationTest、AccountIntegrityIntegrationTest、DepartmentBusinessIntegrationTest、HrBusinessIntegrationTest、ResourceRoleIntegrationTest、PermissionAuditIntegrationTest，以及授权与管理员的 *IT。

新增边界测试检查空白搜索与普通分页等价、合法签名下电话密文和索引不一致仍拒绝读取、异常 Unicode 输入不改变账号状态，以及 ID 集合去重和非法成员拒绝。现有测试继续检查越权拒绝、回滚、签名与审计故障、并发和事件重复提交。

缺少必需服务的局部应用现在在启动时失败，不再依赖阶段占位实现。未认证 HR 请求由真实认证模块返回 INVALID_SERVICE；对应旧测试已按这一行为修正，未删除拒绝断言。

历史结果移至 [重构前测试说明](../achieve/student-refactor/TESTING-before.md)。历史混合覆盖率不能与本轮清空后重测的数字直接当作同口径增幅比较。

工作区运行日志：tmp/student-final.log、tmp/student-frontend.log。正式演示初始化、超级用户恢复、真实页面完整链路及性能验收仍需单独完成。
