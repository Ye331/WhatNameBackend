# 第一阶段测试记录

## 审查结论

**本 PR 提交测试结果，不申请第一阶段整体通过验收。**独立自动化测试与基础冒烟已完成；B 模块存在可复现的未登录写入缺陷 S1-SEC-01，真实跨模块联调仍待完成。组长可据此安排修复和后续验收。

- 测试日期：2026-10-18
- 代码提交：`805db4c`
- 测试分支：`test/stage1-acceptance`
- 环境：macOS、JDK 21.0.12.1、Docker 29.2.1；MySQL 运行在 Docker 容器中
- 端口：基础冒烟后端 18081、未登录访问验证后端 18091；个人 MySQL 3307，隔离测试 MySQL 3308。端口仅为本机测试设置，不是项目固定配置

## 基础冒烟测试

| 检查 | 实际结果 | 结论 |
|---|---|---|
| `GET /api/v1/system/ping` | HTTP 200，`status=UP` | 后端可接收请求 |
| `GET /api/v1/system/database` | HTTP 200，`status=UP` | 后端可连接 MySQL |
| 未登录 `GET /api/v1/departments` | HTTP 401，`code=INVALID_TOKEN` | 无凭据时拒绝访问部门接口 |

2026-10-18 再次执行上述三个只读 HTTP 检查，均符合预期，且每个响应的 `X-Request-Id` 与响应体 `requestId` 一致。测试时临时启动后端，结束后已关闭该进程。

## 现有自动化测试基线

新增 B 可选测试后再次执行 Maven `test`：共发现 258 项，其中实际执行并通过 133 项、跳过 125 项；失败 0 项、错误 0 项，结果为 `BUILD SUCCESS`。原始基线是 255 项、133 项通过、122 项跳过；新增的 3 项 B 数据库测试在普通运行中默认跳过，已在隔离 MySQL 中显式运行并全部通过。

跳过的 125 项不计为通过。本次运行不能代替手动接口测试或完整系统验收。

部门数据导入后，显式启用 `DepartmentMapperTest` 并连接本地 MySQL：实际运行 3 项，通过 3 项，失败 0、错误 0、跳过 0，`BUILD SUCCESS`。这三个用例验证了部门层级、路径、子树及异常查询，属于只读 Mapper 测试，不能算作部门 HTTP 接口的成功用例。

2026-10-18 17:14，在本机执行 `./mvnw -B verify -Pintegration-tests -DtrimStackTrace=true`，隔离的 C 模块 Testcontainers 集成套件实际运行 **51 项，通过 51 项，失败 0、错误 0、跳过 0**，整次构建 `BUILD SUCCESS`。测试使用临时 MySQL 容器，结束后未留存运行中的 Testcontainers 容器；个人开发库复查仍为部门 111、应用/菜单/API/用户/角色各 0。该套件使用测试身份与其他模块替身，不计作生产环境的登录—授权—鉴权完整链路通过。

2026-10-18，使用端口 3308 的**临时独立 MySQL 数据卷**运行 A 模块相关的五个真实数据库测试类：`DepartmentMapperTest` 3 项、`M1MySqlIntegrationTest` 37 项、`Task12M5MySqlIntegrationTest` 22 项、`DepartmentBusinessIntegrationTest` 23 项、`HrBusinessIntegrationTest` 21 项；合计 **106 项通过，失败/错误/跳过均为 0**。临时库导入仓库的 111 条虚构部门数据，测试结束后销毁数据卷。测试包含服务层与部分 HTTP 请求，但身份、签名或跨模块协作使用测试专用实现，不能代替正式联调。

另用全新临时 MySQL 数据卷显式执行 `DMySqlIntegrationTest`：**16 项通过，失败/错误/跳过均为 0**。该套件使用测试专用的可信服务身份及 C 模块完整性适配器；数据卷已销毁。

发现 B 模块原先没有资源、角色业务测试后，新增 `BResourceRoleMySqlIntegrationTest`，用全新临时 MySQL 验证应用/菜单持久化、菜单树防环、API 路径规范化冲突、菜单—API 映射版本、角色—菜单映射版本及错误请求不改变已保存的映射：**3 项通过，失败/错误/跳过均为 0**。该测试直接调用服务层及真实 Mapper，不验证 HTTP 身份鉴别。测试数据卷已销毁。

## 代码覆盖率

用 JaCoCo 0.8.12 对默认单测、C Testcontainers 套件、A/D/B 临时库套件追加采集；`jacoco:report` 分析了 193 个生产类。结果：**行覆盖 2474/2767，89.41%；分支覆盖 1299/1808，71.85%**。HTML 明细见 `target/site/jacoco/index.html`；原始采集文件为 `target/jacoco.exec`。这些文件在构建输出目录，执行 `mvn clean` 会删除。

上述 JaCoCo 文件是本机生成物，按仓库 Git 规范不提交 `target/`；本记录保存了报告总计数。复测时须对相同代码版本分别启用默认单测、C 套件及 A/B/D 的隔离库用例，以 `jacoco.append=true` 合并执行数据，再运行 `org.jacoco:jacoco-maven-plugin:0.8.12:report`。单独执行普通 `mvn test` 得不到上述合并覆盖率。

资源服务行覆盖率 62.38%（131/210），角色服务 62.96%（51/81）；其余未覆盖分支不能由总覆盖率推断为正确。覆盖率记录的是测试期间执行过的生产代码，不代表全部 56 个设计 API 的权限与正式签名链路都通过。

## 缺陷 S1-SEC-01：资源与角色接口允许未登录写入

**第一阶段验收不通过，高优先级。**最新设计要求 RE01 为 `IAM_RESOURCES_VIEW+ADMIN`，RE02 为 `IAM_RESOURCES_WRITE+M5_L1`，RL02 为 `IAM_ROLES_WRITE+M5_L1`。在代码 `805db4c`、正式后端程序与全新临时 MySQL 上，不发送 `Authorization` 头：

| 请求 | 预期 | 实际 |
|---|---|---|
| `GET /api/v1/applications?page=1&pageSize=10` | 拒绝未登录访问 | HTTP 200，返回应用列表 |
| `POST /api/v1/applications`，合法虚构应用 JSON | 拒绝未登录写入 | HTTP 200，返回应用 ID；`sys_application` 对应记录数为 1 |
| `POST /api/v1/roles`，合法虚构业务角色 JSON | 拒绝未登录写入 | HTTP 200，返回角色 ID；`sys_role` 对应记录数为 1 |

复现时先将后端连接到**可销毁的隔离数据库**并设端口 18091，然后执行以下无 `Authorization` 请求；请求体中的编码仅用于测试：

```bash
curl -i 'http://127.0.0.1:18091/api/v1/applications?page=1&pageSize=10'
curl -i 'http://127.0.0.1:18091/api/v1/applications' -H 'Content-Type: application/json' -d '{"appCode":"B_NOAUTH_PROBE","appName":"Unauthorized probe","description":"isolated test"}'
curl -i 'http://127.0.0.1:18091/api/v1/roles' -H 'Content-Type: application/json' -d '{"roleCode":"B_NOAUTH_ROLE","roleName":"Unauthorized role","roleType":1,"description":"isolated test"}'
```

`ResourceController`、`RoleController` 当前调用服务时没有传入身份凭据或执行管理权限检查，且没有针对这两组路由的全局认证拦截器。上述三项是**实测**；其他 B 路由需要修复后逐项回归，不能仅据代码结构算作已实测。所有写入均发生在隔离测试库，后端进程已关闭，临时数据卷已销毁。建议由负责 B 模块与总体权限接入的同学在入口增加可信身份与管理权限校验，并补充“未登录/无权限”回归测试，再做完整 HTTP 验收。

## API 契约静态核对

以组内共享的《详细设计和 API 设计 v3》中的 `03_OpenAPI_3.0_候选契约.yaml` 为最新设计，按 HTTP 方法和相对路径核对 Controller；设计文件位于仓库外，不随本 PR 提交。设计共 56 项，当前代码找到 54 项相同路由。未找到 `GR04 GET /grants/history` 和 `OA01 POST /integrations/oa/decisions`；仓库第一阶段 C 报告将这两项列为后续工作。代码另外提供两个设计目录之外的系统检查接口：`GET /system/ping`、`GET /system/database`。这里只证明路由声明存在，不能证明接口功能已经通过。

## 测试数据基线

在导入任何业务测试数据前，查询本地测试库得到：

| 数据表 | 记录数 |
|---|---:|
| `sys_application`（应用） | 0 |
| `sys_menu`（菜单） | 0 |
| `sys_api`（API 资源） | 0 |
| `sys_dept`（部门） | 0 |
| `sys_user`（用户） | 0 |
| `sys_role`（角色） | 0 |

随后通过仓库提供的 `db/dev-data/department-seed.sql` 导入本地开发部门。导入命令退出码为 0，复查得到部门总数 111、`DEV-DEPT-%` 开发部门 111；应用、菜单、API 资源、用户、角色仍各为 0。仓库的 `verify-department-seed.py` 因预设位置缺少原始 Excel，在导入前即停止，所以该脚本的重复导入、冲突回滚等专项验证**尚未执行**。

这 111 条部门使用开发编号，不是正式 HR 数据。临时库中已经运行若干服务层与 HTTP 业务成功用例，但个人开发库仍没有可登录、可完成跨模块联调的应用/菜单/API/账号/角色数据。用户、授权等数据还需满足系统的签名和审计要求。

## 当前验收状态

已完成当前仓库可独立执行的默认单测、A/B/C/D 模块真实数据库测试、基础 HTTP 冒烟、路由静态核对与代码覆盖率采集。**B 模块存在 S1-SEC-01 未登录写入缺陷，不能通过第一阶段验收。**其余逐模块的正式 HTTP 成功用例和“登录 → 授权 → 菜单 → API 允许 → 回收 → API 拒绝”联调仍需团队完成受控初始化及生产适配后执行；测试替身结果不算正式联调通过。

测试结束复查个人开发库：部门 111，应用/菜单/API/用户/角色各 0；仅个人 MySQL 容器继续运行，全部测试专用 MySQL 容器和数据卷已清理。
