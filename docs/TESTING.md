# 测试与验证

## 统一入口

需要 JDK 21、Python 和可用的 Docker。在 backend 目录执行：

```powershell
.\scripts\dev.ps1 test                # 日常：数据脚本测试 + 默认 *Test
.\scripts\dev.ps1 test -Integration   # 追加全部 *IT 集成测试，耗时较长
```

每个数据库测试类用 Testcontainers 启动一个临时 MySQL，只加载 db/schema.sql 和最小测试夹具，结束后删除，不连接个人数据库。手工联调见 [system.http](../requests/system.http)。

## 结构精简与受控初始化验证（2026-10-21）

本轮删除了端口接口、单实现接口和装配配置类，新增 `InitializationService`。业务判断、加锁、签名、审计未改动。

| 范围 | 结果 |
| --- | --- |
| 默认测试（全部 *Test 类） | 整套运行中 3 个类因测试替身改为继承后带上事务代理而失败（AccountManagement、HrIntegrity、HrBusiness）；修正替身后这 3 个类重跑通过，其余类首次即通过 |
| 新增 InitializationServiceTest | 3 项通过：小样本导入、超级用户登录并通过正常接口授权、重复初始化被拒、撤销任命后兜底恢复 |
| 集成测试 AdminIT、GrantIT、GrantIntegrationIT、GrantHistoryIT、HrGrantCollaborationIT、OaDecisionIT、ServiceRequestAuthenticationIT、TrustedServiceE2EIT、BatchAsyncIT | **258 项通过，0 失败**，与重构前记录的 258 项一致 |
| 真实 Excel 数据（一次性检查，未纳入测试源码） | 导入 111 个部门、10000 名员工及超级用户，约 284 秒；超级用户登录后查询用户列表约 3.6 秒 |
| 测试命名整合 | 测试中的分工代号统一改为功能名（如 `TestD` → `FakeDataSecurity`、`task06` → `dept`，`DBackedManagementDataSecurityTest` → `ManagementDataSecurityTest`），注释中的 A/B/C/D、M5/M6 改为模块名称；受影响的 6 个类与资源角色、审计、部门访问、批量输入补测共 11 个类重跑，**264 项通过** |
| 前端 | npm run build、npm run format:check 通过 |

BatchGrantIT、BatchRecoveryIT（大批量与恢复）本轮未运行。

### 整套覆盖率（一次连续 clean verify）

```powershell
.\mvnw.cmd '-Pcoverage,integration-tests' '-Dit.test=AdminIT,GrantIT,GrantIntegrationIT,GrantHistoryIT,HrGrantCollaborationIT,OaDecisionIT,ServiceRequestAuthenticationIT,TrustedServiceE2EIT,BatchAsyncIT' clean verify
```

结果：**754 项通过，0 失败、0 错误、0 跳过**（默认测试 496 项 + 集成测试 258 项），BUILD SUCCESS。命令与重构前记录相同，可直接比较。

| 范围 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | ---: |
| 全部后端 | **94.07%（5726/6087）** | **75.80%（2415/3186）** |
| 重构前干净重测（参考） | 91.53% | 71.39% |

| Service 包 | 行覆盖率 | 分支覆盖率 |
| --- | ---: | ---: |
| 账号 user | 100.00% | 87.96% |
| 角色 role | 99.17% | 92.50% |
| 资源 resource | 98.71% | 90.20% |
| 审计 audit | 98.45% | 94.92% |
| HR hr | 96.09% | 67.57% |
| 部门 department | 95.22% | 81.63% |
| 管理员 admin | 94.91% | 75.51% |
| 在线权限 permission | 94.59% | 71.33% |
| 认证 auth | 93.02% | 84.92% |
| 授权 grant | 92.72% | 70.31% |
| 事件 event | 91.49% | 73.97% |
| 初始化 system | 86.64% | 71.43% |
| OA oa | 100.00% | 72.92% |

初始化包中 `InitializationService` 行覆盖 98.67%；`InitializationRunner`（命令行入口，执行后退出进程）没有自动化测试，是该包偏低的原因。分支覆盖较低的是 HR、授权、在线权限，主要是批量恢复、HR/OA 联动和各种故障组合。

报告：target/site/jacoco/index.html。

耗时说明：GrantHistoryIT 约 23 分钟、GrantIntegrationIT 约 5 分钟，原因是每次操作都会对整个组织和历史记录验签，测试数据增多后越来越慢。这与“鉴权不缓存”的取舍同源，见 GUIDE 已知限制。

## 如何阅读测试和覆盖率

覆盖率入口是 target/site/jacoco/index.html；XML 和 CSV 位于同目录。业务代码没有通过排除配置隐藏。行覆盖率表示执行过的行，分支覆盖率表示条件路径，不能代替结果断言，也不能证明代码简单。

按功能阅读测试：InitializationServiceTest、AccountAuthenticationIntegrationTest、AccountManagementIntegrationTest、AccountIntegrityIntegrationTest、DepartmentBusinessIntegrationTest、HrBusinessIntegrationTest、ResourceRoleIntegrationTest、PermissionAuditIntegrationTest，以及授权与管理员的 *IT。

新增边界测试检查空白搜索与普通分页等价、合法签名下电话密文和索引不一致仍拒绝读取、异常 Unicode 输入不改变账号状态，以及 ID 集合去重和非法成员拒绝。现有测试继续检查越权拒绝、回滚、签名与审计故障、并发和事件重复提交。

缺少必需服务的局部应用现在在启动时失败，不再依赖阶段占位实现。未认证 HR 请求由真实认证模块返回 INVALID_SERVICE；对应旧测试已按这一行为修正，未删除拒绝断言。

受控初始化和超级用户恢复已有测试；真实页面完整链路及性能验收仍需单独完成。

## 资源与角色补测（2026-10-21）

新增 12 项，ResourceRoleIntegrationTest 从 12 项增至 24 项。本次整组执行 **24 项通过，0 失败、0 错误、0 跳过**。只修改测试和本文档，未修改正式业务代码。

```powershell
.\mvnw.cmd -Pcoverage -Dtest=ResourceRoleIntegrationTest verify
```

新增场景涵盖应用、菜单、API、角色的修改；管理平台与系统角色保护；非法状态、缺失资源、菜单跨应用父节点与自引用；清空映射及旧版本拒绝；HTTP 空修改请求拒绝；审计失败后映射、版本与签名一起回滚，解除故障后可按原版本重试。每项检查持久化结果或未变状态，不仅检查异常。

审计故障通过临时测试库的检查约束模拟，仅拒绝本场景的两种审计记录，并在 finally 中移除。无需额外数据库权限，不修改正式数据库结构。

| Service 包 | 补测前行覆盖率 | 补测后行覆盖率 | 补测前分支覆盖率 | 补测后分支覆盖率 |
| --- | ---: | ---: | ---: | ---: |
| 资源 resource | 74.28% | **98.71%** | 49.02% | **90.20%** |
| 角色 role | 70.25% | **99.17%** | 50.00% | **92.50%** |

当前累计覆盖率：行 **93.79%**，分支 **73.28%**。这是前述 651 项整套验证的覆盖数据与本次补测在同一正式代码版本上的累计结果；本次没有重新运行整套测试，不能称为本次全量干净重测。两次结果分别保留，不把未重跑的用例算作本次执行。完整重测仍使用上面的 clean verify 命令。

测试源码：src/test/java/com/whatname/iam/service/resource/ResourceRoleIntegrationTest.java。

## 授权输入、审计和部门访问补测（2026-10-21）

本轮新增四个小型单元测试类，共 **99 项通过，0 失败、0 错误、0 跳过**。正式业务代码未修改；使用项目已有的 JUnit 与 Mockito，不增加依赖。依赖被替换的单元测试验证输入与协作边界，不能替代此前真实数据库的事务回滚测试。

| 测试类 | 项数 | 主要断言 |
| --- | ---: | --- |
| BatchInputTest | 28 | 顺序与完全重复目标不影响摘要；角色或原因变化影响摘要；来源字段和回收来源必须一致，冲突目标拒绝 |
| AuditServiceTest | 25 | 递归拒绝敏感字段；记录元数据合法；请求标识处理；签名、写入失败和证据超限时不写入不完整日志 |
| AuditQueryServiceTest | 22 | 分页边界、空范围与不可见记录；九种元数据篡改；无效签名和损坏证据拒绝读取 |
| DepartmentAccessServiceTest | 24 | 加锁后复核会话；范围与功能权限同时检查；HR 上下文、当前身份状态、作用域及操作清单约束 |

```powershell
.\mvnw.cmd -Pcoverage '-Dtest=BatchInputTest,AuditServiceTest,AuditQueryServiceTest,DepartmentAccessServiceTest' verify
```

本次命令完整执行成功。累计报告以同一正式代码版本的既有覆盖数据继续收集，**不是重新运行整套测试**。

| 范围 | 累计行覆盖率 | 累计分支覆盖率 |
| --- | ---: | ---: |
| 全部后端 | **94.35%** | **75.83%** |
| 审计 Service 包 | 98.45% | **94.92%** |
| 部门 Service 包 | 95.01% | **81.63%** |
| 授权 Service 包 | 92.57% | 70.31% |
| BatchInput 类 | 93.22% | 90.74% |
| AuditService 类 | 100.00% | 100.00% |
| AuditQueryService 类 | 97.22% | 88.46% |
| DepartmentAccessService 类 | 97.30% | 95.24% |

剩余未覆盖分支主要还涉及批量任务执行/恢复和复杂 HR、OA 授权联动。不能从当前数字推断完整恢复、压力或全部故障组合均已验收。此次没有删除业务判断、排除代码或为了数字改变正式行为。

原 651 项整套验证、资源角色 12 项增量以及本轮 99 项增量应按各自执行范围解读；需要新的全量验收时运行 clean verify。

## 提交前定向复核（2026-10-22）

本次检查覆盖单实现接口合并、依赖注入、初始化与恢复，以及管理员/授权/可信服务调用的主要路径。执行下面的定向验证：

```powershell
.\mvnw.cmd '-Pintegration-tests' '-Dtest=InitializationServiceTest,ManagementDataSecurityTest,AdminServiceTest,GrantServiceTest,ExternalEventAuthenticationTest,BatchInputTest,AuditServiceTest,AuditQueryServiceTest,DepartmentAccessServiceTest' '-Dit.test=AdminIT,GrantIT,TrustedServiceE2EIT' verify
```

结果：**195 项通过，0 失败、0 错误、0 跳过**（默认测试 154 项 + 集成测试 41 项）。Python 数据脚本 4 项测试通过，PowerShell 入口语法检查通过，当前文档链接和提交差异空白检查通过。

本次没有重新执行 754 项整套测试，也没有重新收集覆盖率；前面的完整覆盖率属于其注明的历史运行。前端本次仅删除归档资料、更新 README，没有修改页面代码。运行日志位于本地 target/precommit-review.log，不提交。
