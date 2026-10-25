# 简化版验证记录

验证日期：2026-10-24。Java 21，前端使用本机 Chrome。以下结果对应当前简化代码，不沿用旧版测试数量。

| 检查 | 结果 |
|---|---|
| Python 来源整理边界 | 4 项通过 |
| Java 回归与全量初始化 | 46 项通过，0 失败，0 跳过 |
| MySQL 专项 | 11 项因 Docker 引擎不可用跳过，不算通过 |
| Vue 构建 | 通过 |
| 前端交互 | 7 项通过 |
| 前端格式 | 通过 |

## 后端覆盖

CourseworkFlowTest 通过真实 Spring Controller、Service、JPA Repository 和隔离 H2 数据库验证身份及 IAM 自我鉴权、范围内临时账号、密码登录、暂停失效、多角色并集、即时撤权、OA 重复回收、服务凭据绑定、资源及角色配置、越部门范围、批量部分成功、调岗、离职、冻结/解冻、签名篡改拒绝、错误配置回滚、双请求并发完整保存、自我撤权、全量初始化与本地恢复。

独立测试保留 JWT、数据签名密钥、手机号、拼音和密码哈希边界。SchemaParityTest 确保 H2 测试结构只移除了 MySQL 方言语法，仍使用相同的 11 表和约束。

全量初始化验证 111 部门、10,000 员工、18 来源应用、359 来源功能，加上 IAM/OA/HR 演示后为 21 个应用。实际数据库用户数 10,001，包含初始化管理账号。

最后一次 H2 全量导入及恢复验证链约 13.97 秒，包含导入、查询、撤权、恢复等操作；不是 MySQL 性能测试，也不是鉴权耗时。

## 重跑

在 backend 目录：

```powershell
.\scripts\dev.ps1 test
.\scripts\dev.ps1 test -FullImport
.\scripts\dev.ps1 test -FullImport -Integration
```

最后一项需要 Docker。MySqlFlowIT 复用同一组业务测试，创建独立 MySQL 8.0.46 容器并执行正式 schema.sql，不连接本地旧库。Docker 不可用时明确跳过，因此必须检查 failsafe 的 Skipped 数量。

最终验证前已执行 Maven clean，避免已删除模块的旧 class 或测试报告残留。报告位于 target/surefire-reports、target/failsafe-reports、target/full-import-h2-report.json；完整运行日志为 coursework-simplified-verify.log。

前端在 frontend 目录：npm test、npm run build、npm run format:check。交互测试覆盖登录、取消旧管理员与短信入口、临时账号、无版本/来源的角色集合保存、范围设置、只读权限、角色权限/菜单接口配置和移动端业务演示。测试内拦截请求，属于页面契约测试，不能替代真实 MySQL 联调。

## 尚未完成的环境验证

2026-10-24 首轮验证时 Docker 引擎不可用，11 项 MySQL 专项测试跳过。

2026-10-25 后续验证：独立的 3308 MySQL 8.0.46 容器已启动，用户在 IDEA 完成真实全量初始化。已核对 10,001 个账号、111 个部门、21 个应用，并通过运行中的后端实际调用验证登录、本人 14 项 IAM 权限、用户分页、部门、角色、应用、日志、OA 菜单和 OA 演示鉴权。前端构建及 7 项交互测试再次通过。

这部分是实际 MySQL 初始化与接口联调，不等于 MySqlFlowIT 专项套件已通过。完整专项回归和浏览器连接真实后端的逐项验收仍需运行 -Integration 并完成 GUIDE 的人工演示。旧数据库没有被删表、重建或写入。
