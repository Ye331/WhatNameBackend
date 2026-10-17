# A接口筛选与响应契约对齐（子任务10）

**现行检索变更（2026-10-17 Task18）**：下文“只解密本页”等为任务10历史实现。当前US01在团队23表上对范围/部门/状态/来源交集按ID分批验签解密姓名，完整统计后分页，具体见 [Task18](task18-name-search-existing-schema.md)。Task17额外两表方案已撤下，不再要求迁移、专用密钥或回填；源契约快照不作改写。

2026-10-16，北京时间。依据 d1edb38 后的真实源码、公共 ErrorCode 和05/06/07证据修正；这是实现对照，不是负责人新增业务答复。A仍为18操作，全部契约仍56操作。真实C/D未接入，生产必要能力默认拒绝；src/test替身通过不等于联调通过。

## US01实现与边界

`UserController.list → UserService.list → UserAccountMapper.count/list → XML filter`：可选Integer status/source各限1/2/3，省略不限制该维度。枚举来自db/01_schema.sql的ck_user_status、ck_user_origin及字段注释（OpenAPI原参数只声明integer，未列枚举；本次补明）。status=1可用/2暂停/3离职，source=1 HR/2人工/3受控初始化。page为1..1000000，pageSize为1..500，offset先转long再乘。非法枚举、非整数、溢出整数和分页越界统一400 INVALID_ARGUMENT。

当前JWT及session验证、必要身份验签、D功能/C范围端口先于用户查询；scope无效拒绝，显式deptId范围外403。SQL用绑定参数，所有AND条件与括号内keyword OR条件都在scope内，count/list共用filter且处于同一REPEATABLE_READ事务。只解密本页范围内投影，保留07的lockByEmployeeNo/insertHr/updateHr完整实现。HR能力没有删改。

## 响应差异及依据

- AU01：未知、未设密、暂停、离职、锁定或错误密码均401 INVALID_CREDENTIALS；删除API说明中的ACCOUNT_UNAVAILABLE。AuthService.login保留防枚举行为。
- AU07：重复初始化/不可用账号/无效证明均401 INVALID_CREDENTIALS；删除PASSWORD_ALREADY_INITIALIZED。AU07/AU08补422 BUSINESS_RULE_VIOLATION（密码策略、历史）；失败不核销有效证明，不改密码/sv。原有400/429/503保留。
- US03：唯一冲突409 DUPLICATE_RESOURCE，替换DUPLICATE_PHONE；补422（不存在或已合并目标部门）。US04补404（目标不存在/范围外统一）、422（非source=2或离职），去无实际版本输入/分支的409。status请求只1/2，其余400。
- AU05是防枚举短信受理，无账号资格401分支；移除该条401。AU04只验证JWT并注销自身jti，无参数校验400或管理权限403分支，移除这两条；旧sv/暂停/离职且可验证JWT仍可注销。
- DP01/DP02补实际组织树循环/路径超长422；DP02范围中目标不存在为422 INVALID_DEPT_MAPPING。DP03/DP04补422（已合并部门、缺失范围中目标、非法组织路径），去无版本比较分支的409。必要可信依赖503继续保留。
- HR01/02/03/04/05原响应集合已覆盖实际400/401/403/409/422/503；人员操作原有404保留，本次无核心/DTO/契约状态机改动。
- US01/US02严格成功schema原有additionalProperties=false，却遗漏真实UserView字段。US01补passwordInitialized/source，US02补source，两者sgNo声明nullable（非HR为null）。不修改成功业务实现。

下面只列每个A操作实际或其必要协作端口约定的分支。管理接口401/403来自当前会话及C/D端口；当前默认缺适配主要返回503，不能据此声称真实C/D分支已联调。405/415等公共协议错误继续由GlobalExceptionHandler统一处理，未机械加到每操作；500是未预期故障，非业务成功承诺。

| 编号 | 路由（/api/v1前缀） | responses |
|---|---|---|
| AU01 | POST `/auth/login` | 200, 400, 401, 429, 503 |
| AU05 | POST `/auth/sms/send` | 200, 400, 429, 503 |
| AU07 | POST `/auth/password/initialize` | 200, 400, 401, 429, 422, 503 |
| AU08 | POST `/auth/password/reset` | 200, 400, 401, 429, 422, 503 |
| AU04 | POST `/auth/logout` | 200, 401, 503 |
| US01 | GET `/users` | 200, 400, 401, 403, 503 |
| US02 | GET `/users/{userId}` | 200, 400, 401, 403, 404, 503 |
| US03 | POST `/users/temporary` | 200, 400, 401, 403, 409, 422, 503 |
| US04 | PATCH `/users/{userId}/status` | 200, 400, 401, 403, 404, 422, 503 |
| DP01 | GET `/departments` | 200, 400, 401, 403, 422, 503 |
| DP02 | GET `/departments/{deptId}/users` | 200, 400, 401, 403, 422, 503 |
| HR01 | PUT `/integrations/hr/departments/{deptCode}` | 200, 400, 401, 403, 409, 422, 503 |
| HR02 | PUT `/integrations/hr/employees/{sgNo}` | 200, 400, 401, 403, 404, 409, 422, 503 |
| HR03 | POST `/integrations/hr/transfers` | 200, 400, 401, 403, 404, 409, 422, 503 |
| HR04 | POST `/integrations/hr/terminations` | 200, 400, 401, 403, 404, 409, 422, 503 |
| HR05 | POST `/integrations/hr/merges` | 200, 400, 401, 403, 409, 422, 503 |
| DP03 | POST `/departments/{deptId}/freeze` | 200, 400, 401, 403, 422, 503 |
| DP04 | POST `/departments/{deptId}/unfreeze` | 200, 400, 401, 403, 422, 503 |

## 原文与未完成姓名搜索

02_API接口设计规范.md的US01原文：“搜索关键词，受限字段搜索”；请求原例 `GET /api/v1/users?keyword=张&deptId=17&page=1&pageSize=20` 保留，未删改来冒称完成。真实代码仅用户名/SG子串和完整规范化手机HMAC精确；姓名为随机AES-GCM，现无姓名搜索索引。因此“张”不会按姓名包含语义命中，尚未闭合。keyword maxLength=64与既有Service一致。

供总控与用户决定的候选（均未实施）：

| 候选 | 兼容性与代价 |
|---|---|
| 确认关键词仅限现有字段，并另行明确姓名入口 | 必须由需求方明确接受范围变化及原示例含义，不能本任务擅改或称原需求已完成 |
| 受控姓名子串盲索引，SQL先筛选/计数/分页 | 可兼容包含搜索；须定义归一化、子串长度、HMAC密钥/轮换、碰撞或误报复核与准确total、索引写入事务和历史回填；增加可关联性/频率泄露与存储写放大，需D共同审查 |
| 批准有界候选集解密，再精确过滤分页 | 只有业务明确限制候选规模并定义超限响应才可用；全集团范围不可解密扫描；无法自动保证全范围精确total，改变现有契约，不能直接采用 |

最少必要问题：keyword是否要求姓名任意子串（单字“张”也包含在内），若是，接受何种隐私泄露/索引成本及归一化、分页total约束？本任务不新增搜索协议/表、不密文LIKE、不全集团解密分页。

## 验证与交付边界

真实MySQL/HTTP共67项通过（M1=27、HR=19、部门业务=18、部门Mapper=3），4个新增M1方法覆盖状态/来源矩阵、scope/keyword/dept组合、分页边界、非法值、密码422回滚；既有23项保留，仅2个直接Service调用补传空筛选参数。首轮沙箱loopback失败不计通过；获准本机网络后完整回归通过。03密码组件既有证据复用，未重跑性能。

三份完整设计位于仓库外RBAC0/design目录，本地修订不等于已上传或已发给成员。tmp/task10-contract-baseline、对应.diff和task10-contract-verification-final.json保存原文与静态检查；解析YAML、检查重复键、本地$ref和操作数量，非完整OpenAPI标准认证。38个非A操作原始片段和解析结果、公共components均保持一致。

负责人五项确认保持：HR01负责人选填/省略保留；合并保留空源/去向、冻源或祖先先解冻、子冻结保留；仅推进源hr_version；SUSPEND保留授权失效旧JWT/RESUME不增角色或复活JWT/仅REHIRE恢复离职；未来effectiveAt422不改账号版本、到时新eventId、HR04不增occurredAt。

真实C/D、姓名包含、完整性能/HTTPS部署验收未完成。不改ErrorCode、HR/部门/event核心、POM/schema、密码/JWT/加密核心、04/09工具；无Git写操作、推送、PR或其他消息。完整代码讲解仍待A全部开发完成后统一进行，本次只记录调用链与追问索引。
