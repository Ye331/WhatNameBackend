# C2-06：授权历史 GR04

**C2-07 当前入口：**可运行联合回归已完成，本轮包含真实 D 2001 人，437 通过、122 跳过、0 失败/错误。最新组合数字及外部阻塞见 [联合验收报告](C_STAGE2_TEST_REPORT.md)、[交付说明](C_STAGE2.md)；本文 C2-06 分段结果继续保留且不与本轮累加。

## 查询与事实来源

`GET /api/v1/grants/history` 已接入真实 C 当前资格、A JWT、D 历史证据验签与 MySQL。GR04 表达来源的历次授予/回收；GR05 表达来源状态。同一来源授予后再回收，在 GR04 中有两条变更，在 GR05 中仍是一条失效来源。

查询直接读取既有 `sys_operation_log` 中 C 已实现的五种操作，复用 D `AuditQueryService` 的原有封签/元数据验证方法。没有新增日志表、历史表、字段、迁移或公共响应字段，不读取当前授权来源、有效角色关系或批量结果来生成历史。

| 生产者 | 操作 / 已封存结构 | 历史投影 |
|---|---|---|
| 人工、岗位、持久批量 | USER_ROLE_GRANT / USER_ROLE_REVOKE，C_CHANGE_V1 | 前后来源差集中的一个 GRANT / REVOKE |
| HR 调岗 | HR_POSITION_REVOKE，C_HR_GRANT_CHANGE_V1 | 旧部门岗位来源逐条 REVOKE |
| HR 离职 | HR_GRANTS_REVOKE_ALL，C_HR_GRANT_CHANGE_V1 | 所有实际撤销来源逐条 REVOKE，含停用角色的历史来源 |
| OA | OA_DECISION，C_OA_CHANGE_V1 | changed=true 的一个 OA 来源变化；changed=false 不产生变更 |

HR 的一条审计可以展开为多条来源记录，共用 eventId。OA 同事件重放、无变化审批及批量失败/重复调度不增加历史变更。批量具体 batchId/itemId 保留在原 `authorization.origin.kind=PERSISTED_BATCH` 证据中。

## 公共契约

响应 `data` 为 `items/page/pageSize/total`；每项严格只有以下六个字段：

```json
{
  "eventId": "123",
  "userId": "101",
  "roleId": "5",
  "grantSourceId": "456",
  "operation": "REVOKE",
  "occurredAt": "2026-10-19T10:00:00+08:00"
}
```

所有 ID 为十进制字符串。eventId 是既有操作日志主键，与 D LG04 一致；外部 HR/OA 事件 ID 留在封存元数据中。原因、来源类型/引用、当时资格、批量关联均由 D LG04 `GET /audit/evidence/{eventId}` 返回完整证据，该接口另需 IAM_AUDIT_VIEW 和 D 自己的当前范围，GR04 不代替 LG04 的授权。

userId、roleId 可选，必须为正十进制 ID；page 默认 1，范围 1～1000000；pageSize 默认 20，范围 1～500。OpenAPI 已有的 from/to 可选，要求带秒和时区的 RFC3339 时间，支持小数秒；按瞬时值执行闭区间筛选。非法或反向区间返回 400 INVALID_ARGUMENT。请求示例见 [c-stage2-history.http](../requests/c-stage2-history.http)。

occurredAt 来自封存来源的 createdAt（授予）或 revokedAt（回收），按现有数据库业务时区 +08:00 输出。OA 批准时间及外部事件时间不等同于实际授权变更时间。原操作日志 created_at 没有纳入旧封签，不能把它冒充已验签的发生时间。本实现不使用该列做时间筛选。

排序为操作日志 ID 倒序，同一事件内来源 ID 升序；与写入顺序一致，不声称跨事务提交顺序或按历史时间重排。来源展开、当前范围、角色和时间筛选全部完成后才计数和选页。总数按来源变更计数；空匹配、未知用户/角色、越过尾页均返回空 items，不泄露范围外数量。

## 当前范围与一致性

每次请求用独立只读 REQUIRES_NEW / REPEATABLE_READ 事务取得当前会话、有效管理员任命和 IAM_GRANTS_VIEW；复用 C M5 当前部门树、任命和目标身份签名检查。查询同一事务内的范围、证据、total、page 使用一致快照，外层旧读事务不能借给 GR04 旧范围。下一次请求重新判断当前资格和目标部门。

一级管理员按既有全局范围读历史；其他管理员只见目标用户**当前**部门在其范围内的记录，目标当前不存在则不放行。历史授权人如今失去任命、离职或处于范围外，不会抹除合法历史；目标角色后续停用也不改变旧事件。查询仍会校验当前资格所依赖的签名事实，签名失真不构成可用范围。

先判断可见性，再解析历史内容。范围外坏审计既不曝光也不计数；可见坏审计即使不在请求页，也拒绝整个查询，防止生成看似可信的 total。用户筛选在候选 SQL 完成；角色和时间必须在验证、来源展开后匹配。

候选每次最多读 200 个操作日志，按主键游标扫描。只保留当前候选块与响应页，不把全部历史装入响应集合；精确验签总数仍需遍历全部候选，成本随历史增长，尚未作生产规模性能承诺。不同 HTTP 页是各自的新快照，期间有新事件或范围变化时页位置可能改变；没有新增跨请求快照令牌。

## 完整性与旧数据

D 继续负责旧 seal 与 actor/request/operation/target 等元数据绑定。C 额外核对 sourceSystem/externalEventId/status 与封存元数据一致，并校验支持的 schema、用户、来源、角色、前后集合、revision 和实际变更。未知结构、缺签、篡改、矛盾快照或复活/删除来源均返回 503 INTEGRITY_FAILURE。没有自动补签、改写旧日志或从当前状态猜测历史。

当前来源有记录但没有相应审计时，GR04 不制造事件；空结果仅说明支持且可见的留存审计中没有匹配，不证明旧系统从未授权。已识别的可见旧审计如果缺少可信证据则明确拒绝。DB 权限/历史触发器继续保护既有审计；GR04 沿用 D 的 eventId 和封签语义。

## 验证与交付边界

专项测试包含真实人工/岗位 GrantService、持久 BatchService/Worker、实际 A HR/事件/C/D、实际 OA Controller/A 事件/C/D，以及独立封存夹具的跨页/旧格式/篡改验证。HR/OA 请求认证和 A 最终数据安全仍使用原专项测试的显式适配，不冒充生产外部依赖已交付；GR04 自身查询使用真实 A JWT 和 C/D 权限链路。

2026-10-19 20:43:17（Asia/Shanghai）续验 BUILD SUCCESS。历史集成 53 项、投影单测 4 项、实际 HR 查询 2 项、实际 OA 查询 1 项共新增 60 项均通过；全部回归按同一冻结源码分段续验、套件去重，最终 436 通过、122 环境跳过、0 失败/错误。另排除未改动的 batch-scale 1 项，不计为通过或跳过；其 C2-05 真实 D 2001 人证据保留，其他批量恢复/独立 JVM 重启及既有适配路径 2001 人已在本次下午运行通过。

实际命令、分段报告来源、边界及源码 SHA-256 见 [当前交接](C_STAGE2_HANDOFF.md) 顶部与 `target/c2-06-evidence/`；最终统计为 `final-statistics.json`，不是中断时的 pending 快照。源码集与内容在两次运行之间及最终收集时均与冻结清单一致，前序文件保留，`git diff --check` 通过。

下一部分为 C2-07 联合验收。A AU01/最终数据安全、生产 HR/OA/组织认证、B 管理角色规则及资源写入口、超级用户初始化/恢复/自管资源依赖继续按总计划处理。dryRun 仍是可选增强，当前明确拒绝。
