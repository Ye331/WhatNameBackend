# 共享设计与 API 契约快照（A 工作分支）

**2026-10-18 Task20当前更正**：四份来源文件及source-manifest是历史来源快照，保留原字节用于溯源，不是对其中A自行补写增量的批准。原文末尾的事件认领字段/令牌恢复设计已撤回，不再是当前结构或部署要求；当前事件表使用main原有字段，实际schema与迁移以本页下方当前索引为准。C五文件已恢复main原样，A改用C既有公共方法，现行协作和限制见[接入说明当前段](../task12-c-integration.md)。

快照时间：2026-10-16T14:17:57+08:00（北京时间，Asia/Shanghai）。本地 Git 基线：`feat/backend-a-rbac0` / `b227924f9e5c769e6a0b3b115b52fba29ff470ed`。这是为 A 全部完成后的统一 PR 准备的可审查快照；未在本任务提交、推送或创建 PR，也不代表远端最新、全体成员批准或 A 最终验收。

## 阅读入口与来源

| 文件 | 内容与原始来源（OOAD 根相对路径） |
|---|---|
| [完整系统设计](01-system-design.md) | `RBAC0/design/详细设计和api设计_v3/详细设计和api设计_v3/01_系统详细设计说明书.md` |
| [完整 API 规范](02-api-spec.md) | 同上源目录的 `02_API接口设计规范.md`；全部 56 操作、逐接口字段、错误与调用时序 |
| [OpenAPI 3.0 候选契约](03-openapi.yaml) | 同上源目录的 `03_OpenAPI_3.0_候选契约.yaml`；逐字节复制 |
| [数据库设计说明](04-database-design.md) | `RBAC0/design/数据库设计/02_数据库设计说明.md` |
| [来源清单](source-manifest.json) | 四源及目标 SHA-256、字节数、快照时点、本地 Git 基线、两处链接变换和完整 diff |

文件采用 01-system-design / 02-api-spec / 03-openapi / 04-database-design 的顺序命名，和四份唯一源一一对应，不另造精简契约。两份 Markdown 各仅将开发计划链接 `../../backend/DEVELOPMENT_PLAN.md` 改为仓库可解析的 `../../DEVELOPMENT_PLAN.md`，其余字节保留；数据库说明和 OpenAPI 均与源同字节。源路径只用于溯源，读者应使用本目录副本和以下仓库内链接。

## 规范定义与当前实现边界

完整规范仍定义 **56 操作：A 18，非 A 38**。静态解析操作与本地引用不证明这些能力已被全体实现、审批或运行验证。原文中的“冻结口径”等是来源文档原有表述；本快照没有新增团队批准。`iam.example.test` 是占位域名，真实入口以实际部署为准。

A 的 18 个 HTTP 路由已有独立实现。US01 姓名搜索现行方案见 [Task18](../task18-name-search-existing-schema.md)：沿用团队23表，范围内分批验签及内存解密匹配，完整计数后分页，保留用户名/SG/完整手机号查询。Task17 两表索引及密钥/回填已撤下，不属于团队批准设计；其文档仅保留历史。四份源快照与 source-manifest 保留原字节；源中 Task10 的“姓名未完成”为当时状态，本文仅索引现行实现，不改写源契约或冒称团队新增确认。HTTP字段及18/56操作数量保持。

**真实 C/D 仍未联调完成**。A 的局部协作端口不是团队已冻结的真实 C/D 方法；必要生产依赖缺失时默认拒绝，允许替身仅在 `src/test`。完整可信员工导入、真实登录→授权→菜单/API→回收→拒绝链路、完整性能及 HTTPS 部署尚未验收。09 已准备 10,000 条输入，**实际导入 0**；显式开发参数不能冒称真实 HR 分类、版本或可信签名。原员工 Excel、明文 NDJSON、个人配置、密钥和本机测试日志不属于本目录交付。

负责人五项已确认规则保持如下；不再列为待确认：

1. HR01 负责人选填、可后补，更新省略保留，OpenAPI 不列为必填。
2. 合并保留空源与去向，源禁接人员/任命；直属人及子部门转目标。源或祖先冻结须先显式解冻，子部门自身冻结保留。
3. 合并只比较和推进源 `hr_version`，不覆盖目标/人员；事务锁双方及受影响组织。
4. HR02 SUSPEND 保留授权、使旧会话失效；RESUME 不增角色、不复活旧 JWT；离职仅 REHIRE，且不恢复旧授权。
5. HR04 未来 `effectiveAt` 返回 422，不改账号/版本，不预约；到时使用新 `eventId`。HTTP 未新增 `occurredAt` 必填。

## 当前数据库与原文差异

数据库落地入口为 [空库 schema](../../db/01_schema.sql)、[数据库使用说明](../../db/README.md)；当前仅保留已确认的[部门合并迁移](../../db/migrations/20261016_01_department_merge.sql)。事件认领迁移已撤下。这里是索引，不是执行命令；已有库需核对后按迁移处理，不能重复空库建表。

Task20当前schema为 **23 表 / 18 触发器 / 49 CHECK / 227 列**。数据库说明 §2 的23张表与226个原有字段定义对应；当前仅保留已确认的部门合并去向一列。两列增量都出现在历史来源末尾，其中事件认领增量已撤回。本目录保留原文并在此说明差异：

| 原文位置与说法 | 当前 schema / 迁移证据与解释 |
|---|---|
| 数据库说明 §5：“本次只修改设计和建表脚本，未操作实际数据库”；“当前SQL包含49个CHECK、16个触发器。”（687、689 行） | 这是原文历史验证口径，不能用作当前环境证明。schema 现有 18 个触发器；迁移 01 增加 `tr_dept_merge_self` 与 `tr_dept_merge_insert`，原 16 个保留，CHECK 仍 49。现有 db/README 同时保存了首次 16 和增量 18 的时序 |
| §2.6 字段表未列 `merged_into_dept_id`，增量 693 行说明 nullable BIGINT / 自引用限制删除 | schema 第147行与迁移01定义该列；schema第600行起补索引、非级联自外键及两个触发器。迁移以 `NULL` 表达可空，schema 为 `DEFAULT NULL`；MySQL 的 NO ACTION 历史证据与限制删除语义区分记录 |
| §2.16 字段表未列 `claim_token`，增量 695 行说明 nullable CHAR(36) ascii_bin | 这是A未获批准的历史增量，Task20已移除schema/Mapper/服务依赖并删除迁移02，当前不得执行。非终态在线恢复明确拒绝 |

上述原Task11来源快照验证不含数据库运行。Task20本机结构清理与新源码测试证据另行保存，不能把历史静态检查当作新测试结论。四份历史源设计未修改，当前撤回项在此集中说明；source-manifest记录的旧schema/迁移哈希仅用于历史溯源。

原设计附加的“HR02/03/04 不属于任务06”等文字保留历史任务范围；当前人员业务及其限制以 [HR 集成说明](../task07-hr-personnel.md) 和 A18 对照为准，不能将旧任务边界解释为人员业务至今未实现。

## 实现、配置、数据与真实接入索引

| 阅读目的 | 当前仓库入口 |
|---|---|
| 工程/职责/协作基线 | [仓库 README](../../README.md)、[开发计划](../../DEVELOPMENT_PLAN.md)、[Git 规范](../../GIT_WORKFLOW.md)；本轮按用户要求同一 A 分支、最终统一 PR，本文未改团队规范 |
| 05 安全配置与 M1 请求 | [安全模板](../../config/task05-security.example.yml)、[账号/会话请求](../../requests/task05-accounts.http)；真实值保留本机，模板不提供生产默认放行 |
| 组织六路由、迁移、事件事务与恢复 | [组织集成说明](../task06-organization-integration.md)、[请求示例](../../requests/task06-organization.http) |
| HR02 四动作、HR03/04、状态与来源回收 | [HR 集成说明](../task07-hr-personnel.md)、[HR 请求示例](../../requests/task07-hr-personnel.http) |
| A18 响应清单、US01 筛选和姓名缺口 | [A 查询与契约对照](../task10-a-contract-alignment.md) |
| 09 输入准备及“导入0”边界 | [工具说明](../task09-employee-data.md)、[生成器](../../scripts/dev-data/prepare-employee-events.py)、[专属校验用例](../../scripts/dev-data/test_employee_events.py)；[部门开发数据说明](../../db/dev-data/README.md)、[受控组织映射](../../db/dev-data/department-source.json) |
| D 身份验签/重签、功能→C范围、审计的 A 接点 | [M1SecurityGateway](../../src/main/java/com/whatname/iam/common/security/M1SecurityGateway.java)、[正式缺依赖拒绝配置](../../src/main/java/com/whatname/iam/common/security/M1SecurityConfiguration.java) |
| D 可信 HR/OA、nonce 接入与组织/人员签名，C 管理根限制 | [DepartmentCollaboration](../../src/main/java/com/whatname/iam/service/department/DepartmentCollaboration.java)、[缺依赖拒绝配置](../../src/main/java/com/whatname/iam/service/department/DepartmentIntegrationConfiguration.java) |
| C 精确岗位来源/全部回收、复职核对、管理范围重审 | [HrCollaboration](../../src/main/java/com/whatname/iam/service/hr/HrCollaboration.java)、[缺依赖拒绝配置](../../src/main/java/com/whatname/iam/service/hr/HrIntegrationConfiguration.java) |
| A 公共事件和可信上下文 | [ExternalEventService](../../src/main/java/com/whatname/iam/service/event/ExternalEventService.java)、[TrustedEventContext](../../src/main/java/com/whatname/iam/service/event/TrustedEventContext.java)；构造 record 本身不代表可信，D 必须验证发行/权限 |

C/D 适配须加入调用者同一数据源事务，完成真实来源/资格完整性校验、精确回收或限制、`grant_revision`、最终事实及集合签名和审计；不得将这些写入拆成独立提交。HR/OA 服务签名头及原始 HTTP body 签名字节规则见完整 API §2.4，不能用业务幂等摘要代替；JWT 的 RS256 配置与 D 的 Ed25519 数据签名是不同用途。实际 C/D 类、序列化字节兼容和真实联调证据仍待接入确认，不编造方法名或声称已有适配。

## 校验与历史证据

本次重新做的是：四源执行前后 SHA-256、按映射重建字节、两处链接 diff、相对链接文件存在、OpenAPI 同字节和重复键拒绝解析、56/A18/非A38 操作及 269 本地 `$ref` 解析、schema/迁移词法比对，以及本机已知凭据/真实员工记录对照和私钥/令牌模式扫描。静态检查不称完整 OpenAPI 标准认证。临时证据保留仓库外；仓库内可根据 manifest 的映射、哈希、变换与 diff 审查。

67 项 MySQL/HTTP 历史证据来自任务10 **2026-10-16 13:42:23 +08:00**：M1 27 + HR 19 + 部门业务 18 + Mapper 3，0失败/错误/跳过。执行基线是 `d1edb38` 加当时任务10在途变更；后续任务09/10已本地提交，当前 HEAD 为本页基线。本次核对冻结报告/日志和相关文件哈希后复用该证据，**没有重新运行这67项**，也没有让新增文档自动获得运行验证。原 38 个非 A 操作保持源文件内容；本任务不验证 B/C/D 业务实现。

## 待集中讲解索引

完整通读留 A 全部完成后，用户掌握状态仍待讲解与复述验证。集中讲解可从本目录四份快照、manifest 和以上实现入口串起：①工作区/提交/push/统一PR/组织者合并的区别；②源哈希、目标哈希、路径变换 diff 如何证明完整可追溯；③契约定义、局部端口、替身测试、真实联调和性能验收各证明什么；④空库快照与迁移、历史验证与当前结构的区别；⑤结构化功能分解、Controller→Service→Mapper、事件事务及来源精确回收如何对应三门课程。没有把自动准备计作用户已掌握。
