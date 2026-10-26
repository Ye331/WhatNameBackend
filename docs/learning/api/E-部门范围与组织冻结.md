# E · 部门范围与组织冻结

职责：部门管理范围与组织冻结；数据范围是跨接口公共规则。主讲 4 个接口。

核心源码：AccessService.within/scope、AccountService.scope、OrganizationService。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [E01](#E01) | `PUT /api/v1/users/{id}/scope` | 配置管理数据范围 |
| [E02](#E02) | `GET /api/v1/departments` | 查询本人可管理部门 |
| [E03](#E03) | `POST /api/v1/departments/{id}/freeze` | 冻结部门权限 |
| [E04](#E04) | `POST /api/v1/departments/{id}/unfreeze` | 解冻部门权限 |

HTTP示例文件：[本组请求](../../../requests/learning/E.http)。变量均为待填写占位，密码和密钥不要提交。

## E必须额外主讲的公共算法

没有独立管理员身份API。E负责 [AccessService.within](../../../src/main/java/com/whatname/iam/service/AccessService.java#L65)、[AccessService.scope](../../../src/main/java/com/whatname/iam/service/AccessService.java#L72)、[AccessService.departmentChain](../../../src/main/java/com/whatname/iam/service/AccessService.java#L52)，这些方法被A/D/G等人的接口调用。

画一棵三层部门树：标出本人部门、后代、兄弟部门；分别解释ALL、DEPT_TREE和冻结祖先的影响。范围不产生功能权限。

<a id="E01"></a>
## E01 · 配置管理数据范围

`PUT /api/v1/users/{id}/scope` · 主讲：E · 业务接口

**入口与调用链：** [ManagementController.scope](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L74) → [AccountService.scope](../../../src/main/java/com/whatname/iam/service/AccountService.java#L197) → DomainRepository.lock(Account)；AccessService.scope。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** UsersView.vue 范围

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_SCOPE_WRITE。

**范围：** 目标先在本人范围内；将目标设为ALL时，操作者自己也必须是ALL。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标用户 |
| managementScope | string | 必填 | ALL或DEPT_TREE |

```http
PUT /api/v1/users/2/scope
Authorization: Bearer <JWT>
Content-Type: application/json

{
  "managementScope": "DEPT_TREE"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 校验范围配置权限。
2. 锁目标并检查原部门范围。
3. 仅已有ALL范围的人可授予ALL；修改字段、签名、日志。

**数据库：** 改 sys_user.management_scope；更新签名、日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** USER_SCOPE。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.batchReportsPartialSuccessAndCommitsOnlyAllowedTargets](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L471)

**正向演示：** ALL操作者给测试人员设置DEPT_TREE，再用测试账号查看用户列表。

**反向演示：** DEPT_TREE操作者尝试把目标设置ALL，应403。

常见错误：

- 400 INVALID_ARGUMENT：范围枚举不合法
- 403 OUT_OF_SCOPE：目标越界或非ALL操作者授予ALL

**老师可能问：** ALL是否等于全部功能权限？

**回答要点：** 不是，ALL只扩大对象范围，每个操作仍需对应菜单权限。


<a id="E02"></a>
## E02 · 查询本人可管理部门

`GET /api/v1/departments` · 主讲：E · 业务接口

**入口与调用链：** [ManagementController.departments](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L222) → [OrganizationService.departments](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L40) → DomainRepository.all(Department)；AccessService.within/departmentChain。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** OrganizationView.vue；UsersView.vue 部门筛选

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_DEPTS_VIEW、IAM_USERS_VIEW、IAM_GRANTS_VIEW 任一。

**范围：** ALL返回全部；DEPT_TREE仅本人部门及后代。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| 无 | — | — | 无请求体与业务参数 |

```http
GET /api/v1/departments
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
[
  {
    "id": "2",
    "parentId": "1",
    "deptCode": "DEMO_DEPT",
    "deptName": "演示部门",
    "status": 1,
    "mergedIntoDeptId": null
  }
]
```

### 怎么实现、改哪些表

1. 验证允许的目录查看权限。
2. 对每个部门检查与本人部门的祖先关系。
3. 返回可见部门数组，供组织页和用户筛选器使用。

**数据库：** 读 sys_dept 与组织签名，不写表。

**事务与锁：** Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。

**签名与日志：** 不写日志。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 同一接口在组织页和用户部门选择器都可使用。

**反向演示：** 部门管理员不应得到兄弟部门；伪造前端选项仍会被后端范围校验挡住。

常见错误：

- 403 PERMISSION_DENIED：缺少任一种查看权限
- 503 INTEGRITY_FAILURE：组织环或签名异常

**老师可能问：** 怎样判断目标部门是否属于下属？

**回答要点：** 沿目标的parentId向上找；祖先链包含操作者deptId就属于范围。


<a id="E03"></a>
## E03 · 冻结部门权限

`POST /api/v1/departments/{id}/freeze` · 主讲：E · 业务接口

**入口与调用链：** [ManagementController.freeze](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L227) → [OrganizationService.freeze](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L51) → DomainRepository.lock(Department)；AccessService.scope。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** OrganizationView.vue

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_DEPTS_FREEZE。

**范围：** 目标部门必须在本人范围内。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标部门 |

```http
POST /api/v1/departments/2/freeze
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 验证功能权限。
2. 锁目标部门，检查范围。
3. 设置status=0，签名并记录；后续鉴权检查祖先链。

**数据库：** 改 sys_dept.status；更新组织签名和日志，不删除用户角色。

**事务与锁：** 一次普通事务，只锁目标部门一行；对后代的影响由鉴权递归检查实现。

**签名与日志：** DEPARTMENT_FREEZE，detail中的frozen为true。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.hrSyncTransferAndTerminationFollowSimplifiedRules](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L525)

**正向演示：** 冻结祖先部门后，后代测试员工业务请求拒绝；解冻后角色仍在并恢复允许。

**反向演示：** 冻结自己部门后可能失去解冻资格，应由未受该冻结影响且有权限的人操作。

常见错误：

- 403 OUT_OF_SCOPE：冻结/解冻越界
- 401/403：操作者账号失效或自身权限已受冻结影响

**老师可能问：** 冻结为什么不用逐人清空角色？

**回答要点：** 把状态放在部门节点，鉴权检查祖先链即可影响整个子树，解冻也不必重建授权。


<a id="E04"></a>
## E04 · 解冻部门权限

`POST /api/v1/departments/{id}/unfreeze` · 主讲：E · 业务接口

**入口与调用链：** [ManagementController.unfreeze](../../../src/main/java/com/whatname/iam/controller/ManagementController.java#L233) → [OrganizationService.freeze](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L51) → DomainRepository.lock(Department)；AccessService.scope。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** OrganizationView.vue

**权限：** Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：IAM_DEPTS_FREEZE。

**范围：** 目标部门必须在本人范围内。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 目标部门 |

```http
POST /api/v1/departments/2/unfreeze
Authorization: Bearer <JWT>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 验证功能权限。
2. 锁目标部门，检查范围。
3. 设置status=1，签名并记录；后续鉴权检查祖先链。

**数据库：** 改 sys_dept.status；更新组织签名和日志，不删除用户角色。

**事务与锁：** 一次普通事务，只锁目标部门一行；对后代的影响由鉴权递归检查实现。

**签名与日志：** DEPARTMENT_FREEZE，detail中的frozen为false。

### 验证与答辩

**现有测试：** 自动测试入口见以下源码；接口验收结合本节成功与失败步骤。 [SimplifiedBusinessTest.hrSyncTransferAndTerminationFollowSimplifiedRules](../../../src/test/java/com/whatname/iam/simplified/SimplifiedBusinessTest.java#L525)

**正向演示：** 冻结祖先部门后，后代测试员工业务请求拒绝；解冻后角色仍在并恢复允许。

**反向演示：** 冻结自己部门后可能失去解冻资格，应由未受该冻结影响且有权限的人操作。

常见错误：

- 403 OUT_OF_SCOPE：冻结/解冻越界
- 401/403：操作者账号失效或自身权限已受冻结影响

**老师可能问：** 冻结为什么不用逐人清空角色？

**回答要点：** 把状态放在部门节点，鉴权检查祖先链即可影响整个子树，解冻也不必重建授权。
