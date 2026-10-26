# A · 账号与登录

职责：账号与临时人员；另负责两个运行检查接口。主讲 12 个接口。

核心源码：AccountService、JwtTokenService、PasswordHasher。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [A01](#A01) | `POST /api/v1/auth/login` | 账号或手机号密码登录 |
| [A02](#A02) | `GET /api/v1/auth/me` | 读取本人身份与IAM权限 |
| [A03](#A03) | `POST /api/v1/auth/logout` | 退出该账号全部会话 |
| [A04](#A04) | `PUT /api/v1/auth/password` | 修改本人密码 |
| [A05](#A05) | `GET /api/v1/users` | 分页查询范围内用户 |
| [A06](#A06) | `GET /api/v1/users/{id}` | 查询一个用户 |
| [A07](#A07) | `POST /api/v1/users/temporary` | 创建临时账号 |
| [A08](#A08) | `PATCH /api/v1/users/{id}` | 修改临时账号信息 |
| [A09](#A09) | `PATCH /api/v1/users/{id}/status` | 暂停或恢复临时账号 |
| [A10](#A10) | `PUT /api/v1/users/{id}/password` | 管理者重置目标密码 |
| [A11](#A11) | `GET /api/v1/system/ping` | 检查后端进程 |
| [A12](#A12) | `GET /api/v1/system/database` | 检查数据库连接 |

HTTP示例文件：[本组请求](../../../requests/learning/A.http)。变量均为待填写占位，密码和密钥不要提交。

<a id="A01"></a>
## A01 · 账号或手机号密码登录

`POST /api/v1/auth/login` · 主讲：A · 业务接口

**入口与调用链：** [AuthController.login](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L25) → [AccountService.login](../../../src/main/java/com/whatname/iam/service/AccountService.java#L43) → AccountRepository.findByUsername/findByPhone；DomainRepository.query/save。

**持久层源码：** [AccountRepository](../../../src/main/java/com/whatname/iam/repository/AccountRepository.java#L1)；[DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** 登录页 App.vue → api.js.login

**权限：** 公开登录入口；不需要旧 JWT。

**范围：** 仅验证输入凭据对应的账号，不使用管理数据范围。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| loginType | string | 必填 | USERNAME 或 PHONE |
| identifier | string | 必填 | 最长100；PHONE 会规范化并检查手机号 |
| password | string | 必填 | 登录只验证已有哈希；新密码复杂度由设密接口检查 |

```http
POST /api/v1/auth/login
Content-Type: application/json

{
  "loginType": "USERNAME",
  "identifier": "demo_temp",
  "password": "ExampleOnly123!"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "authenticated": true,
  "token": "<JWT>",
  "user": {
    "id": "101",
    "username": "demo_temp",
    "realName": "演示用户",
    "phone": "13800000002",
    "employeeNo": null,
    "deptId": "2",
    "status": 1,
    "source": 2,
    "employmentType": null,
    "managementScope": "DEPT_TREE",
    "createdAt": "2026-10-25T10:00:00"
  }
}
```

### 怎么实现、改哪些表

1. 按登录类型找到账号并验签。
2. 检查 status=1 和 scrypt 密码哈希。
3. 写成功/失败 LOGIN 日志；成功签发含用户ID和会话版本的 JWT。
4. 失败先返回内部结果提交日志，再由 Controller 返回401。

**数据库：** 读 sys_user、sys_data_signature；写 sys_operation_log。

**事务与锁：** 登录失败日志通过正常返回先提交；Controller 在事务外报告凭据错误，避免把失败日志回滚。

**签名与日志：** LOGIN：成功与普通凭据失败均记录；完整性故障抛异常，不保证该路径有失败日志。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.temporaryAccountLifecycleClearsRolesAndInvalidatesSessions](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L410)

**正向演示：** 创建临时测试账号后，用 USERNAME 和 PHONE 分别登录，均获得 token。

**反向演示：** 同一账号输入错误密码，应401；随后由G查询 LOGIN 失败记录。

常见错误：

- 400 INVALID_ARGUMENT：登录类型/字段不合法
- 401 INVALID_CREDENTIALS：密码错误或账号不可用
- 503 INTEGRITY_FAILURE：用户签名异常

**老师可能问：** 为什么登录不能只看密码正确？

**回答要点：** 还要验证账号签名和可用状态，暂停/离职账号即使密码正确也不能登录。


<a id="A02"></a>
## A02 · 读取本人身份与IAM权限

`GET /api/v1/auth/me` · 主讲：A · 业务接口

**入口与调用链：** [AuthController.me](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L33) → [AccountService.me](../../../src/main/java/com/whatname/iam/service/AccountService.java#L64) → DomainRepository.get/roles/menus/apis/query。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** api.js.refreshSession → App.vue 导航

**权限：** 本人 Bearer JWT，不要求另有 IAM 管理菜单权限。账号状态和会话版本仍必须有效。

**范围：** 只返回本人，不接受目标 userId。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
GET /api/v1/auth/me
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "user": {
    "id": "101",
    "username": "demo_user",
    "realName": "演示用户",
    "phone": "13800000002",
    "employeeNo": "DEMO001",
    "deptId": "2",
    "status": 1,
    "source": 1,
    "employmentType": 1,
    "managementScope": "DEPT_TREE",
    "createdAt": "2026-10-25T10:00:00"
  },
  "menus": [
    {
      "id": "4",
      "appId": "3",
      "parentId": "0",
      "menuCode": "DEMO_MENU",
      "menuName": "演示菜单",
      "menuType": 2,
      "path": "/demo",
      "sortOrder": 0,
      "status": 1
    }
  ]
}
```

### 怎么实现、改哪些表

1. 校验JWT、用户签名、状态和会话版本。
2. 读取 IAM 应用，计算本人的有效菜单并集。
3. 返回 user 和 menus，供前端导航与按钮使用。

**数据库：** 共同读取：sys_user、sys_dept、sys_application、sys_user_role、sys_role、sys_role_permission、sys_menu、sys_menu_api、sys_api、sys_data_signature。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写操作日志。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.authenticationRejectsBadCredentialsAndNeverSerializesPasswordOrSessionVersion](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L389)

**正向演示：** 登录后携带 Bearer 调用，返回本人及实际拥有的 IAM 菜单。

**反向演示：** 退出登录后复用旧JWT，应401。

常见错误：

- 401 INVALID_TOKEN：令牌失效
- 503 INTEGRITY_FAILURE：身份或权限数据异常

**老师可能问：** 这里返回哪些用户信息？

**回答要点：** 返回当前登录用户及其有效 IAM 功能菜单权限。


<a id="A03"></a>
## A03 · 退出该账号全部会话

`POST /api/v1/auth/logout` · 主讲：A · 业务接口

**入口与调用链：** [AuthController.logout](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L46) → [AccountService.logout](../../../src/main/java/com/whatname/iam/service/AccountService.java#L69) → DomainRepository.lock/get/save。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** App.vue 退出 → api.js.logout

**权限：** 本人 Bearer JWT，不要求另有 IAM 管理菜单权限。账号状态和会话版本仍必须有效。

**范围：** 仅本人。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
POST /api/v1/auth/logout
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 取得本人并锁用户行。
2. 递增 sessionVersion，重新签名。
3. 保存 LOGOUT，随后旧JWT均因版本不符失效。

**数据库：** 读写 sys_user；写 sys_data_signature、sys_operation_log。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** LOGOUT。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 准备同账号两枚JWT，退出其中一个会话。

**反向演示：** 两枚旧JWT随后访问 /auth/me 都应401。

常见错误：

- 401 INVALID_TOKEN：旧或无效JWT
- 503 INTEGRITY_FAILURE：用户签名异常

**老师可能问：** 为什么另一个浏览器也退出了？

**回答要点：** 设计采用账号会话版本统一失效，未保留单令牌黑名单。


<a id="A04"></a>
## A04 · 修改本人密码

`PUT /api/v1/auth/password` · 主讲：A · 业务接口

**入口与调用链：** [AuthController.password](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L53) → [AccountService.ownPassword](../../../src/main/java/com/whatname/iam/service/AccountService.java#L78) → DomainRepository.lock；实体脏检查。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** App.vue 修改密码对话框

**权限：** 本人 Bearer JWT，不要求另有 IAM 管理菜单权限。账号状态和会话版本仍必须有效。

**范围：** 仅本人。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| oldPassword | string | 必填 | 必须匹配当前哈希 |
| password | string | 必填 | 8–64位，含字母和数字 |

```http
PUT /api/v1/auth/password
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "oldPassword": "OldExample123!",
  "password": "NewExample456!"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 锁本人并验签。
2. 校验原密码。
3. 生成新scrypt哈希，递增会话版本，签名并记日志。

**数据库：** 读写 sys_user；写签名、日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** PASSWORD_CHANGE。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 用正确原密码改密，再用新密码登录。

**反向演示：** 原密码错误应401，旧密码仍可登录；成功改密后原JWT应401。

常见错误：

- 401 INVALID_CREDENTIALS：原密码不符
- 400 INVALID_ARGUMENT：新密码不合规则

**老师可能问：** 为什么改完密码需要重新登录？

**回答要点：** 会话版本递增，旧JWT不能继续使用。


<a id="A05"></a>
## A05 · 分页查询范围内用户

`GET /api/v1/users` · 主讲：A · 业务接口

**入口与调用链：** [ManagementController.users](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L34) → [AccountService.users](../../../src/main/java/com/whatname/iam/service/AccountService.java#L88) → AccountService.visibleDepartments → DomainRepository.all(Department)、page(Account)、query(count)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 用户账号/权限分配

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_USERS_VIEW 或 IAM_GRANTS_VIEW。

**范围：** 先计算本人可见部门；指定 deptId 必须也在范围内，且仅筛该精确部门，不自动包含其子部门。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| keyword | query string | 可选 | 匹配账号、姓名、工号；不是手机号搜索 |
| deptId | query ID | 可选 | 指定部门精确筛选 |
| status/source | query integer | 可选 | 1/2/3 含义见公共说明；当前查询未单独验证枚举 |
| page | query integer | 默认1 | >=1 |
| pageSize | query integer | 默认20 | 1–100 |

```http
GET /api/v1/users?page=1&pageSize=20
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "items": [
    {
      "id": "101",
      "username": "demo_user",
      "realName": "演示用户",
      "phone": "13800000002",
      "employeeNo": "DEMO001",
      "deptId": "2",
      "status": 1,
      "source": 1,
      "employmentType": 1,
      "managementScope": "DEPT_TREE",
      "createdAt": "2026-10-25T10:00:00"
    }
  ],
  "total": "1",
  "page": 1,
  "pageSize": 20
}
```

### 怎么实现、改哪些表

1. 验证菜单权限。
2. 计算可见部门并检查 deptId。
3. 按关键字/状态/来源分页；逐条验证返回用户签名。
4. 返回 items/total/page/pageSize。

**数据库：** 授权公共读取＋sys_user 范围分页；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.authenticationRejectsBadCredentialsAndNeverSerializesPasswordOrSessionVersion](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L389)

**正向演示：** 部门管理员查询只看到本部门及后代用户；ALL用户可看到全公司。

**反向演示：** 指定兄弟部门的deptId，应403。

常见错误：

- 403 OUT_OF_SCOPE：指定范围外部门
- 400 INVALID_ARGUMENT：分页参数不合法
- 503 INTEGRITY_FAILURE：返回用户或组织签名异常

**老师可能问：** 范围过滤应该由前端还是后端完成？

**回答要点：** 后端查询就限制部门集合，前端筛选不能作为权限防线。


<a id="A06"></a>
## A06 · 查询一个用户

`GET /api/v1/users/{id}` · 主讲：A · 业务接口

**入口与调用链：** [ManagementController.user](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L46) → [AccountService.detail](../../../src/main/java/com/whatname/iam/service/AccountService.java#L137) → DomainRepository.get(Account)；AccessService.scope。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** 当前列表直接使用分页数据；详情接口可用 IDEA HTTP Client 演示

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_USERS_VIEW。

**范围：** 目标用户必须在本人部门范围内。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标用户ID |

```http
GET /api/v1/users/2
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "101",
  "username": "demo_user",
  "realName": "演示用户",
  "phone": "13800000002",
  "employeeNo": "DEMO001",
  "deptId": "2",
  "status": 1,
  "source": 1,
  "employmentType": 1,
  "managementScope": "DEPT_TREE",
  "createdAt": "2026-10-25T10:00:00"
}
```

### 怎么实现、改哪些表

1. 校验功能权限。
2. 读取目标用户。
3. 验证目标签名和目标部门范围后返回实体。

**数据库：** 授权公共读取＋sys_user；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 查询已知范围内员工，返回用户对象。

**反向演示：** 查询兄弟部门员工，应403。

常见错误：

- 404 RESOURCE_NOT_FOUND：用户不存在
- 403 OUT_OF_SCOPE：目标部门越界

**老师可能问：** 知道别人的ID能不能直接查看？

**回答要点：** 不能，服务端仍按目标所属部门检查范围。


<a id="A07"></a>
## A07 · 创建临时账号

`POST /api/v1/users/temporary` · 主讲：A · 业务接口

**入口与调用链：** [ManagementController.createUser](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L51) → [AccountService.temporary](../../../src/main/java/com/whatname/iam/service/AccountService.java#L145) → DomainRepository.get(Department)/save(Account)；JPA自动生成ID。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 创建临时账号

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_USERS_WRITE。

**范围：** 新账号部门必须在操作者范围内，且祖先链不冻结、不合并。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| username | string | 必填 | 最长64且唯一 |
| realName | string | 必填 | 最长64 |
| phone | string | 必填 | 规范化后1[3-9]开头的11位手机号且唯一 |
| deptId | ID | 必填 | 目标部门 |
| password | string | 必填 | 8–64位，含字母和数字 |

```http
POST /api/v1/users/temporary
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "username": "demo_temp",
  "realName": "演示外部人员",
  "phone": "13800000002",
  "deptId": "2",
  "password": "ExampleOnly123!"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "101",
  "username": "demo_temp",
  "realName": "演示用户",
  "phone": "13800000002",
  "employeeNo": null,
  "deptId": "2",
  "status": 1,
  "source": 2,
  "employmentType": null,
  "managementScope": "DEPT_TREE",
  "createdAt": "2026-10-25T10:00:00"
}
```

### 怎么实现、改哪些表

1. 检查权限、部门范围和部门有效性。
2. 设置 source=2，无工号、无人员类型，默认DEPT_TREE。
3. 密码哈希后保存账号、签名和日志；不自动分配角色。

**数据库：** 新增 sys_user；写 sys_data_signature、sys_operation_log；查询 sys_dept。

**事务与锁：** 一次普通写事务；新增用户无旧行锁，唯一约束处理重复账号和手机号。

**签名与日志：** USER_CREATE。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.temporaryAccountLifecycleClearsRolesAndInvalidatesSessions](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L410)

**正向演示：** 创建账号后由D分配角色，再登录演示。

**反向演示：** 不分配角色就登录，只能证明身份，不应获得业务菜单。

常见错误：

- 403 OUT_OF_SCOPE：创建到范围外
- 422 BUSINESS_RULE_VIOLATION：部门链冻结/合并
- 409 DUPLICATE_RESOURCE：账号或手机号重复

**老师可能问：** 临时账号需要另一张表吗？

**回答要点：** 不需要，source=2 区分来源，使用同一套角色与权限关系。


<a id="A08"></a>
## A08 · 修改临时账号信息

`PATCH /api/v1/users/{id}` · 主讲：A · 业务接口

**入口与调用链：** [ManagementController.editUser](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L57) → [AccountService.updateTemporary](../../../src/main/java/com/whatname/iam/service/AccountService.java#L168) → DomainRepository.lock(Account)；实体脏检查保存修改。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 临时账号“编辑”

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_USERS_WRITE。

**范围：** 目标在范围内且 source=2。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标临时账号 |
| realName | string | 可选 | 给出时非空、最长64 |
| phone | string | 可选 | 给出时规范化并验证唯一 |

```http
PATCH /api/v1/users/2
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "realName": "更新后的演示人员"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "101",
  "username": "demo_temp",
  "realName": "演示用户",
  "phone": "13800000002",
  "employeeNo": null,
  "deptId": "2",
  "status": 1,
  "source": 2,
  "employmentType": null,
  "managementScope": "DEPT_TREE",
  "createdAt": "2026-10-25T10:00:00"
}
```

### 怎么实现、改哪些表

1. 锁目标并检查范围。
2. 拒绝非临时账号。
3. 只修改给出的姓名/手机号；不改工号、来源、部门或角色。

**数据库：** 修改 sys_user；更新签名并记日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** USER_EDIT。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 修改临时账号姓名，再查列表验证。

**反向演示：** 对HR员工调用，应422。

常见错误：

- 422 BUSINESS_RULE_VIOLATION：正式员工不能走此接口
- 409 DUPLICATE_RESOURCE：手机号重复
- 403 OUT_OF_SCOPE：目标越界

**老师可能问：** 为什么正式员工不能在这里改？

**回答要点：** 正式员工由HR维护，避免本地手工数据与HR来源冲突。


<a id="A09"></a>
## A09 · 暂停或恢复临时账号

`PATCH /api/v1/users/{id}/status` · 主讲：A · 业务接口

**入口与调用链：** [ManagementController.status](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L65) → [AccountService.status](../../../src/main/java/com/whatname/iam/service/AccountService.java#L182) → DomainRepository.lock/clearRoles。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 暂停/恢复

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_USERS_WRITE。

**范围：** 目标在范围内且 source=2。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 临时账号ID |
| status | integer | 建议必填 | 1恢复、2暂停；代码省略时保留当前状态 |

```http
PATCH /api/v1/users/2/status
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "status": 2
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 锁用户并验签、检查范围及来源。
2. status=2时清空角色并增加会话版本。
3. status=1只恢复账号，不自动返还角色。

**数据库：** 修改 sys_user；暂停删除 sys_user_role；更新签名和日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** USER_STATUS。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.temporaryAccountLifecycleClearsRolesAndInvalidatesSessions](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L410)

**正向演示：** 暂停有角色的临时账号，核对角色清空且旧JWT失效。

**反向演示：** 恢复后未重新分配角色，业务请求仍应拒绝。

常见错误：

- 400 INVALID_ARGUMENT：不接受status=3
- 422 BUSINESS_RULE_VIOLATION：非临时账号

**老师可能问：** 恢复账号为什么没有恢复权限？

**回答要点：** 角色暂停时已删除，恢复只改变账号状态。


<a id="A10"></a>
## A10 · 管理者重置目标密码

`PUT /api/v1/users/{id}/password` · 主讲：A · 业务接口

**入口与调用链：** [ManagementController.password](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L83) → [AccountService.resetPassword](../../../src/main/java/com/whatname/iam/service/AccountService.java#L211) → DomainRepository.lock(Account)；setPassword。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 重置密码

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_PASSWORD_RESET。

**范围：** 目标必须在本人范围内；离职账号拒绝重置。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标用户ID |
| password | string | 必填 | 8–64位，含字母和数字 |

```http
PUT /api/v1/users/2/password
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "password": "ResetExample456!"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 校验重置权限与目标范围。
2. 拒绝status=3。
3. 更新哈希和会话版本，重新签名并写日志。

**数据库：** 修改 sys_user.password_hash/session_version；写签名和日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** PASSWORD_RESET。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 重置选定测试员工密码，用新密码登录。

**反向演示：** 目标旧JWT应失效；离职账号重置应422。

常见错误：

- 403 OUT_OF_SCOPE：越界
- 422 BUSINESS_RULE_VIOLATION：目标已离职
- 400 INVALID_ARGUMENT：弱密码

**老师可能问：** 重置需要知道目标原密码吗？

**回答要点：** 管理者不需要原密码，但必须具有专门权限及目标数据范围。


<a id="A11"></a>
## A11 · 检查后端进程

`GET /api/v1/system/ping` · 主讲：A · 运行检查

**入口与调用链：** [AuthController.ping](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L84) → [AuthController.ping](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L85) → 无Repository调用。

**持久层源码：** 无持久层调用。

**页面/调用方：** IDEA HTTP Client / 运维检查。

**权限：** 公开运行检查。

**范围：** 不返回用户或业务数据。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
GET /api/v1/system/ping
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "status": "UP"
}
```

### 怎么实现、改哪些表

1. 直接构造UP响应。

**数据库：** 不读写数据库。

**事务与锁：** 无业务事务。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 后端启动后请求返回UP。

**反向演示：** 数据库关闭时，此接口本身仍不能作为数据库就绪证明。

常见错误：

- 没有本接口专用业务错误；HTTP层与运行故障见公共说明。

**老师可能问：** ping成功能证明数据库能用吗？

**回答要点：** 不能，数据库连接由另一个检查接口验证。


<a id="A12"></a>
## A12 · 检查数据库连接

`GET /api/v1/system/database` · 主讲：A · 运行检查

**入口与调用链：** [AuthController.database](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L89) → [QueryService.database](../../../src/main/java/com/whatname/iam/service/QueryService.java#L82) → DomainRepository.query(count Account)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** IDEA HTTP Client / 运维检查。

**权限：** 公开运行检查。

**范围：** 仅返回连接结果，不返回员工数量。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
GET /api/v1/system/database
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "connected": true
}
```

### 怎么实现、改哪些表

1. 执行一个用户计数查询。
2. 查询成功返回connected=true；数据库异常交给统一异常处理。

**数据库：** 只读 sys_user 计数。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** MySQL正常时返回connected=true。

**反向演示：** 在独立环境停止数据库后请求，应该503。

常见错误：

- 503 AUTH_STATE_UNAVAILABLE：数据库不可访问

**老师可能问：** 为什么内部查数量却不返回数量？

**回答要点：** 这里只用查询确认数据库可用，对外只给运行状态。
