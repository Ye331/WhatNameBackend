# 测试说明

测试代码位于 `src/test/java`，测试资源位于 `src/test/resources`。Java 21，JUnit 5，Spring Boot Test，MockMvc 和 JPA 验证当前业务规则。

## 运行

在 backend 目录执行：

```powershell
.\scripts\dev.ps1 test
.\scripts\dev.ps1 test -FullImport
.\scripts\dev.ps1 test -FullImport -Integration
```

默认使用独立 H2 数据库，各业务场景重新初始化。FullImport 增加真实 Excel 来源的全量导入与恢复测试；Integration 增加独立 MySQL 容器验证，需要 Docker。

## 测试范围

| 测试类 | 验证内容 |
|---|---|
| SimplifiedBusinessTest | 初始化、登录、用户维护、RBAC0、管理范围、资源、HR/OA、组织变动、签名、日志、事务回滚、并发保存、批量部分成功、本地恢复 |
| SimplifiedInputTest | 编号、密码、手机号、姓名和路由输入边界 |
| SimplifiedSessionTest | JWT 签名和字段、有效期、会话版本、密码哈希 |
| SimplifiedConfigurationTest | 密钥生成和复用、损坏配置拒绝、服务凭据、初始化和恢复入口 |
| SimplifiedMySqlIT | 在独立 MySQL 容器中复用业务场景 |

全量导入断言包括：111 个部门、10,000 名员工、18 个来源应用、359 条来源功能、10,001 个账号和 21 个应用。

## 报告与限制

- H2 明细：`target/surefire-reports`。
- MySQL 明细：`target/failsafe-reports`。
- 覆盖率：`target/site/jacoco/index.html`，以本次运行生成的报告为准。
- 未启用 FullImport 时，全量场景跳过；Docker 不可用时，MySQL 场景跳过。跳过不代表通过。
- H2 通过不等于 MySQL 验证通过。
- 前端在 frontend 目录执行 `npm test`、`npm run build`、`npm run format:check`。页面测试模拟接口，不能替代真实后端联调。

## 本次验证

2026-10-27：后端 `test -FullImport` 119 项通过，0 失败、0 错误、0 跳过，构建成功。前端 7 项测试、构建、源码格式检查通过。MySQL 容器专项本次未运行。
