# B · 在线鉴权与平台自我管理

职责：RBAC0 计算、菜单输出、IAM 自我鉴权和业务演示。主讲 3 个接口。

核心源码：AccessService、ManagementInterceptor、QueryService.menus/check。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [B01](#B01) | `GET /api/v1/auth/menus` | 查询本人某应用的菜单 |
| [B02](#B02) | `POST /api/v1/auth/check` | 外部业务后端在线鉴权 |
| [B03](#B03) | `GET /api/v1/demo/{appCode}/{feature}` | 演示业务接口逐次鉴权 |

HTTP示例文件：[本组请求](../../../requests/learning/B.http)。变量均为待填写占位，密码和密钥不要提交。

## B必须额外主讲的公共链路

IAM自我管理不是额外一个HTTP接口，而是所有管理API入口都执行的公共机制。走读 [ManagementInterceptor.preHandle](../../../src/main/java/com/whatname/iam/common/config/ManagementInterceptor.java#L36) → [AccessService.requireApi](../../../src/main/java/com/whatname/iam/service/AccessService.java#L137) → [AccessService.allowed](../../../src/main/java/com/whatname/iam/service/AccessService.java#L120) → [AccessService.menus](../../../src/main/java/com/whatname/iam/service/AccessService.java#L81)。

演示同一管理页面：只读角色没有维护按钮；即使手工调用写接口也403；撤掉IAM角色后本人也失去管理能力。没有sys_admin或用户名放行。

<a id="B01"></a>
## B01 · 查询本人某应用的菜单

`GET /api/v1/auth/menus` · 主讲：B · 业务接口

**入口与调用链：** [AuthController.menus](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L39) → [QueryService.menus](../../../src/main/java/com/whatname/iam/service/QueryService.java#L25) → DomainRepository.roles/menus/get；补祖先目录。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** DemoView.vue 选择OA/HR；api.js用于身份刷新时另调用/auth/me

**权限：** 本人 Bearer JWT，不要求另有 IAM 管理菜单权限。账号状态和会话版本仍必须有效。

**范围：** 仅本人；按指定应用计算业务权限，不使用“管理其他人”的部门范围。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| appCode | query string | 默认IAM | 已登记且启用的应用编码 |

```http
GET /api/v1/auth/menus?appCode=OA
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "appCode": "OA",
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

1. 根据JWT取得本人和目标应用。
2. AccessService.menus 取有效角色菜单并集，检查组织和资源状态。
3. 补齐导航祖先，返回菜单及本人信息。

**数据库：** 共同读取：sys_user、sys_dept、sys_application、sys_user_role、sys_role、sys_role_permission、sys_menu、sys_menu_api、sys_api、sys_data_signature。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 给测试账号分配演示角色后，OA返回4个菜单。

**反向演示：** 回收角色后重查，菜单为空；目录本身不能产生API权限。

常见错误：

- 404 RESOURCE_NOT_FOUND：应用不存在
- 403 PERMISSION_DENIED：应用停用
- 401 INVALID_TOKEN：身份失效

**老师可能问：** 菜单为空会不会自动退出登录？

**回答要点：** 不会；身份和权限分开，账号有效仍可登录。


<a id="B02"></a>
## B02 · 外部业务后端在线鉴权

`POST /api/v1/auth/check` · 主讲：B · 业务接口

**入口与调用链：** [AuthController.check](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L61) → [QueryService.check](../../../src/main/java/com/whatname/iam/service/QueryService.java#L40) → DomainRepository.query(Api)、roles/menus/apis。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** 外部后端调用；管理前端不保存应用密钥

**权限：** 用户Bearer JWT ＋ X-App-Code ＋ 对应 iam.integration.apps.<code>.key 的 X-Service-Key。

**范围：** 应用由对应服务凭据绑定；不接受用HR/OA集成密钥代替应用鉴权密钥。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| method | string | 必填 | 请求方法，应与登记的大写方法一致 |
| route | string | 必填 | 最长256；传后端匹配后的路由模板，如/orders/{id} |
| X-App-Code | header | 必填 | 1–64位字母/数字/下划线/连字符 |
| X-Service-Key | header | 必填 | 对应应用专用密钥 |

```http
POST /api/v1/auth/check
Authorization: Bearer <JWT>
X-App-Code: OA
X-Service-Key: <OA_APP_KEY>
Content-Type: application/json

{
  "method": "GET",
  "route": "/api/v1/demo/OA/feature1"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "allowed": true,
  "reasonCode": "ALLOW"
}
```

### 怎么实现、改哪些表

1. Controller先校验应用凭据。
2. 验证用户身份、应用及目标API签名。
3. 按规范化路径+方法找到唯一API，再检查有效菜单是否映射它。
4. 返回allowed/reasonCode并记AUTH_CHECK。

**数据库：** 共同读取：sys_user、sys_dept、sys_application、sys_user_role、sys_role、sys_role_permission、sys_menu、sys_menu_api、sys_api、sys_data_signature。 成功执行判断时写 sys_operation_log。

**事务与锁：** QueryService普通事务，判断结果与日志同事务；日志失败不返回允许。异常身份/验签路径不保证有AUTH_CHECK日志。

**签名与日志：** AUTH_CHECK，含方法、模板、ALLOW/DENY。

### 验证与答辩

**现有测试：** HTTP自动覆盖允许、应用密钥不能替换为OA/HR集成密钥。 [CourseworkFlowTest.externalCheckBindsApplicationToItsOwnCredential](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L317)

**正向演示：** 正确应用凭据和角色应返回200且allowed=true。

**反向演示：** 回收角色后同JWT返回200且allowed=false；错误服务密钥返回401。

常见错误：

- 401 INVALID_SERVICE：密钥不匹配
- 401 INVALID_TOKEN：JWT失效
- 400 INVALID_ROUTE_TEMPLATE：模板非法
- 503 INTEGRITY_FAILURE：签名异常

**老师可能问：** HTTP200是否就可以放行业务？

**回答要点：** 不能，业务系统必须继续检查data.allowed；只有true才放行。


<a id="B03"></a>
## B03 · 演示业务接口逐次鉴权

`GET /api/v1/demo/{appCode}/{feature}` · 主讲：B · 业务接口

**入口与调用链：** [AuthController.demo](../../../src/main/java/com/whatname/iam/controller/AuthController.java#L74) → [QueryService.check](../../../src/main/java/com/whatname/iam/service/QueryService.java#L40) → 复用AccessService.allowed与QueryService.check。

**持久层源码：** 无持久层调用。

**页面/调用方：** DemoView.vue

**权限：** 本人 Bearer JWT，不要求另有 IAM 管理菜单权限。账号状态和会话版本仍必须有效。

**范围：** 对当前用户在路径指定应用中的菜单/API权限重新计算。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| appCode | path string | 必填 | 演示使用OA或HR |
| feature | path string | 必填 | feature1到feature4；以已登记路由为准 |

```http
GET /api/v1/demo/OA/feature1
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "appCode": "OA",
  "feature": "feature1",
  "message": "业务接口鉴权通过"
}
```

### 怎么实现、改哪些表

1. 组合本次实际演示路径。
2. 复用同一RBAC0计算器判断。
3. 允许返回业务演示结果，拒绝由Controller返回403。

**数据库：** 共同读取：sys_user、sys_dept、sys_application、sys_user_role、sys_role、sys_role_permission、sys_menu、sys_menu_api、sys_api、sys_data_signature。 写AUTH_CHECK。

**事务与锁：** 鉴权和日志在QueryService事务内完成；拒绝判断正常返回后由Controller抛403，因此该拒绝日志保留。

**签名与日志：** AUTH_CHECK。

### 验证与答辩

**现有测试：** HTTP自动覆盖多角色并集、撤权、冻结与解冻。 [CourseworkFlowTest.multiRoleUnionImmediateRevokeAndOaRepeat](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L260)；[CourseworkFlowTest.freezeTransferAndTermination](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L393)

**正向演示：** 业务演示点一个已授权OA功能，显示通过。

**反向演示：** 保留旧页面，另一会话撤权后点击原功能，应403。

常见错误：

- 403 PERMISSION_DENIED：业务权限不足
- 401 INVALID_TOKEN：账号失效

**老师可能问：** 前端菜单还没刷新，为什么也能阻止操作？

**回答要点：** 每次业务请求都重新校验，旧页面不能绕过后端。
