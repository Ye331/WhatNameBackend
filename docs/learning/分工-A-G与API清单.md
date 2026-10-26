# A–G 分工与逐API答辩入口

当前代码共 **45个HTTP接口：43个业务接口＋2个运行检查**。下面每个接口只出现一次；每个人先读自己的接口卡，再准备公共链路说明。

| 人员 | 主讲范围 | 业务接口数 | 运行检查数 | 阅读入口 |
|---|---|---:|---:|---|
| A | 账号与临时人员；另负责两个运行检查接口 | 10 | 2 | [账号与登录](api/A-账号与登录.md) |
| B | RBAC0 计算、菜单输出、IAM 自我鉴权和业务演示 | 3 | 0 | [在线鉴权与平台自我管理](api/B-在线鉴权与平台自我管理.md) |
| C | 三层资源及菜单/API 映射 | 11 | 0 | [应用菜单与API资源](api/C-应用菜单与API资源.md) |
| D | 区分角色定义、角色菜单和用户角色三种操作 | 7 | 0 | [角色定义与用户授权](api/D-角色定义与用户授权.md) |
| E | 部门管理范围与组织冻结；数据范围是跨接口公共规则 | 4 | 0 | [部门范围与组织冻结](api/E-部门范围与组织冻结.md) |
| F | 同步、调岗、离职、部门合并 | 5 | 0 | [HR与人员组织变动](api/F-HR与人员组织变动.md) |
| G | 批量、OA、审计；另主讲签名、事务、初始化和恢复 | 3 | 0 | [批量OA审计与公共安全](api/G-批量OA审计与公共安全.md) |

A–G分工：A账号，B鉴权，C资源，D角色授权，E管理范围与组织，F HR，G批量/OA/审计。E主讲实际参与多个接口的数据范围算法。G还负责签名、初始化和恢复，不能仅按HTTP接口数量评价工作量。

## 所有人共同掌握

先读 [公共接口规则](api/00-公共接口规则.md)。每人应能解释：身份如何验证、权限从哪里来、目标范围怎样限制、事务如何提交、错误为什么发生。

## 完整接口归属表

| 编号 | 主讲 | 方法与完整路径 | 用途 |
|---|---|---|---|
| [A01](api/A-账号与登录.md#A01) | A | `POST /api/v1/auth/login` | 账号或手机号密码登录 |
| [A02](api/A-账号与登录.md#A02) | A | `GET /api/v1/auth/me` | 读取本人身份与IAM权限 |
| [A03](api/A-账号与登录.md#A03) | A | `POST /api/v1/auth/logout` | 退出该账号全部会话 |
| [A04](api/A-账号与登录.md#A04) | A | `PUT /api/v1/auth/password` | 修改本人密码 |
| [A05](api/A-账号与登录.md#A05) | A | `GET /api/v1/users` | 分页查询范围内用户 |
| [A06](api/A-账号与登录.md#A06) | A | `GET /api/v1/users/{id}` | 查询一个用户 |
| [A07](api/A-账号与登录.md#A07) | A | `POST /api/v1/users/temporary` | 创建临时账号 |
| [A08](api/A-账号与登录.md#A08) | A | `PATCH /api/v1/users/{id}` | 修改临时账号信息 |
| [A09](api/A-账号与登录.md#A09) | A | `PATCH /api/v1/users/{id}/status` | 暂停或恢复临时账号 |
| [A10](api/A-账号与登录.md#A10) | A | `PUT /api/v1/users/{id}/password` | 管理者重置目标密码 |
| [A11](api/A-账号与登录.md#A11) | A | `GET /api/v1/system/ping` | 检查后端进程 |
| [A12](api/A-账号与登录.md#A12) | A | `GET /api/v1/system/database` | 检查数据库连接 |
| [B01](api/B-在线鉴权与平台自我管理.md#B01) | B | `GET /api/v1/auth/menus` | 查询本人某应用的菜单 |
| [B02](api/B-在线鉴权与平台自我管理.md#B02) | B | `POST /api/v1/auth/check` | 外部业务后端在线鉴权 |
| [B03](api/B-在线鉴权与平台自我管理.md#B03) | B | `GET /api/v1/demo/{appCode}/{feature}` | 演示业务接口逐次鉴权 |
| [C01](api/C-应用菜单与API资源.md#C01) | C | `GET /api/v1/applications` | 查询应用列表 |
| [C02](api/C-应用菜单与API资源.md#C02) | C | `GET /api/v1/applications/{app}/menus` | 查询应用菜单 |
| [C03](api/C-应用菜单与API资源.md#C03) | C | `GET /api/v1/applications/{app}/apis` | 查询应用API |
| [C04](api/C-应用菜单与API资源.md#C04) | C | `GET /api/v1/menus/{id}/apis` | 查询菜单关联API ID |
| [C05](api/C-应用菜单与API资源.md#C05) | C | `POST /api/v1/applications` | 新建应用 |
| [C06](api/C-应用菜单与API资源.md#C06) | C | `PATCH /api/v1/applications/{id}` | 修改应用 |
| [C07](api/C-应用菜单与API资源.md#C07) | C | `POST /api/v1/applications/{app}/menus` | 新建菜单或目录 |
| [C08](api/C-应用菜单与API资源.md#C08) | C | `PATCH /api/v1/applications/{app}/menus/{id}` | 修改菜单或目录 |
| [C09](api/C-应用菜单与API资源.md#C09) | C | `POST /api/v1/applications/{app}/apis` | 新建API资源 |
| [C10](api/C-应用菜单与API资源.md#C10) | C | `PATCH /api/v1/applications/{app}/apis/{id}` | 修改API资源 |
| [C11](api/C-应用菜单与API资源.md#C11) | C | `PUT /api/v1/menus/{id}/apis` | 完整保存菜单接口关联 |
| [D01](api/D-角色定义与用户授权.md#D01) | D | `GET /api/v1/roles` | 查询角色目录 |
| [D02](api/D-角色定义与用户授权.md#D02) | D | `POST /api/v1/roles` | 新建角色 |
| [D03](api/D-角色定义与用户授权.md#D03) | D | `PATCH /api/v1/roles/{id}` | 修改或停用角色 |
| [D04](api/D-角色定义与用户授权.md#D04) | D | `GET /api/v1/roles/{id}/menus` | 读取角色菜单ID集合 |
| [D05](api/D-角色定义与用户授权.md#D05) | D | `PUT /api/v1/roles/{id}/menus` | 完整保存角色菜单权限 |
| [D06](api/D-角色定义与用户授权.md#D06) | D | `GET /api/v1/users/{id}/roles` | 读取用户角色ID集合 |
| [D07](api/D-角色定义与用户授权.md#D07) | D | `PUT /api/v1/users/{id}/roles` | 完整保存用户角色 |
| [E01](api/E-部门范围与组织冻结.md#E01) | E | `PUT /api/v1/users/{id}/scope` | 配置管理数据范围 |
| [E02](api/E-部门范围与组织冻结.md#E02) | E | `GET /api/v1/departments` | 查询本人可管理部门 |
| [E03](api/E-部门范围与组织冻结.md#E03) | E | `POST /api/v1/departments/{id}/freeze` | 冻结部门权限 |
| [E04](api/E-部门范围与组织冻结.md#E04) | E | `POST /api/v1/departments/{id}/unfreeze` | 解冻部门权限 |
| [F01](api/F-HR与人员组织变动.md#F01) | F | `POST /api/v1/integrations/hr/departments` | 按部门编码同步组织 |
| [F02](api/F-HR与人员组织变动.md#F02) | F | `POST /api/v1/integrations/hr/employees` | 按工号同步员工 |
| [F03](api/F-HR与人员组织变动.md#F03) | F | `POST /api/v1/integrations/hr/users/{id}/transfer` | HR调岗 |
| [F04](api/F-HR与人员组织变动.md#F04) | F | `POST /api/v1/integrations/hr/users/{id}/terminate` | HR离职 |
| [F05](api/F-HR与人员组织变动.md#F05) | F | `POST /api/v1/integrations/hr/departments/{id}/merge` | HR合并部门 |
| [G01](api/G-批量OA审计与公共安全.md#G01) | G | `POST /api/v1/grants/batch` | 同步批量授予或回收一个角色 |
| [G02](api/G-批量OA审计与公共安全.md#G02) | G | `POST /api/v1/integrations/oa/roles` | OA审批后执行角色授予或回收 |
| [G03](api/G-批量OA审计与公共安全.md#G03) | G | `GET /api/v1/audit/operations` | 分页查询操作与鉴权日志 |

## 每人的交付物

1. 按自己的API卡走读源码，能定位Controller、Service、Repository与涉及表。
2. 至少完成一条完整正向链路和两条反向用例，记录HTTP状态、响应和数据库变化。
3. 准备3–5分钟讲解：解决什么问题 → 请求怎么走 → 权限/范围在哪里检查 → 数据如何变化 → 怎样验证。
4. 在本人的 `.http` 示例中填测试账号、JWT、部门/角色ID；服务密钥只放本地私有环境，不上传。
5. 没有专用自动用例的接口，明确标为人工验收，不能把相关测试冒充完整覆盖。

## 跨人员交接

- A给B一个有效JWT；D准备用户角色，C准备菜单/API关系，B展示允许、拒绝和即时撤权。
- E准备ALL与DEPT_TREE两名测试管理者；A/D/G用同一批目标证明范围检查一致。
- F调岗或离职测试员工后，D核对角色，B核对鉴权，G核对签名与日志。
- G演示OA与手工操作同一份授权关系、批量部分成功，以及初始化账号误撤权后的本地恢复。

## 接口覆盖核对

执行 `python scripts/generate-api-handbook.py --check`：与三个Controller路由及OpenAPI核对，确认45个接口无遗漏、无重复；同时验证源码方法和文档链接。这个命令只读检查文档，不启动后端、不修改数据库。
