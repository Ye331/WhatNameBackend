# 共享设计与 API 契约快照（A 工作分支）

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

A 的 18 个 HTTP 路由已有独立实现。Task17 已补充 US01 单字及任意中间片段姓名搜索，保留用户名/SG 子串与完整手机号 HMAC 匹配；现行检索、迁移和回填契约见 [Task17姓名搜索](../task17-name-search.md)。四份源快照及 source-manifest 保留原字节，其中 Task10 的“姓名未完成”说明是历史状态，由 Task17 实现补充覆盖；没有据此冒称团队或D批准新索引。原 `keyword=张` 示例、HTTP字段及18/56操作数量保持。

**真实 C/D 仍未联调完成**。A 的局部协作端口不是团队已冻结的真实 C/D 方法；必要生产依赖缺失时默认拒绝，允许替身仅在 `src/test`。完整可信员工导入、真实登录→授权→菜单/API→回收→拒绝链路、完整性能及 HTTPS 部署尚未验收。09 已准备 10,000 条输入，**实际导入 0**；显式开发参数不能冒称真实 HR 分类、版本或可信签名。原员工 Excel、明文 NDJSON、个人配置、密钥和本机测试日志不属于本目录交付。

负责人五项已确认规则保持如下；不再列为待确认：

1. HR01 负责人选填、可后补，更新省略保留，OpenAPI 不列为必填。
2. 合并保留空源与去向，源禁接人员/任命；直属人及子部门转目标。源或祖先冻结须先显式解冻，子部门自身冻结保留。
3. 合并只比较和推进源 `hr_version`，不覆盖目标/人员；事务锁双方及受影响组织。
4. HR02 SUSPEND 保留授权、使旧会话失效；RESUME 不增角色、不复活旧 JWT；离职仅 REHIRE，且不恢复旧授权。
5. HR04 未来 `effectiveAt` 返回 422，不改账号/版本，不预约；到时使用新 `eventId`。HTTP 未新增 `occurredAt` 必填。

## 当前数据库与原文差异

数据库落地入口为 [空库 schema](../../db/01_schema.sql)、[数据库使用说明](../../db/README.md)；已有库的两项迁移为 [部门合并](../../db/migrations/20261016_01_department_merge.sql) 和 [事件认领](../../db/migrations/20261016_02_external_event_owner.sql)。这里是索引，不是执行命令；已有库需核对后按迁移处理，不能重复空库建表。

本次仅对文件做静态结构比对：schema 为 **23 表 / 18 触发器 / 49 CHECK / 228 列**。数据库说明 §2 的 23 张表与 226 个原有字段定义对应；另外两列仅记在文末增量，没有回填字段表。本目录保留这些原文差异：

| 原文位置与说法 | 当前 schema / 迁移证据与解释 |
|---|---|
| 数据库说明 §5：“本次只修改设计和建表脚本，未操作实际数据库”；“当前SQL包含49个CHECK、16个触发器。”（687、689 行） | 这是原文历史验证口径，不能用作当前环境证明。schema 现有 18 个触发器；迁移 01 增加 `tr_dept_merge_self` 与 `tr_dept_merge_insert`，原 16 个保留，CHECK 仍 49。现有 db/README 同时保存了首次 16 和增量 18 的时序 |
| §2.6 字段表未列 `merged_into_dept_id`，增量 693 行说明 nullable BIGINT / 自引用限制删除 | schema 第147行与迁移01定义该列；schema第600行起补索引、非级联自外键及两个触发器。迁移以 `NULL` 表达可空，schema 为 `DEFAULT NULL`；MySQL 的 NO ACTION 历史证据与限制删除语义区分记录 |
| §2.16 字段表未列 `claim_token`，增量 695 行说明 nullable CHAR(36) ascii_bin | schema第359行与迁移02含可空 `CHAR(36) CHARACTER SET ascii COLLATE ascii_bin`；受控恢复更换认领令牌，非自动超时接管 |

本次没有连接数据库、重跑 DDL、迁移或业务测试；环境是否落地引用 [组织集成说明](../task06-organization-integration.md) 的既有验证时点。上述词法核对不等于 SQL 执行验证或完整数据库审计。源设计未修改，差异在此集中说明。

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
