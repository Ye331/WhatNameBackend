# C2-03 HR 调岗、离职与复职协作

**C2-07 当前入口：**后续 OA、批量恢复及 GR04 已实现。本文保留 C2-03 当时测试数字与下一部分说明；最新组合结果见 [联合验收](C_STAGE2_TEST_REPORT.md)，生产认证/A 最终适配仍按 [交付说明](C_STAGE2.md) 保留阻塞。

**2026-10-19：**C 专用 HR 事务参与者已接入 A 当前 HR Service。生产 HR 请求仍依赖 A/D 服务认证及 A 最终数据安全适配，默认入口继续拒绝。测试结果和源码基线见 [交接](C_STAGE2_HANDOFF.md)。

最终完整回归于 2026-10-19 11:00:20（Asia/Shanghai）BUILD SUCCESS：**284 通过、122 跳过、0 失败/错误**；其中本次 **HrGrantCollaborationIT 39 项真实 MySQL 测试全部通过**。跳过项及测试替身的准确范围见交接，不计为生产端到端完成。

## 责任和入口

| 模块 | 本次责任 |
|---|---|
| A | HR02/03/04 参数、人员生命周期和 HR 版本；事件内容去重/状态；部门、状态、session_version；最终身份重签及 HR 业务审计 |
| C | 专用来源回收、有效角色集合重算、grant_revision；离职管理员/清单清理、调岗范围重审、复职残余资格检查；USER_GRANTS 与管理员签名及业务证据 |
| D | 生产可信 HR 服务身份/请求范围/签名/时间窗/nonce；事务内服务权限重查；Ed25519 规范数据签名和审计 seal/原子追加 |

`HrGrantCollaboration implements HrCollaboration` 是内部事务参与者，不是 HTTP 或认证入口。保留原有 `revokePosition`、`revokeAll`、`requireRehireEligible`、`departmentChanged`；接口增加两个默认拒绝的方法：

```java
void prepare(TrustedEventContext actor, String eventId, String eventType, String employeeNo);
void complete(long userId);
```

调用方变更仅为 A `HrService`：HR_EMPLOYEE / HR_TRANSFER / HR_TERMINATION 的回调在组织/人员读取前 prepare，在最终身份签名及 A 审计后 complete。未升级的自定义适配不能悄悄跳过这两个阶段。原缺失依赖配置仍保留默认拒绝；全应用装配真实 C 参与者。

无新表、字段、迁移、HTTP 或对外 DTO。`ExternalEventMapper.lock` 仅增加既有事件类型/目标字段的内部投影，供 C 绑定正在处理的事件及工号；不是新的认领或在线接管协议。

## 锁序与提交边界

1. A/D 认证请求，A 在独立短事务保存接收/内容摘要。非终态恢复规则保持不变。
2. A 开始既有 SERIALIZABLE 业务事务，先锁事件行。A 事务内重新验证服务权限，再进入 C prepare。
3. C 确认原事件为 PROCESSING 且类型、USER 目标、工号一致。取得 **管理员→部门→角色→委派清单→全部用户（主键序）→D 应用/菜单/API/映射→目标授权来源/有效关系** 的统一锁；先验组织、身份、任命、清单、角色和目标授权集合签名，检查有效来源与有效关系一致。事件行检查和上下文结构检查均不替代 D 认证。
4. A 在上述锁之后读取组织/人员并再验证服务权限，校验 HR 次序、来源及生命周期。C 按本节规则回收，A 持久化人员变更；A SQL 不覆盖 grant_revision。
5. C 来源变化一次推进 grant_revision，签 USER_GRANTS 并写来源审计。此时不签人员身份，避免把尚未完成的部门/状态/sv/hr_version 当最终身份。
6. A 重读当前用户（含 C 的 grant_revision），完成 USER_IDENTITY 最终签名及 HR 业务审计，然后 C complete。C 检查人员/授权差量，复用 C2-02 重审及递归清单收缩，签全部变化管理员的 ADMIN / ADMIN_DELEGABLE_ROLES。
7. A 更新事件 APPLIED；提交前 C 的两个参与上下文检查阶段完结、最终用户/管理员/授权事实及签名。所有业务事实、签名、关键审计、APPLIED 一起提交或回滚。

接收记录和回滚后的 REJECTED/PENDING_RECOVERY 记录仍是 A 的独立短事务，不能误称整个外部事件只有一笔事务。INTEGRITY_FAILURE 属服务端故障，按 A 既有语义保留 PENDING_RECOVERY，不擅自改为可直接重试或在线接管。

两个 C 上下文均绑定当前事务，挂起时解绑、恢复时绑定，提交/回滚后清除。普通/只读外层事务、无接收事件、错目标、重复阶段、漏完结、完成后再次改变事实均拒绝。当前仍使用 C2-02 全局 M5 串行化锁范围，未作组织规模吞吐承诺。B 资源写入口的角色优先锁序及签名适配仍待 B 对齐。

## 业务规则

- **HR03 及 HR02 部门变化：**只回收有效且 `source_type=DEPT_POSITION AND source_dept_id=旧部门` 的来源；同角色全部匹配旧岗位来源一并回收，MANUAL、OA_APPROVAL、其他部门独立来源保留。仍有支持的角色关系保留；最后来源消失才删除关系。不授予新岗位权限，不因调岗增加 session_version。
- **HR04 离职：**清空全部有效来源和有效角色关系，不依赖操作者管理员身份、可授权清单、角色启用或普通管理角色资格。管理员任命置无效，清理本人拥有/提供的委派及相同角色的递归下游；独立合法委派保留。即使本人任命此前已失效，残余清单仍需清理。
- **管理员移动/范围变化：**复用 C2-02 比较前后范围，撤销本人归属变化及接收范围扩大的部门管理员，保留历史管理根和等级，需显式重任命。新增 HR 人员也触发接收范围重审；一级全局任命不因自然增长扩大部门根。
- **复职：**在原离职状态下，确认没有有效来源、有效关系、有效任命及本人拥有/直接提供的委派；任何残余拒绝，不借复职修复。A 增加 session_version，C 不复活历史来源、关系、任命或清单。
- **revision：**一次来源集合变更只加一次 grant_revision；无匹配来源、空集合离职及单纯资格重审不制造授权 revision。每个受影响管理员的 delegable_revision 同次重审至多加一次。新建 HR 用户的空集合由 C 首次签名，已有用户缺签绝不补签。
- **历史：**来源行保留，改 status / revoked_at，`revoked_by=NULL` 表示服务操作；不填管理员 ID。任命行保留，资格审计保存变更前后清单。D 当前 USER_GRANTS 规范签有效集合；不声称它认证了所有既有失效来源的历史元数据。

## 审计和故障验证

C 来源操作使用 `HR_POSITION_REVOKE` / `HR_GRANTS_REVOKE_ALL`、`C_HR_GRANT_CHANGE_V1`；管理员重审使用 `ADMIN_HR_REVIEW`、`C_HR_ADMIN_REVIEW_V1`。`user_id=NULL`，`target_type=USER`，external_event_id 关联原事件。来源证据保留前后 revision/来源/关系、撤销来源 ID、原因及 HR sourceSystem/clientId/requestId/eventId/eventType；D 追加元数据及 seal。D 元数据的 source_system 仍为 IAM，HR 原因看 origin，不修改 D 原含义。

测试 `HrGrantCollaborationIT` 使用隔离 MySQL 8.0.46 固定摘要镜像、真实 A HrService/ExternalEventService、生产 C Service/Mapper、真实 D DataSignatureService/AuditService 和 A JWT 签发。仅在显式构建的内部测试服务中替换服务认证和 A 最终数据安全边界；未覆盖生产认证成功或 AU01 登录。生产认证 bean 保持原样并有拒绝断言。

用例包括精确来源集合、HR02/03 同规则、停用角色、无操作版本、离职/复职、残余资格、事件幂等/内容冲突/乱序、新用户空集合、管理员范围/递归委派、可验证来源审计、缺签/篡改/关系不一致、阶段协议、最终事实篡改、事务挂起/恢复及真实数据库锁等待。SQL 拦截器在 INSERT/UPDATE 后注入 C 集合签名、A 最终身份签名、管理员第二签、C 来源审计、C 管理员审计、A HR 审计、APPLIED 故障；断言业务/签名/成功证据全部回滚。

```powershell
$env:JAVA_HOME='C:\Users\ZYD\.jdks\ms-21.0.12.1'
.\mvnw.cmd -B "-Dspring.config.import=" "-Dtest=HrGrantCollaborationIT" test
.\mvnw.cmd -B verify -Pintegration-tests "-Dspring.config.import=" "-DtrimStackTrace=true"
git diff --check
```

## 生产依赖和后续接点

1. **D/A 服务认证：**`DepartmentCollaboration.authenticateHr/verifyTrustedEvent` 当前仍拒绝。D 须交付客户端/key/HR 类型与目标范围绑定、请求签名、时间窗、nonce 防重放、可信上下文，以及等待业务锁后的当前服务权限重查。不得以测试 clientId、固定头或构造 TrustedEventContext 开放生产。
2. **A 最终适配：**`ManagementDataSecurity` 仍为缺失依赖拒绝。A 应装配 D USER_IDENTITY 验签/最终签名及 HR 审计；A/C 共有事务和最终重读接点本次已就位，测试适配不等同生产交付。
3. **C2-02 既有依赖：**管理角色等级规则仍默认拒绝；B 写入、超级用户受控初始化/恢复及自管资源联合验收继续按生命周期交接。
4. **C2-04 起点：**复用事件事务和服务身份原则实现 OA；本次 HR 参与者限制 HR 三类人员事件，不能当 OA 绕行入口。无 OA、批量恢复或 GR04 新实现。
