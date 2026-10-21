# C2-04 OA 批准事件接入

**C2-07 当前入口：**批量恢复和 GR04 已在后续交付，本文结尾的下一部分说明及测试数字为当时记录。最新 [联合验收](C_STAGE2_TEST_REPORT.md) 和 [交付说明](C_STAGE2.md) 保留真实 D 服务认证缺口，不用测试提供者关闭该依赖。

2026-10-19，分支 `feat/c-stage2-integration`。C 已实现 OA01 内部业务；生产 D 请求签名认证仍未交付，默认 `INVALID_SERVICE`，不能视为生产端到端完成。最终命令、计数和未提交源码基线见 [交接](C_STAGE2_HANDOFF.md)。

最终完整回归于 **2026-10-19 15:55:03（Asia/Shanghai）BUILD SUCCESS**：348 通过、122 跳过、0 失败/错误；本次 **OaDecisionIT 63 项 MySQL 集成测试及 1 项生产默认拒绝测试全部通过**。跳过项和认证替身的范围均已在交接单独记录。

## OA01 与职责

`POST /api/v1/integrations/oa/decisions` 使用现有 OA01 JSON 和四个响应字段：eventId、processingStatus、grantSourceId、idempotentReplay。没有新表、字段、外部接口、事件认领字段或在线接管。

| 模块 | 责任 |
|---|---|
| D | 原始 method/path/query/body 的 Ed25519 请求签名、client/key/服务类型绑定、±300 秒时间窗、nonce 独立持久化防重放；等待业务锁后复核本请求证明和当前 key/状态；规范数据签名、审计及 seal |
| A | 既有 ExternalEventService 接收/内容摘要/状态；事件锁、SERIALIZABLE 业务事务、APPLIED 原子提交；失败短事务；保留非终态不可在线接管的规则 |
| C | 严格审批内容、当前服务角色/部门/操作范围、目标及角色资格；来源去重、精确回收、有效关系和 grant_revision；双签名及资格/前后事实审计 |

C 新增 D 内部接点 `TrustedOaCaller.authenticate(request, rawBody)` / `verifyTrustedEvent(context, eventType)`，生产缺省均拒绝。现有 A 恢复授权对应 `authorizeRecovery` 默认拒绝；即使提供授权，A 仍拒绝非终态接管。这是本分支集成接点，**不冒充 D 已交付实现或团队已完成签收**。

`authenticate` 必须返回与本次请求证明绑定的上下文，包含 sourceSystem=OA、clientId 和 requestId。构造 TrustedEventContext、提交 clientId 或数据库存在一条服务记录都不等于认证。D 不能仅凭这些字符串在 `verifyTrustedEvent` 放行；还需验证原始请求证明、密钥绑定及当前资格。

A 的事件服务新增注入该 OA 接点的六参数构造，保留原五参数构造；五参数路径缺少 OA 提供者时拒绝 OA，HR 仍调用原 DepartmentCollaboration。部分 A 单模块测试夹具显式适配 D 接点；没有把 OA 转送 HR 信任实现或修改 HR 对外行为。

## 输入与执行规则

- 原始请求体直接交给 D，再严格解析 JSON。重复键、尾随 JSON、未知字段、数字 ID、超出 long 的 ID、前导零、非字符串批准时间、无时区时间、非 APPROVED、非精确 GRANT/REVOKE 均拒绝。请求体限 16 KiB。
- GRANT 不能出现 grantSourceId/originalApprovalId。REVOKE 两者必须且只能出现一个，显式 null 不当作省略；所有 ID 为正十进制字符串。原申请人字段不在现有 OpenAPI 中，不擅自新增；原申请人失权/离职不影响服务当前范围内的已批准操作。
- 当前 `sys_service_identity` 必须为启用、app_id=NULL、allowed_scope=OA_DECISION，且 SERVICE_IDENTITY 可验签。JSON 配置必须是字符串数组；ID 严格检查，不接受数字、通配符、角色名或模糊范围。
- allowed_role_ids、allowed_operations、allowed_dept_ids 全部检查。部门根按当前组织子树判定，合并根或合并祖先不能承接服务范围；不把历史路径文本当当前范围。
- 新写入要求角色启用且 role_type 为 1/2，超级角色即使在允许数组中也拒绝。管理角色的 OA 资格来自显式服务配置，不猜人类管理员等级，不改 C2-02 的 `canDelegate` 默认拒绝；授予管理角色不等于任命管理员。
- GRANT 另要求目标正常、归属及祖先链未冻结/合并。REVOKE 可处理暂停/离职或冻结对象的历史来源，但仍要求当前服务范围和启用角色；它不是 HR 强制清理入口。

## 锁序与原子性

从无外层事务的入口调用 A 事件服务。顺序如下：

1. D 认证原始请求并独立消费 nonce；A 验证 OA 类型/请求证明，在独立短事务接收事件。
2. A SERIALIZABLE 业务事务锁原事件行。C 绑定 PROCESSING / OA_DECISION / USER / targetUserId，取得 **管理员→部门→角色→清单→目标用户→D 资源/映射→服务身份→目标来源/有效关系** 的统一锁。
3. C 当前读并验签组织、部门、目标身份、管理员/角色、服务配置；服务身份锁后调用 D 再验证本请求证明及当前权限。D 不得把入口短事务的服务行锁持有到上述 C 业务锁之前。
4. C 验 USER_IDENTITY / USER_GRANTS，复用 GrantOperations 的集合一致性检查，要求有效来源支持集合与有效关系严格相等；缺失或篡改先拒绝，不自动补签。
5. C 修改来源及必要关系，每次集合变化只推进一次 grant_revision，使用既有 DGrantIntegrity 同事务重签 USER_IDENTITY 和 USER_GRANTS；这里没有 A 人员状态变更需要等待，C 可完成最终双签。
6. D 追加 OA_DECISION 关键审计并密封，A 写 APPLIED。事实、两份签名、成功证据、APPLIED 一起提交或回滚。

接收记录以及回滚后的 REJECTED/PENDING_RECOVERY 属于原 A 独立短事务。服务端完整性、签名、数据库、审计故障仍保留 PENDING_RECOVERY；不把失败回执误当成功，不自动接管或重做。

## 两层去重与精确回收

| 情况 | 当前处理 |
|---|---|
| 同 sourceSystem/eventId、同内容 | A 返回保留结果，C 不再写来源/revision/成功审计；返回层 idempotentReplay=true |
| 同事件键、不同内容 | IDEMPOTENCY_CONFLICT，原结果不覆盖 |
| 不同 eventId，同用户/角色/approvalId | 按既有来源唯一键当前读，复用同 OA 来源，不增加 revision；该新事件记录 changed=false 审计并 APPLIED |
| 已撤销来源的原审批重发 GRANT | DUPLICATE_RESOURCE，不复活历史；再次授权需新的批准单 |
| 新批准单，同用户/角色 | 新的独立 OA 来源；已有有效角色关系不重复插入 |
| REVOKE 按 grantSourceId | 必须属于同用户、同角色且 source_type=OA_APPROVAL，不命中人工或岗位 |
| REVOKE 按 originalApprovalId | 仅按原批准单查 OA 来源；绝不回退到本次回收 approvalId |
| 撤销后仍有其他合法来源 | 保留有效角色关系 |
| 最后来源撤销 | 删除有效关系，保留全部来源历史，双签可验证空集合 |
| 已失效来源再次 REVOKE | 无集合变更、不增 revision；不同事件保留真实 changed=false 事件审计 |
| PROCESSING/PENDING_RECOVERY | 按 A 既有冲突/不可用语义拒绝；无新恢复 HTTP、认领或在线接管 |

审批来源查找复用现有数据库唯一键的排序规则，包含数据库认定等价的大小写等情形；没有单独引入 Java 字符串查重或未经迁移的排序规则变更。

重放返回原执行结果的事实字段，仅按本次调用设置 idempotentReplay 标记；不改写 A 已持久化的结果。返回前 C 在独立 SERIALIZABLE 事务复核当前服务范围，防止服务撤权后用重放读取越界结果。该检查取得行锁但不写授权、版本、签名或审计；不要求当前目标仍有原授权，APPLIED 不代表当下权限仍有效。当前服务范围已撤销时可拒绝返回，原 APPLIED 保持不变。

服务来源的 granted_by/revoked_by 均为 NULL，授权事实不填假管理员。已撤销来源行及历史不删除；D 既有 USER_GRANTS 仅签有效集合，不能用它倒称所有旧失效来源元数据都可信。

## 审计与验证边界

OA_DECISION / C_OA_CHANGE_V1 记录外部事件、服务来源、批准内容、实际来源 ID、原批准单、changed 标志、服务范围、当时目标/角色资格及来源/关系/版本前后事实。actor user_id=NULL、target_type=USER；D 元数据 source_system 保持 IAM，OA 在 origin.sourceSystem。批准原文、请求签名、nonce、密钥、密码和人员密文不进入证据。

`OaBoundaryTest` 验证生产默认服务接点拒绝伪造头。`OaDecisionIT` 通过 MockMvc 驱动真实 Controller/MVC（无外部网络），使用 A 事件编排、C 当前范围/授权算法、D SERVICE_IDENTITY/用户数据签名/审计及固定摘要隔离 MySQL；**只替换 D 尚未交付的服务请求认证**。配置范围不是替身，SQL 故障发生在真实持久化之后。latch 和 performance_schema.data_lock_waits 验证真实等待后的当前服务撤权及同审批并发去重。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B "-Dspring.config.import=" "-Dtest=OaBoundaryTest,OaDecisionIT" test
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

测试数以最终完整运行的交接统计为准。当前仍不能宣称真实请求签名、key 绑定、防重放/时间窗、nonce 在业务回滚后仍不可重放、生产服务请求→OA→授权→鉴权的端到端成功。

## 交付给 D / A 的依赖

- D 为 TrustedOaCaller 提供生产适配：API 2.4 五个签名头、原始路径及查询串、原始 body SHA-256、Ed25519、公钥指纹/客户端/服务类型绑定、±300 秒及独立 nonce 唯一插入/保留 10 分钟；不能使用示例头或仅构造上下文放行。
- D 等待 C 统一业务锁之后重新验证当前 key、服务状态和本次原始请求绑定的证明；authenticate 独立消费一次 nonce，verifyTrustedEvent 复核既有请求证明而不重复消费 nonce。C 继续锁并验签受控范围，双方不能交换成服务行→管理员的反向锁序。旧的跨请求允许结果不能缓存复用。
- A 确认新增 OA 信任路由与已有事件状态边界；既有终态恢复经独立授权可重放，非终态仍不接管。原 A 单模块相关夹具只适配新信任类型，不冒充生产认证。
- 当前阶段无 C2-05 批量恢复和 GR04 新功能。下一部分从已有批量逐项事务、持久状态及接点继续；保留 C2-01～04 全部未提交改动和证据。
