# 测试与验证

JDK 21、Docker 可用，在 backend 目录执行：

```powershell
.\scripts\dev.ps1 test
.\scripts\dev.ps1 test -Integration
```

统一测试入口先运行 Python 数据校验测试，随后分别执行 `mvnw.cmd -Pcoverage clean verify` 与 `mvnw.cmd -Pcoverage,integration-tests clean verify`。第二条含全部 *IT 大批量测试，耗时较长。历史环境开关控制的用例仍可能跳过，跳过不等于通过。指定用例可直接使用 Maven 的 -Dtest 和 -Dit.test。

Testcontainers 与 Compose 共用 db/schema.sql，不再加载第二份资源补丁。测试夹具不能替代演示初始化。手工联调唯一入口是 [system.http](../requests/system.http)，按功能逐条执行；令牌、ID、修订号从真实响应取得。HR/OA 必须使用真实服务签名。

覆盖率报告在 target/site/jacoco/index.html。行覆盖率衡量执行的行，分支覆盖率衡量条件路径，两者不能代替断言。没有排除业务代码提高数字。

前端执行 npm test、npm run build、npm run format:check。当前仅测试正式页面；旧原型和专用测试已归档，因此数量不能直接与此前含原型的 53 项比较。

## 验证记录

此前功能重构：后端 477 项通过、170 项跳过，行覆盖率 78.33%、分支覆盖率 58.44%。这是历史快照，详见 [原始记录](../achieve/cleanup-20261021/docs/REFACTORING.md)，不是本轮重新全量执行的结果。

2026-10-21 本轮结构整理验证：

- 后端 54 项通过、0 失败、0 跳过：ResourceRoleIntegrationTest 12、InputValidationTest 11、AdminIT 9、GrantIT 13、ServiceRequestAuthenticationIT 9。所有测试源码编译通过；选取的容器测试实际使用唯一 schema.sql 建库。
- 前端正式页面 37 项通过，正式构建和格式检查通过。旧原型的 16 项专用测试随原型归档。
- 数据准备脚本 4 项边界测试通过，覆盖错误表头、重复员工标识、公式拒绝、错误 dimension 和合并单元格展开。实际三份来源生成 111 / 10000 / 359 条记录，actualImported=0。
- Docker Compose 配置检查通过。Windows PowerShell 在临时目录执行 setup 并重复执行通过，确认数据库密码占位符已替换、签名配置已包含、已有配置字节不变。
- 本轮没有运行全部历史数据库测试或大批量性能测试，没有访问实际业务数据进行 HTTP 写操作。

本轮后端命令：

```sh
mvn -Pcoverage,integration-tests -Dtest=ResourceRoleIntegrationTest,InputValidationTest -Dit.test=AdminIT,GrantIT,ServiceRequestAuthenticationIT verify -q
python -m unittest discover -s scripts -p 'test_*.py' -v
```

日志位于工作区 tmp/cleanup-backend.log、tmp/cleanup-frontend.log。本轮未清理此前 JaCoCo 执行数据，因此不发布混合数据的新覆盖率；完整重测使用上面的 clean verify。
