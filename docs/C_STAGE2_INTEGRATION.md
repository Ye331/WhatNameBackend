# C2-01：A/D 真实接入与事务基线

**C2-07 当前入口：**HR、OA、批量恢复及 GR04 的 C 实现均已接续交付，本文正文保留 C2-01 当时边界。组合状态与最新 A/B/D 依赖以 [交付说明](C_STAGE2.md)、[联合验收](C_STAGE2_TEST_REPORT.md) 为准；不要将下文历史“未实现”当作当前状态。

**C2-03 接续：**HR 人员流程已与 C 管理写入统一锁序，C 专用来源回收、离职资格清理、复职检查和最终提交协议见 [HR 接入](C_STAGE2_HR.md)。本文下方“HR 授权回收未完成”保留为 C2-01/02 历史边界；生产认证及 A 最终数据安全适配仍未交付。

2026-10-19 接续：C2-02 已增加合并生命周期、组织事件前置锁与重审，详见 [生命周期接入](C_STAGE2_LIFECYCLE.md)。本文 HR/OA 未来锁序讨论中，组织 sync/merge 的前置阶段现已实现；HR 授权回收、OA、生产服务认证仍未完成。管理角色规则经用户确认仍等待 B/C 对齐并保持拒绝。

日期：2026-10-18。代码基线 `805db4c`，开发分支 `feat/c-stage2-integration`。本文描述 C 的生产适配；最终测试结果与未完成依赖见 [交接](C_STAGE2_HANDOFF.md)。

## 真实链路与完成边界

`C Controller → GrantService/AdminService → A JwtTokenService → C M5 锁与规则 → D 当前会话/功能 → C Mapper → D 双签名 → D AuditService → 同一事务提交`。

- HTTP 只读取请求的 Authorization，使用 A 的 RS256 验证器。身份阶段不读取数据库，不在 M5 之前取得用户锁或建立 RR 快照。原始请求中的完整凭据保持可用，锁后重新验 JWT 有效期及主体，再由 D `currentUser` 校验账号、设密、session_version 和 jti 注销记录，随后 `requireFunction`。
- `currentUserId` 返回的 ID 不能独立代表会话有效，也不能代替任命、对象范围或可授权清单。持久任务之外的无请求调用明确拒绝。
- 后台由 `BatchWorker` 在锁定持久项后建立事务范围的 `PERSISTED_BATCH` 上下文；当前 actor 取自持久任务，不依赖请求 ThreadLocal、不要求已结束的浏览器会话仍有效。每项仍重新校验当前账号、功能、任命、范围、角色和资源。未增加恢复、重试或调度能力。
- `CExecutionContext` 仅保存本事务的身份依据及已经通过的功能检查证据，随事务挂起/恢复，提交或回滚后清除。它不缓存跨请求允许结果，也不能用于认证 HR/OA。
- `AdminIntegrationConfiguration` / `GrantIntegrationConfiguration` 默认装配生产适配。通过 ObjectProvider 延迟取得 A/D 服务，部分上下文缺少服务时仍明确拒绝；不存在测试头或默认放行开关。管理角色资格仍默认 false，属于 C2-02。

**完整登录端到端尚未完成。** A 的 `M1SecurityConfiguration.unavailableManagementDataSecurity` 仍拒绝身份验签、审计等调用，`AuthService.login` 因而不能构成已验收的生产登录链路。本次测试使用随机测试密钥调用真实 A `JwtTokenService.issue` 建立凭据，不伪称是 AU01 登录。C 的 HTTP 授予、D 菜单/内部 API 决策、精确回收及随后拒绝可以独立验证。

## 签名与审计

| 适配器 | 处理 |
|---|---|
| `DAdminIntegrity` | 验证组织全集、部门、加载的用户身份、管理员任命与清单、角色完整规范数据；变更后对全部 changedAdminIds 重签 ADMIN / ADMIN_DELEGABLE_ROLES |
| `DGrantIntegrity` | 变更前验证 USER_IDENTITY / USER_GRANTS；grant_revision 变化后同事务重签二者，包含最后来源回收后的空集合 |
| `DAdminAudit` / `DGrantAudit` | 调用 D `AuditService.change`，同事务追加并密封白名单证据；不异步、不另开证据事务、不吞失败 |

端口投影仅用于确定对象 ID 和审计事实；验签与重签一律由 D 按完整规范记录读取，不对 C 最小投影另造签名协议。读取缺签、篡改或集合不一致直接拒绝，不补签。

证据格式 `schema=C_CHANGE_V1`：

- `authorization`：permission、origin（HTTP_SESSION 或 PERSISTED_BATCH）、身份/授权签名引用、变更前授权及角色依据、当时菜单/祖先和应用的观察事实。resources 是观察事实，不冒称每个停用资源都被 D 用于允许判定。
- HTTP origin 保存 userId、sessionVersion、expiresAt、jtiDigest；不保存完整 JWT 或原始 jti。批量 origin 保存 batchId、itemId。
- 授权事件保存 roleId、sourceId、reason、authority、before/after；包括来源 ID、类型、引用、部门、有效/回收状态、操作者和时间、有效角色集合及版本。
- 管理事件保存 changedAdminIds、reason、before/after 的任命/清单、人员部门、组织及角色事实。递归收缩的全部受影响管理员保留在证据中。
- `targetType=USER`、targetId 为被操作用户，兼容 D 现有审计范围投影；operation 为既有 USER_ROLE_GRANT/USER_ROLE_REVOKE/ADMIN_*。GR04 未实现，C2-06 可从这些来源级事实读取，不能用当前状态重建历史。
- D 负责追加 metadata 与 detached seal。自回收使用变更前保存的功能证据，不在失权之后重新鉴权来生成成功审计。

## 事务与锁序

C 单人管理/授权服务由本次调用开启事务；身份适配拒绝参与外层已有事务（包括外层只读事务），返回 AUTH_STATE_UNAVAILABLE，不用 REQUIRES_NEW 把调用方业务拆开。批量提交同样要求外层无事务；既有 worker 每项继续使用 REQUIRES_NEW。读取端点使用自己的事务内一致视图；已有事务中的 A/B 协作仍走 MANDATORY 的 AdminPolicyService，不通过 C HTTP 服务借用身份入口。

| 路径 | 当前锁顺序与状态 |
|---|---|
| C 管理/单人授权写 | sys_admin 按 user_id → sys_dept 按 id → sys_role 按 id → 委派清单 → actor/target 用户按去重 ID 排序 → 应用 → 菜单 → API → 角色菜单映射 → 菜单 API 映射 → 目标授权来源/关系 → 签名与审计 |
| C 批量逐项 | 本项 FOR UPDATE → 上述 C 顺序 → 业务、双签名、审计、本项 APPLIED 同事务；失败项记录仍在业务回滚后独立保存 |
| A 管理入口 | 先调用既有 C M5 完整 actor/target；生产完整性适配现在同时取得上述资源锁，再回到 A 的会话/组织读取。A 自己仍必须遵守新事务及完整身份/组织签名要求 |
| A 当前 HR/组织外部事件 | 事件 → 组织 → 用户；不能在这之后调用 C 管理员优先入口，存在反向首锁。HR/OA 未接通，继续拒绝，未宣称全局锁序完成 |

资源锁由 `CResourceLockMapper` 在首次 D 普通查询之前取得；授权写入的首次一致性读发生在 M5/用户/资源锁之后。SQL 与映射锁保持到提交。因此等待任命、会话或资源变更后，验签与功能判断读取提交后的事实。MyBatis 沿用 STATEMENT 本地缓存；无跨请求允许缓存。

当前使用宽范围锁保证基线正确性，会串行化管理写入，没有吞吐承诺。SQL 死锁或超时回滚，沿用 503，不在本部分自动重试。B 当前资源写入口尚未接通 D 签名/审计，部分路径先删映射再更新角色；与 C 的角色→映射锁存在反向关系。协作要求是 B 在写入前按同一管理/资源锁协议取得当前资格，随后修改并完成全部关联签名和审计；不能把现有 B 写入当作已验收的并发生产入口。未签名的 B 修改会使后续 C/D 验签拒绝。

HR/OA 下一阶段的协作设计约束：保留 A 事件锁第一位，进入业务回调后必须在任何组织/用户锁之前先取得所需 C 管理事实及完整用户集合的统一顺序锁；C 需要专用服务身份入口，不能在持有组织/用户锁之后伪造管理员回调人工授权。此设计仍需 A/C/D 联合落实，本文没有新增公共方法或擅自修改 A 事务。

## 配置、数据和外部依赖

无新增表、字段、HTTP 或 A/D 公共方法签名。新增 Mapper 仅锁定现有表；已有库不重跑 schema。

部署需要 A 的 JWT 验证配置和 D 的 Ed25519 当前私钥/历史公钥；具体配置沿用 [D_STAGE1](D_STAGE1.md)。完整权限资源预置必须受控签署组织、用户身份/空授权、角色、管理员/空清单、资源及映射。本次不提供初始化后门；测试夹具只在 src/test。

外部依赖：

1. A/D 完成 `ManagementDataSecurity`，才能验收真实登录、注销写入审计及人员/组织生产链路。
2. B/D 完成资源写入口资格、统一锁序及关联签名/审计；现有 `02_add_canonical_path_and_index.sql` 增量应由团队在现有库核对后执行。本次独立容器使用 schema + 该增量，未修改开发库。
3. C2-02 确认管理角色资格、合并生命周期及组织重审；本部分不猜管理角色等级。
4. C2-03/04 与 A/D 统一事件业务锁序、身份最终重签时机及可信服务认证，再接 HR/OA。D 当前 TrustedApiCaller 仍默认拒绝，测试直接调用 D 内部 check 不代表 AU03 服务请求认证完成。
5. C2-05 实现恢复；当前批量接通身份只证明既有逐项执行，不能称重启续跑。C2-06 使用留存审计实现历史查询。
