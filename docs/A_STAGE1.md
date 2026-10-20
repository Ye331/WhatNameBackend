# A 第一阶段：账号、组织与 HR 基础处理

本文件汇总已交付的第一阶段基础功能，范围依据[两阶段开发计划](../DEVELOPMENT_PLAN.md)。第一阶段结果见[第一阶段测试报告](A_STAGE1_TEST_REPORT.md)；后续短信演示、身份实现和真实签名审计适配单独见[第二阶段说明](A_STAGE2.md)。

## 1. 接口范围

路径统一带 `/api/v1`，响应使用 `ApiResponse`。ID 使用正整数十进制字符串；分页从 1 开始，默认 `pageSize=20`，最大 500。

| 编号 | 方法与路径 | 当前行为 / 必要权限 |
|---|---|---|
| AU01 | POST `/auth/login` | 用户名或完整手机号、密码登录，签发 JWT |
| AU05 | POST `/auth/sms/send` | 发送 SET_PASSWORD / RESET_PASSWORD 验证码，发送通道可替换；第一阶段保留可替换接点 |
| AU07 | POST `/auth/password/initialize` | 验证一次性证明，初始化密码 |
| AU08 | POST `/auth/password/reset` | 密码策略及历史检查，重置后旧会话失效 |
| AU04 | POST `/auth/logout` | 按 JWT jti 注销，重复注销幂等 |
| US01 | GET `/users` | 管理范围内检索、筛选、计数和分页；`IAM_USERS_VIEW` |
| US02 | GET `/users/{userId}` | 查询人员详情；`IAM_USERS_VIEW` |
| US03 | POST `/users/temporary` | 人工创建临时账号；`IAM_USERS_WRITE` |
| US04 | PATCH `/users/{userId}/status` | 临时账号暂停 / 恢复；`IAM_USERS_WRITE` |
| DP01 | GET `/departments` | 返回可见组织树的平铺节点；`IAM_DEPTS_VIEW` |
| DP02 | GET `/departments/{deptId}/users` | 部门人员查询；`IAM_USERS_VIEW` |
| DP03 | POST `/departments/{deptId}/freeze` | 冻结部门；`IAM_DEPTS_FREEZE` |
| DP04 | POST `/departments/{deptId}/unfreeze` | 解冻部门；`IAM_DEPTS_FREEZE` |
| HR01 | PUT `/integrations/hr/departments/{deptCode}` | 可信 HR 部门同步 |
| HR02 | PUT `/integrations/hr/employees/{sgNo}` | 可信 HR 人员 UPSERT / SUSPEND / RESUME / REHIRE |
| HR03 | POST `/integrations/hr/transfers` | 调岗及旧岗位来源回收编排 |
| HR04 | POST `/integrations/hr/terminations` | 离职及全部来源、管理员资格回收编排 |
| HR05 | POST `/integrations/hr/merges` | 合并部门，保留空源与合并去向 |

请求字段以现有 Controller / DTO 为准。可从[账号请求](../requests/task05-accounts.http)、[组织请求](../requests/task06-organization.http)、[HR 请求](../requests/task07-hr-personnel.http)和[管理接入请求](../requests/task12-c-management.http)查看示例；示例不能代替真实凭据或服务认证。


## 2. 账号与人员查询

- JWT 验证后仍检查当前账号状态、`session_version` 和 jti 注销记录；令牌不携带可用于后续放行的权限快照。重置密码、暂停和离职使旧会话失效，恢复账号不复活旧 JWT。
- 密码使用 scrypt，初始化 / 重置校验短信用途、有效期、次数及密码历史；策略失败不消耗有效证明。未知账号、不可用账号和错误凭据采用防枚举响应。
- 临时账号为 `source=2`，只允许在合法未合并部门创建；US04 只接受状态 1 / 2，不用于 HR 离职或复职。手机号、SG 和用户名唯一性由现有约束裁决。
- US01 的 `status` 为 1 可用 / 2 暂停 / 3 离职，`source` 为 1 HR / 2 人工 / 3 受控初始化；省略时不限制该维度。`keyword` 最长 64，`page` 最大 1000000；显式部门范围外返回 403。人员详情的不存在与不可见统一返回 404。
- 用户名 / SG 保留子串查询，完整规范化手机号使用已有 HMAC 精确匹配；姓名支持单字、全名及中间连续片段。非空关键词先按权限、部门、状态和来源筛选，再按 ID 每批最多 200 人验签、解密匹配，遍历全部候选后返回准确 total 和目标页。范围内任一候选完整性或姓名解密失败，整次查询失败；范围外资料不读取、不解密。姓名明文不写日志或跨请求缓存。

姓名匹配沿用 strip + NFC、大小写及重音敏感的字面子串语义。200 是单批大小，扫描量没有因此截断；大范围查询会延长事务和管理锁占用，尚未通过规模及性能验收。


## 3. 组织与 HR 行为

- DP01 默认 `includeDescendants=true`；false 只返回可见范围的根。范围外父节点用 `parentId="0"` 表达，不泄露隐藏路径；合并去向只在目标可见时返回。DP02 默认只查直属人员，开启子树时先与管理范围取交集，再计数分页。
- 部门冻结作用于访问判断，不修改人员账号状态。解冻后仍按当前账号、来源和权限判断。已合并空源保留历史展示，不接收新人员或任命。
- HR01 的负责人选填、可后补，更新省略时保留。HR05 转移源直属人员和子部门，保留源及去向；源或祖先冻结时须先显式解冻，子部门自身冻结保留。合并只比较和推进源部门 `hr_version`，不覆盖目标部门及人员的版本。
- HR02 新建账号使用姓名拼音首字母生成登录名，碰撞时追加 SG 尾部，仍冲突则拒绝；初始密码为不可知的随机占位且未初始化。更新保留账号 ID、用户名及已有凭据。每个已接受事件只推进对应人员的严格更新 `hr_version`，不要求版本连续。
- SUSPEND 保留授权并使旧会话失效；RESUME 不新增角色。离职账号只允许 REHIRE，且须由 C 证明不存在可复活的有效来源或管理员资格；复职不恢复历史授权。
- HR03 校验原部门，要求 C 仅回收旧部门的 DEPT_POSITION 来源，保留独立 MANUAL / OA / 其他部门来源及历史，不授予新角色。HR04 要求 C 回收全部有效来源、有效关系和管理员资格，再将账号置为离职并递增会话版本；`grant_revision` 由 C 维护。
- HR04 使用带时区的 `effectiveAt`，没有额外必填 `occurredAt`。未来时间返回 422，不改账号或版本、不预约执行；到时使用新 `eventId` 通知。

数据库入口见[使用说明](../db/README.md)与[现有 schema](../db/01_schema.sql)。已有库按流程核对[部门合并迁移](../db/migrations/20261016_01_department_merge.sql)，不重复空库建表；本说明不增加表、字段、HTTP 接口或业务规则。


## 4. 第一阶段协作边界

沿用 Controller → Service → Mapper、同一 MySQL 数据源和调用方事务，不增加内部 HTTP。A 按 C 已有管理员资格及用户、部门范围方法编排管理入口，先取得管理锁和资格，再检查会话和必要安全条件；业务入口参与已有事务时拒绝，避免使用旧快照作写入依据。

第一阶段完成业务编排和可替换协作接点。D 的真实完整性、功能鉴权、签名及审计尚未在 A 中完成生产适配；C 的 HR 回收、复职资格及组织重审，D 的可信服务认证、请求签名和 nonce，以及独立演示短信属于后续接入范围。必要实现缺失时明确拒绝；测试替身不能代表生产接入完成。C、D 的阶段范围分别见 [C 第一阶段](C_STAGE1.md)、[D 第一阶段](D_STAGE1.md)。

外部事件沿用来源系统与 eventId 幂等键。首次接收后，在 SERIALIZABLE 业务事务内执行业务、必要签名审计及 APPLIED；相同内容的已完成事件返回保存结果，内容冲突或处理中返回 409，待恢复返回 503。非终态恢复明确拒绝，不提供在线接管或自动恢复入口；人工异常处理步骤仍需团队确认。

姓名搜索沿用现有数据库，在可见候选范围内验签、解密后匹配并完整统计，未通过规模性能验收。第一阶段基础功能完成不代表完整权限链路已验收；第二阶段的新增能力、实际状态和待协作事项以[第二阶段说明](A_STAGE2.md)为准。
