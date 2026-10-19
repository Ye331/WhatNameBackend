# 统一权限管理系统后端

Java 21 + Spring Boot + MyBatis + MySQL。Controller → Service → Mapper 三层；MySQL 用 Docker，后端在 IDEA 中运行。

## 启动

1. IDEA 打开 `pom.xml`，项目 SDK 和 Maven Runner 选择 JDK 21。
2. 打开 Docker Desktop。首次在后端仓库根目录（`pom.xml` 所在目录）的 Terminal 执行：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\scripts\setup-local.ps1
   docker compose up -d --wait
   ```

   自动生成本地配置并建立团队设计的23张表。之后在 Docker Desktop 启动 `whatname-iam` 即可，停止不会删除数据。
3. 检查 `config/application-local.yml` 的数据库连接信息，默认Docker方式由脚本自动填写，无需再改；修改方法见下方。
4. 打开 `IamApplication.java`，点击绿色运行按钮。工作目录为后端仓库根目录，后端端口 `8080`。

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

C 第一阶段提供管理员规则与清单、单人授予与精确回收、异步批量基础，共 12 个接口。范围、请求示例及 A/B/D 接入约束见 [功能与接入说明](docs/C_STAGE1.md)，可复现命令与实际结果见 [测试报告](docs/C_STAGE1_TEST_REPORT.md)。真实认证、功能鉴权、签名和审计未接入时默认拒绝；完整权限链路仍待团队联调。

D 第一阶段提供菜单/API 权限计算、7 个查询/鉴权接口及 Ed25519 签名、审计公共方法，见 [D 接入说明](docs/D_STAGE1.md)。服务签名、防重放和 A/B/C 生产写入适配属于第二阶段，未接入处继续拒绝；测试预置身份只存在于测试代码。

US01 支持单字、全名及中间片段姓名包含搜索，保留用户名/SG 子串与完整手机号查询。遵循团队现有23表，不新增表、字段、索引或专用密钥；在权限筛选后的范围内分批验签、内存解密匹配，完整统计后分页。大范围查询需遍历全部候选，性能尚未验收，详见 [姓名搜索现行方案](docs/task18-name-search-existing-schema.md)。

A 当前按 C 已有的用户/部门范围方法适配，C 的五个文件已恢复 main 原样；事件表不增加认领字段，非终态在线恢复明确拒绝。真实 C HR 回收、D 认证/功能/签名审计和人工恢复流程仍需团队接入，见 [当前协作说明](docs/task12-c-integration.md) 与 [自测入口](docs/task13-a-self-test.md)。
