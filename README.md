# 统一权限管理系统后端

Java 21 + Spring Boot + MyBatis + MySQL。Controller → Service → Mapper 三层；MySQL 用 Docker，后端在 IDEA 中运行。

## 启动

1. IDEA 打开 `pom.xml`，项目 SDK 和 Maven Runner 选择 JDK 21。
2. 打开 Docker Desktop。首次在 `backend` 目录的 Terminal 执行：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1
   docker compose up -d --wait
   ```

   自动生成本地配置并建立25张表（原23表及2张姓名盲索引表）。之后在 Docker Desktop 启动 `whatname-iam` 即可，停止不会删除数据。
3. 检查 `config/application-local.yml` 的数据库连接信息，默认Docker方式由脚本自动填写，无需再改；修改方法见下方。
4. 打开 `IamApplication.java`，点击绿色运行按钮。工作目录为 `backend`，后端端口 `8080`。

## 本地配置

`src/main/resources/application.yml` 是团队公共配置；个人数据库信息放在 `config/application-local.yml`，不要提交。

| 字段 | 何时修改 |
|---|---|
| `spring.datasource.url` | 数据库地址、端口或库名变化时修改；默认 `127.0.0.1:3307/unified_permission`，保留后面的连接参数 |
| `spring.datasource.username` | 使用其他数据库账号时修改；默认 `iam_dev` |
| `spring.datasource.password` | 填该账号实际密码；默认由脚本生成，不能仅修改此文件来更改数据库密码 |

若本地配置已存在，脚本不会覆盖。手动配置可复制 `config/application-local.example.yml` 为 `application-local.yml`，再填写以上字段。改完后重启后端。

端口 `3307` 被占用时：修改 `.env` 中的 `MYSQL_PORT`，同步修改本地配置URL端口，再运行 `docker compose up -d --wait`。后端 `8080` 被占用时，可在本地配置增加 `server.port: 8081`，并修改 `requests/smoke.http` 的 `baseUrl`。

## 数据库与自测

IDEA 的 **Database → ＋ → MySQL**：地址 `127.0.0.1`，端口 `3307`，用户 `iam_dev`，数据库 `unified_permission`，密码见 `config/application-local.yml`。连接后展开 **tables** 查看表。

打开 `requests/smoke.http`，运行请求：

- `system/ping`：检查后端启动。
- `system/database`：检查数据库连接，正常返回200。

## 开发约定

- `controller` 接收请求；`service` 处理业务与事务；`mapper` 执行SQL，XML在 `resources/mapper`。
- `entity` 为数据库实体，`dto` 为请求响应对象，`common` 为公共配置和处理。
- 响应使用 `ApiResponse`，跨模块通过 Service 协作。
- 两阶段开发：第一阶段独立实现基础功能，第二阶段补齐公共校验和联调；A/C稍多，B/D稍少。见 [分工与开发计划](DEVELOPMENT_PLAN.md)。
- `main` 受保护，成员从最新 `main` 拉任务分支，提交PR/MR审查，由组织者合并。见 [Git开发规范](GIT_WORKFLOW.md)。
- 本地配置、密码文件不提交；已有数据库改表使用迁移，不重复执行建表脚本。

管理员规则与清单维护已提供第一阶段实现和自动测试，见 [管理员模块说明](docs/ADMIN_STAGE1.md) 和 [测试报告](docs/ADMIN_STAGE1_TEST_REPORT.md)。管理员接口在真实认证、功能鉴权、签名和审计未接入时明确拒绝访问。不使用 Redis 或 DAO 层。

单人授予、按来源精确回收、用户角色和授权来源查询已提供第一阶段实现，见 [授权模块说明](docs/GRANTS_STAGE1.md) 和 [授权测试报告](docs/GRANTS_STAGE1_TEST_REPORT.md)。支持多来源共存、用户授权版本保护和事务内签名/审计接入；依赖未接入时默认拒绝。

批量基础复用单人业务，提供异步任务、逐项独立事务、幂等提交及范围过滤后的分页结果，见 [批量模块说明](docs/BATCH_STAGE1.md) 和 [批量测试报告](docs/BATCH_STAGE1_TEST_REPORT.md)。本阶段不含 dryRun、重启恢复和自动重试；OA、HR 及真实认证／签名／审计联调仍待后续完成。

US01 支持单字及中间片段姓名包含搜索，保留用户名/SG 子串与完整手机号查询。现有库须显式执行 `db/migrations/20261017_01_user_name_search.sql`，配置独立 `iam.security.name-search-key` 并对旧账号受控回填；未就绪的范围内关键词查询返回503。归一化、维护命令、泄露与真实D待复核边界见 [姓名搜索](docs/task17-name-search.md)。
