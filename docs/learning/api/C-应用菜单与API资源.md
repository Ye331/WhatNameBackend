# C · 应用菜单与API资源

职责：三层资源及菜单/API 映射。主讲 11 个接口。

核心源码：ResourceService 的资源方法、RouteTemplateUtils。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [C01](#C01) | `GET /api/v1/applications` | 查询应用列表 |
| [C02](#C02) | `GET /api/v1/applications/{app}/menus` | 查询应用菜单 |
| [C03](#C03) | `GET /api/v1/applications/{app}/apis` | 查询应用API |
| [C04](#C04) | `GET /api/v1/menus/{id}/apis` | 查询菜单关联API ID |
| [C05](#C05) | `POST /api/v1/applications` | 新建应用 |
| [C06](#C06) | `PATCH /api/v1/applications/{id}` | 修改应用 |
| [C07](#C07) | `POST /api/v1/applications/{app}/menus` | 新建菜单或目录 |
| [C08](#C08) | `PATCH /api/v1/applications/{app}/menus/{id}` | 修改菜单或目录 |
| [C09](#C09) | `POST /api/v1/applications/{app}/apis` | 新建API资源 |
| [C10](#C10) | `PATCH /api/v1/applications/{app}/apis/{id}` | 修改API资源 |
| [C11](#C11) | `PUT /api/v1/menus/{id}/apis` | 完整保存菜单接口关联 |

HTTP示例文件：[本组请求](../../../requests/learning/C.http)。变量均为待填写占位，密码和密钥不要提交。

<a id="C01"></a>
## C01 · 查询应用列表

`GET /api/v1/applications` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.apps](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L145) → [ResourceService.applications](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L35) → DomainRepository.all/query/get/apis；逐个返回对象验签。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue；角色选菜单也使用应用和菜单目录

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_VIEW。

**范围：** 资源为全局定义，不按操作者部门裁剪。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
GET /api/v1/applications
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  {
    "id": "3",
    "appCode": "DEMO_APP",
    "appName": "演示应用",
    "appDesc": null,
    "status": 1
  }
]
```

### 怎么实现、改哪些表

1. 验证资源查看权限。
2. 按路由参数读取资源或关联ID。
3. 验证相应资源签名后返回；列表不是分页接口。

**数据库：** 授权公共读取＋对应 sys_application/sys_menu/sys_api/sys_menu_api；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 打开应用资源页并选择应用，与接口返回对象对应。

**反向演示：** 无资源查看权限的账号应403。

常见错误：

- 404 RESOURCE_NOT_FOUND：路径对象不存在
- 503 INTEGRITY_FAILURE：资源或关联签名异常

**老师可能问：** 这里为什么没有部门范围过滤？

**回答要点：** 应用资源是全局定义，管理资格由对应IAM功能权限控制。


<a id="C02"></a>
## C02 · 查询应用菜单

`GET /api/v1/applications/{app}/menus` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.menus](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L164) → [ResourceService.menus](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L42) → DomainRepository.all/query/get/apis；逐个返回对象验签。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue；角色选菜单也使用应用和菜单目录

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_VIEW。

**范围：** 资源为全局定义，不按操作者部门裁剪。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| app | path ID | 必填 | 应用ID或菜单ID，以路由为准 |

```http
GET /api/v1/applications/2/menus
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
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
```

### 怎么实现、改哪些表

1. 验证资源查看权限。
2. 按路由参数读取资源或关联ID。
3. 验证相应资源签名后返回；列表不是分页接口。

**数据库：** 授权公共读取＋对应 sys_application/sys_menu/sys_api/sys_menu_api；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 打开应用资源页并选择应用，与接口返回对象对应。

**反向演示：** 无资源查看权限的账号应403。

常见错误：

- 404 RESOURCE_NOT_FOUND：路径对象不存在
- 503 INTEGRITY_FAILURE：资源或关联签名异常

**老师可能问：** 这里为什么没有部门范围过滤？

**回答要点：** 应用资源是全局定义，管理资格由对应IAM功能权限控制。


<a id="C03"></a>
## C03 · 查询应用API

`GET /api/v1/applications/{app}/apis` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.apis](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L186) → [ResourceService.apis](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L50) → DomainRepository.all/query/get/apis；逐个返回对象验签。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue；角色选菜单也使用应用和菜单目录

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_VIEW。

**范围：** 资源为全局定义，不按操作者部门裁剪。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| app | path ID | 必填 | 应用ID或菜单ID，以路由为准 |

```http
GET /api/v1/applications/2/apis
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  {
    "id": "5",
    "appId": "3",
    "apiCode": "DEMO_API",
    "apiName": "查看演示",
    "apiPath": "/demo/items/{id}",
    "canonicalPath": "/demo/items/{}",
    "httpMethod": "GET",
    "status": 1
  }
]
```

### 怎么实现、改哪些表

1. 验证资源查看权限。
2. 按路由参数读取资源或关联ID。
3. 验证相应资源签名后返回；列表不是分页接口。

**数据库：** 授权公共读取＋对应 sys_application/sys_menu/sys_api/sys_menu_api；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 打开应用资源页并选择应用，与接口返回对象对应。

**反向演示：** 无资源查看权限的账号应403。

常见错误：

- 404 RESOURCE_NOT_FOUND：路径对象不存在
- 503 INTEGRITY_FAILURE：资源或关联签名异常

**老师可能问：** 这里为什么没有部门范围过滤？

**回答要点：** 应用资源是全局定义，管理资格由对应IAM功能权限控制。


<a id="C04"></a>
## C04 · 查询菜单关联API ID

`GET /api/v1/menus/{id}/apis` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.menuApis](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L208) → [ResourceService.menuApis](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L142) → DomainRepository.all/query/get/apis；逐个返回对象验签。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue；角色选菜单也使用应用和菜单目录

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_VIEW。

**范围：** 资源为全局定义，不按操作者部门裁剪。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 应用ID或菜单ID，以路由为准 |

```http
GET /api/v1/menus/2/apis
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  "5"
]
```

### 怎么实现、改哪些表

1. 验证资源查看权限。
2. 按路由参数读取资源或关联ID。
3. 验证相应资源签名后返回；列表不是分页接口。

**数据库：** 授权公共读取＋对应 sys_application/sys_menu/sys_api/sys_menu_api；无写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 打开应用资源页并选择应用，与接口返回对象对应。

**反向演示：** 无资源查看权限的账号应403。

常见错误：

- 404 RESOURCE_NOT_FOUND：路径对象不存在
- 503 INTEGRITY_FAILURE：资源或关联签名异常

**老师可能问：** 这里为什么没有部门范围过滤？

**回答要点：** 应用资源是全局定义，管理资格由对应IAM功能权限控制。


<a id="C05"></a>
## C05 · 新建应用

`POST /api/v1/applications` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.createApp](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L150) → [ResourceService.application](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L58) → DomainRepository.save 或 lock(Application)；实体脏检查。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 新建/编辑应用

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局资源操作。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| appCode | string | 必填 | 最长64；只在创建时读取，更新不改编码 |
| appName | string | 必填 | 最长128；PATCH也必须提供 |
| appDesc | string | 可选 | 最长512；省略或null清空 |
| status | integer | 可选 | 0/1；省略保持原值，新建默认1 |

```http
POST /api/v1/applications
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "appCode": "DEMO_APP",
  "appName": "演示应用",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "3",
  "appCode": "DEMO_APP",
  "appName": "演示应用",
  "appDesc": null,
  "status": 1
}
```

### 怎么实现、改哪些表

1. 检查写权限。
2. 创建或锁定并验签原对象。
3. 保存名称/描述/状态；IAM应用不能停用。
4. 签名并记录APPLICATION_SAVE。

**数据库：** 写 sys_application、sys_data_signature、sys_operation_log。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** APPLICATION_SAVE。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 创建演示应用或修改其名称，再查询应用列表。

**反向演示：** 更新时只传status但缺少appName，会400；停用IAM会422。

常见错误：

- 400 INVALID_ARGUMENT：缺少appName等必填字段
- 409 DUPLICATE_RESOURCE：应用编码重复
- 422 BUSINESS_RULE_VIOLATION：停用IAM

**老师可能问：** PATCH在这里是不是任意单字段修改？

**回答要点：** 不是；当前实现仍要求appName，并会将省略的描述清空，演示应按真实代码说明。


<a id="C06"></a>
## C06 · 修改应用

`PATCH /api/v1/applications/{id}` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.editApp](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L156) → [ResourceService.application](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L58) → DomainRepository.save 或 lock(Application)；实体脏检查。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 新建/编辑应用

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局资源操作。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 应用ID |
| appName | string | 必填 | 最长128；PATCH也必须提供 |
| appDesc | string | 可选 | 最长512；省略或null清空 |
| status | integer | 可选 | 0/1；省略保持原值，新建默认1 |

```http
PATCH /api/v1/applications/2
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "appName": "演示应用新名称",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "3",
  "appCode": "DEMO_APP",
  "appName": "演示应用",
  "appDesc": null,
  "status": 1
}
```

### 怎么实现、改哪些表

1. 检查写权限。
2. 创建或锁定并验签原对象。
3. 保存名称/描述/状态；IAM应用不能停用。
4. 签名并记录APPLICATION_SAVE。

**数据库：** 写 sys_application、sys_data_signature、sys_operation_log。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** APPLICATION_SAVE。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 创建演示应用或修改其名称，再查询应用列表。

**反向演示：** 更新时只传status但缺少appName，会400；停用IAM会422。

常见错误：

- 400 INVALID_ARGUMENT：缺少appName等必填字段
- 409 DUPLICATE_RESOURCE：应用编码重复
- 422 BUSINESS_RULE_VIOLATION：停用IAM

**老师可能问：** PATCH在这里是不是任意单字段修改？

**回答要点：** 不是；当前实现仍要求appName，并会将省略的描述清空，演示应按真实代码说明。


<a id="C07"></a>
## C07 · 新建菜单或目录

`POST /api/v1/applications/{app}/menus` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.createMenu](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L169) → [ResourceService.menu](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L74) → DomainRepository.get/lock/save/query/apis。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 菜单页签

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局资源；父菜单必须与当前菜单属于同应用。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| app | path ID | 必填 | 所属应用 |
| menuCode | string | 必填 | 最长64；只创建时读取 |
| menuName | string | 必填 | 最长128；PATCH也必填 |
| menuType | integer | 可选 | 1目录/2权限菜单；默认2或保持原值 |
| parentId | ID或0 | 可选 | 省略/null/0设为顶级；更新时也如此 |
| path | string | 可选 | 最长256；省略清空 |
| status | integer | 可选 | 0/1；省略保持原值 |

```http
POST /api/v1/applications/2/menus
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "menuCode": "DEMO_MENU",
  "menuName": "演示菜单",
  "menuType": 2,
  "parentId": "0",
  "path": "/demo",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
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
```

### 怎么实现、改哪些表

1. 校验应用及原菜单。
2. 检查父链同应用、不能指向自己或形成循环。
3. 已有角色授权或API关联的菜单不能改成目录。
4. 保存父节点/路径/状态并签名、记录日志。

**数据库：** 写 sys_menu、签名、日志；检查 sys_application、sys_role_permission、sys_menu_api。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** MENU_SAVE。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 创建目录与子菜单，列表显示父子ID对应。

**反向演示：** 将父节点设为自己或自己的后代，应422。

常见错误：

- 400 INVALID_ARGUMENT：跨应用父节点或字段不合法
- 422 BUSINESS_RULE_VIOLATION：循环父链或已有授权菜单改目录
- 409 DUPLICATE_RESOURCE：同应用菜单编码重复

**老师可能问：** 目录是否直接允许API？

**回答要点：** 目录只导航；角色只能获得type=2菜单，接口关联也只允许权限菜单。


<a id="C08"></a>
## C08 · 修改菜单或目录

`PATCH /api/v1/applications/{app}/menus/{id}` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.editMenu](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L177) → [ResourceService.menu](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L74) → DomainRepository.get/lock/save/query/apis。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 菜单页签

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局资源；父菜单必须与当前菜单属于同应用。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| app | path ID | 必填 | 所属应用 |
| id | path ID | 必填 | 目标菜单 |
| menuName | string | 必填 | 最长128；PATCH也必填 |
| menuType | integer | 可选 | 1目录/2权限菜单；默认2或保持原值 |
| parentId | ID或0 | 可选 | 省略/null/0设为顶级；更新时也如此 |
| path | string | 可选 | 最长256；省略清空 |
| status | integer | 可选 | 0/1；省略保持原值 |

```http
PATCH /api/v1/applications/2/menus/2
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "menuName": "更新菜单",
  "menuType": 2,
  "parentId": "0",
  "path": "/demo",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
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
```

### 怎么实现、改哪些表

1. 校验应用及原菜单。
2. 检查父链同应用、不能指向自己或形成循环。
3. 已有角色授权或API关联的菜单不能改成目录。
4. 保存父节点/路径/状态并签名、记录日志。

**数据库：** 写 sys_menu、签名、日志；检查 sys_application、sys_role_permission、sys_menu_api。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** MENU_SAVE。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 创建目录与子菜单，列表显示父子ID对应。

**反向演示：** 将父节点设为自己或自己的后代，应422。

常见错误：

- 400 INVALID_ARGUMENT：跨应用父节点或字段不合法
- 422 BUSINESS_RULE_VIOLATION：循环父链或已有授权菜单改目录
- 409 DUPLICATE_RESOURCE：同应用菜单编码重复

**老师可能问：** 目录是否直接允许API？

**回答要点：** 目录只导航；角色只能获得type=2菜单，接口关联也只允许权限菜单。


<a id="C09"></a>
## C09 · 新建API资源

`POST /api/v1/applications/{app}/apis` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.createApi](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L191) → [ResourceService.api](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L113) → DomainRepository.query(同应用同方法)、save/lock(Api)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 接口资源页签

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局定义；应用归属不可通过更新迁移。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| app | path ID | 必填 | 所属应用 |
| apiCode | string | 必填 | 最长128；仅创建时读取 |
| apiName | string | 必填 | 最长128 |
| apiPath | string | 必填 | 最长256；规范路由模板 |
| httpMethod | string | 必填 | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS |
| status | integer | 可选 | 0/1 |

```http
POST /api/v1/applications/2/apis
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "apiCode": "DEMO_API",
  "apiName": "查看演示",
  "apiPath": "/demo/items/{id}",
  "httpMethod": "GET",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "5",
  "appId": "3",
  "apiCode": "DEMO_API",
  "apiName": "查看演示",
  "apiPath": "/demo/items/{id}",
  "canonicalPath": "/demo/items/{}",
  "httpMethod": "GET",
  "status": 1
}
```

### 怎么实现、改哪些表

1. 验证写权限及应用。
2. 规范化模板，并将变量名转为{}保存canonicalPath。
3. 拒绝与同应用同方法其他模板重叠的路径。
4. 保存并签名，记录API_SAVE。

**数据库：** 写 sys_api、签名和日志。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** API_SAVE。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.resourceConfigurationRoundTripsAndProtectsCrossApplicationBoundaries](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L485)

**正向演示：** 登记GET /demo/items/{id}并查询，canonicalPath应为/demo/items/{}。

**反向演示：** 再登记GET /demo/items/{itemId}或重叠模板，应409。

常见错误：

- 400 INVALID_ROUTE_TEMPLATE：模板格式错误
- 400 INVALID_ARGUMENT：方法非法
- 409 DUPLICATE_RESOURCE：路径歧义/重复

**老师可能问：** 仅换参数名为什么也重复？

**回答要点：** 请求路由含义没有变化，不能让一个实际请求对应两个权限资源。


<a id="C10"></a>
## C10 · 修改API资源

`PATCH /api/v1/applications/{app}/apis/{id}` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.editApi](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L199) → [ResourceService.api](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L113) → DomainRepository.query(同应用同方法)、save/lock(Api)。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 接口资源页签

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局定义；应用归属不可通过更新迁移。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| app | path ID | 必填 | 所属应用 |
| id | path ID | 必填 | API ID |
| apiName | string | 必填 | 最长128 |
| apiPath | string | 必填 | 最长256；规范路由模板 |
| httpMethod | string | 必填 | GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS |
| status | integer | 可选 | 0/1 |

```http
PATCH /api/v1/applications/2/apis/2
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "apiName": "查看演示",
  "apiPath": "/demo/items/{id}",
  "httpMethod": "GET",
  "status": 1
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "5",
  "appId": "3",
  "apiCode": "DEMO_API",
  "apiName": "查看演示",
  "apiPath": "/demo/items/{id}",
  "canonicalPath": "/demo/items/{}",
  "httpMethod": "GET",
  "status": 1
}
```

### 怎么实现、改哪些表

1. 验证写权限及应用。
2. 规范化模板，并将变量名转为{}保存canonicalPath。
3. 拒绝与同应用同方法其他模板重叠的路径。
4. 保存并签名，记录API_SAVE。

**数据库：** 写 sys_api、签名和日志。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** API_SAVE。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.resourceConfigurationRoundTripsAndProtectsCrossApplicationBoundaries](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L485)

**正向演示：** 登记GET /demo/items/{id}并查询，canonicalPath应为/demo/items/{}。

**反向演示：** 再登记GET /demo/items/{itemId}或重叠模板，应409。

常见错误：

- 400 INVALID_ROUTE_TEMPLATE：模板格式错误
- 400 INVALID_ARGUMENT：方法非法
- 409 DUPLICATE_RESOURCE：路径歧义/重复

**老师可能问：** 仅换参数名为什么也重复？

**回答要点：** 请求路由含义没有变化，不能让一个实际请求对应两个权限资源。


<a id="C11"></a>
## C11 · 完整保存菜单接口关联

`PUT /api/v1/menus/{id}/apis` · 主讲：C · 业务接口

**入口与调用链：** [ManagementController.replaceApis](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L213) → [ResourceService.replaceApis](../../../src/main/java/com/whatname/iam/service/ResourceService.java#L148) → DomainRepository.lock(Menu)/apis/query/save/remove。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** ResourcesView.vue 关联接口

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_RESOURCES_WRITE。

**范围：** 全局资源；菜单与每个API必须属于同应用。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 菜单ID |
| apiIds | ID array | 必填 | 最多10000个原始元素；去重排序；[]清空 |

```http
PUT /api/v1/menus/2/apis
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "apiIds": [
    "5"
  ]
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 锁菜单、验签并要求type=2。
2. 验证全部API及同应用约束。
3. 差异增删关联，重新签名菜单的API集合。

**数据库：** 改 sys_menu_api，更新 sys_menu 对象签名，写日志。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** MENU_APIS_REPLACE，记录前后ID集合。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.resourceConfigurationRoundTripsAndProtectsCrossApplicationBoundaries](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L485)

**正向演示：** 勾选两个API保存，再GET关联ID集合。

**反向演示：** 加入另一个应用的API，应拒绝且原集合保留。

常见错误：

- 400 INVALID_ARGUMENT：跨应用API或列表非法
- 422 BUSINESS_RULE_VIOLATION：目录不能关联API

**老师可能问：** 为什么集合签名挂在菜单上？

**回答要点：** 菜单签名覆盖其API ID集合，直接篡改关联表也能被发现。
