# D · 角色定义与用户授权

职责：区分角色定义、角色菜单和用户角色三种操作。主讲 7 个接口。

核心源码：ResourceService 的角色方法、GrantService。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [D01](#D01) | `GET /api/v1/roles` | 查询角色目录 |
| [D02](#D02) | `POST /api/v1/roles` | 新建角色 |
| [D03](#D03) | `PATCH /api/v1/roles/{id}` | 修改或停用角色 |
| [D04](#D04) | `GET /api/v1/roles/{id}/menus` | 读取角色菜单ID集合 |
| [D05](#D05) | `PUT /api/v1/roles/{id}/menus` | 完整保存角色菜单权限 |
| [D06](#D06) | `GET /api/v1/users/{id}/roles` | 读取用户角色ID集合 |
| [D07](#D07) | `PUT /api/v1/users/{id}/roles` | 完整保存用户角色 |

HTTP示例文件：[本组请求](../../../requests/learning/D.http)。变量均为待填写占位，密码和密钥不要提交。

<a id="D01"></a>
## D01 · 查询角色目录

`GET /api/v1/roles` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.roles](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L112) → [ResourceService.roles](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L167) → DomainRepository.all(Role)、SignatureService.verify(role)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** RolesView.vue；UsersView.vue 角色选择

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_ROLES_VIEW 或 IAM_GRANTS_VIEW。

**范围：** 角色是全局目录，不按部门过滤。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
GET /api/v1/roles
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  {
    "id": "6",
    "roleCode": "DEMO_ROLE",
    "roleName": "演示角色",
    "roleDesc": null,
    "status": 1
  }
]
```

### 怎么实现、改哪些表

1. 允许角色查看者或授权查看者读取目录。
2. 读取全部角色并逐个验签。
3. 返回角色数组，包含停用角色；前端按用途筛选。

**数据库：** 授权公共读取＋sys_role及其菜单集合签名；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 角色管理和权限分配都能取得角色目录。

**反向演示：** 只有用户查看、没有角色/授权查看的账号应403。

常见错误：

- 403 PERMISSION_DENIED：缺少两种查看权限
- 503 INTEGRITY_FAILURE：角色或关联异常

**老师可能问：** 授权人员为什么可以读角色但不能编辑角色？

**回答要点：** 查看和编辑是不同菜单权限。


<a id="D02"></a>
## D02 · 新建角色

`POST /api/v1/roles` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.createRole](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L117) → [ResourceService.role](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L179) → DomainRepository.save 或 lock(Role)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** RolesView.vue

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_ROLES_WRITE。

**范围：** 全局角色定义，不按部门过滤。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| roleCode | string | 必填 | 最长64；更新不读取编码 |
| roleName | string | 必填 | 最长128；PATCH也必填 |
| roleDesc | string | 可选 | 最长512；省略清空 |
| status | integer | 可选 | 0/1；新建默认1，更新省略保持 |

```http
POST /api/v1/roles
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "roleCode": "DEMO_ROLE",
  "roleName": "演示角色",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "6",
  "roleCode": "DEMO_ROLE",
  "roleName": "演示角色",
  "roleDesc": null,
  "status": 1
}
```

### 怎么实现、改哪些表

1. 校验角色维护权限。
2. 创建或锁定原角色并验签。
3. 保存名称、描述和状态；签名包含现有菜单集合。

**数据库：** 写 sys_role、签名和日志；不直接写用户角色。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** ROLE_SAVE。

### 验证与答辩

**现有测试：** POST有HTTP创建回归；PATCH暂无专用正向HTTP用例。 [CourseworkFlowTest.roleAndResourceConfigurationsUseOneRbacChain](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L350)

**正向演示：** 创建角色，再由另一个API配置菜单，最后给用户分配角色。

**反向演示：** 停用角色后其原用户关联仍存在，但该角色不贡献权限。

常见错误：

- 400 INVALID_ARGUMENT：缺名称/状态非法
- 409 DUPLICATE_RESOURCE：roleCode重复

**老师可能问：** 创建角色会立即给某个用户权限吗？

**回答要点：** 不会，创建角色、配置菜单、分配给用户是三步。


<a id="D03"></a>
## D03 · 修改或停用角色

`PATCH /api/v1/roles/{id}` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.editRole](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L123) → [ResourceService.role](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L179) → DomainRepository.save 或 lock(Role)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** RolesView.vue

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_ROLES_WRITE。

**范围：** 全局角色定义，不按部门过滤。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标角色 |
| roleName | string | 必填 | 最长128；PATCH也必填 |
| roleDesc | string | 可选 | 最长512；省略清空 |
| status | integer | 可选 | 0/1；新建默认1，更新省略保持 |

```http
PATCH /api/v1/roles/2
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "roleName": "演示角色新名称",
  "status": 0
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "6",
  "roleCode": "DEMO_ROLE",
  "roleName": "演示角色",
  "roleDesc": null,
  "status": 1
}
```

### 怎么实现、改哪些表

1. 校验角色维护权限。
2. 创建或锁定原角色并验签。
3. 保存名称、描述和状态；签名包含现有菜单集合。

**数据库：** 写 sys_role、签名和日志；不直接写用户角色。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** ROLE_SAVE。

### 验证与答辩

**现有测试：** POST有HTTP创建回归；PATCH暂无专用正向HTTP用例。 暂无关联专用自动用例。

**正向演示：** 创建角色，再由另一个API配置菜单，最后给用户分配角色。

**反向演示：** 停用角色后其原用户关联仍存在，但该角色不贡献权限。

常见错误：

- 400 INVALID_ARGUMENT：缺名称/状态非法
- 409 DUPLICATE_RESOURCE：roleCode重复

**老师可能问：** 创建角色会立即给某个用户权限吗？

**回答要点：** 不会，创建角色、配置菜单、分配给用户是三步。


<a id="D04"></a>
## D04 · 读取角色菜单ID集合

`GET /api/v1/roles/{id}/menus` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.roleMenus](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L131) → [ResourceService.roleMenus](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L193) → DomainRepository.get(Role)/menus。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** RolesView.vue 配置权限回显

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_ROLES_VIEW。

**范围：** 全局角色配置。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 角色ID |

```http
GET /api/v1/roles/2/menus
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  "4"
]
```

### 怎么实现、改哪些表

1. 检查查看权限。
2. 验证角色及关联集合签名。
3. 返回当前menuId数组供复选框回显。

**数据库：** 读 sys_role、sys_role_permission、签名。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 读取已有角色，与配置权限对话框勾选项对应。

**反向演示：** 直接篡改该角色菜单关联，再读应503。

常见错误：

- 404 RESOURCE_NOT_FOUND：角色不存在
- 503 INTEGRITY_FAILURE：关联签名不符

**老师可能问：** 为何返回ID数组而不是菜单详情？

**回答要点：** 关联接口只负责关系；菜单详情由C的资源目录提供。


<a id="D05"></a>
## D05 · 完整保存角色菜单权限

`PUT /api/v1/roles/{id}/menus` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.replaceMenus](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L136) → [ResourceService.replaceMenus](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L199) → DomainRepository.lock(Role)/menus/query/save/remove。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** RolesView.vue；界面还需要资源查看权限用于选菜单

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_ROLE_PERMISSIONS_WRITE。

**范围：** 全局角色配置。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 角色ID |
| menuIds | ID array | 必填 | 最多10000个原始元素；只能选type=2菜单；[]清空 |

```http
PUT /api/v1/roles/2/menus
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "menuIds": [
    "4"
  ]
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 锁角色并验签。
2. 验证所有目标菜单签名和type=2。
3. 差异增删关联，签名新集合并记录日志。

**数据库：** 改 sys_role_permission；更新 sys_role 签名；写日志。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** ROLE_MENUS_REPLACE。

### 验证与答辩

**现有测试：** HTTP自动覆盖角色菜单保存；无效替换回滚另有用户角色服务级测试。 [CourseworkFlowTest.roleAndResourceConfigurationsUseOneRbacChain](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L350)

**正向演示：** 为角色配置业务菜单，其已有用户下一次请求获得权限。

**反向演示：** 数组含不存在菜单，整体拒绝且旧集合保留。

常见错误：

- 400 INVALID_ARGUMENT：传目录或非法ID集合
- 404 RESOURCE_NOT_FOUND：菜单不存在

**老师可能问：** 这是不是RBAC1角色继承？

**回答要点：** 不是，角色只直接关联菜单，没有角色到角色的关系。


<a id="D06"></a>
## D06 · 读取用户角色ID集合

`GET /api/v1/users/{id}/roles` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.userRoles](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L92) → [GrantService.roles](../../../src/main/java/com/whatname/iam/service/GrantService.java#L28) → DomainRepository.get(Account)/roles。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 角色对话框

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_GRANTS_VIEW。

**范围：** 目标用户必须在操作者范围内。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标用户 |

```http
GET /api/v1/users/2/roles
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  "6"
]
```

### 怎么实现、改哪些表

1. 检查授权查看权限。
2. 验证目标身份签名和部门范围。
3. 返回唯一用户角色关系中的roleId数组。

**数据库：** 读 sys_user、sys_user_role及用户签名。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 打开用户角色对话框，与当前关系一致。

**反向演示：** 查看其他部门范围的用户角色应403。

常见错误：

- 403 OUT_OF_SCOPE：越界
- 404 RESOURCE_NOT_FOUND：用户不存在

**老师可能问：** 为什么没有授权来源接口？

**回答要点：** 现在只有一份用户角色关系，来源体系已删除。


<a id="D07"></a>
## D07 · 完整保存用户角色

`PUT /api/v1/users/{id}/roles` · 主讲：D · 业务接口

**入口与调用链：** [ManagementController.userRoles](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L97) → [GrantService.replace](../../../src/main/java/com/whatname/iam/service/GrantService.java#L36) → DomainRepository.lock(Account)/roles/query/save/remove。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 单人角色配置

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_GRANTS_WRITE。

**范围：** 目标必须在范围内；非空角色集合要求用户可用且部门祖先有效。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标用户 |
| roleIds | ID array | 必填 | 最多500个原始元素；去重；角色必须存在并启用；[]清空 |

```http
PUT /api/v1/users/2/roles
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "roleIds": [
    "6"
  ]
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 检查功能权限，锁目标并检查部门范围。
2. 校验目标状态和全部角色。
3. 对唯一关系做差异增删，签名并记录前后集合。

**数据库：** 改 sys_user_role；更新用户签名；写日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** USER_ROLES_REPLACE。

### 验证与答辩

**现有测试：** 服务级自动覆盖并集、回滚、并发完整保存及自我撤权。 [CourseworkFlowTest.multiRoleUnionImmediateRevokeAndOaRepeat](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L260)；[CourseworkFlowTest.tamperingFailsAndInvalidReplacementRollsBack](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L428)；[CourseworkFlowTest.competingSavesRemainCompleteAndSigned](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L457)；[CourseworkFlowTest.selfRevocationNeedsNoIndependentAdministratorAndPersistsOtherGrants](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L448)

**正向演示：** 为测试用户加两个角色，再取消一个；有效权限为剩余角色并集。

**反向演示：** 包含一个不存在角色，整体失败且原关系不变。

常见错误：

- 403 OUT_OF_SCOPE：越界
- 422 BUSINESS_RULE_VIOLATION：非空授权给停用/冻结目标或选停用角色
- 404 RESOURCE_NOT_FOUND：角色不存在

**老师可能问：** 两个管理者同时保存会怎样？

**回答要点：** 锁同一个用户行后依次保存，最后得到后提交的完整集合，没有旧页面版本冲突检测。
