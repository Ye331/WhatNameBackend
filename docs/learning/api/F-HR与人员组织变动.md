# F · HR与人员组织变动

职责：同步、调岗、离职、部门合并。主讲 5 个接口。

核心源码：IntegrationController 的 HR 方法、OrganizationService。

[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)

建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。

| 编号 | 方法与路径 | 用途 |
|---|---|---|
| [F01](#F01) | `POST /api/v1/integrations/hr/departments` | 按部门编码同步组织 |
| [F02](#F02) | `POST /api/v1/integrations/hr/employees` | 按工号同步员工 |
| [F03](#F03) | `POST /api/v1/integrations/hr/users/{id}/transfer` | HR调岗 |
| [F04](#F04) | `POST /api/v1/integrations/hr/users/{id}/terminate` | HR离职 |
| [F05](#F05) | `POST /api/v1/integrations/hr/departments/{id}/merge` | HR合并部门 |

HTTP示例文件：[本组请求](../../../requests/learning/F.http)。变量均为待填写占位，密码和密钥不要提交。

<a id="F01"></a>
## F01 · 按部门编码同步组织

`POST /api/v1/integrations/hr/departments` · 主讲：F · 业务接口

**入口与调用链：** [IntegrationController.department](../../../src/main/java/com/whatname/iam/controller/IntegrationController.java#L22) → [OrganizationService.syncDepartment](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L61) → DomainRepository.query(按deptCode)、lock/save。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** 无普通管理页按钮；由HR后端或IDEA HTTP Client调用

**权限：** X-Service-Key 必须匹配 iam.integration.hr-key；不要求人类用户JWT或IAM管理菜单。

**范围：** 可信HR对全组织执行同步；不套用某个人类管理员的部门范围。调用方负责发送正确人员/组织信息。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| deptCode | string | 必填 | 最长64；用它查找已有部门 |
| deptName | string | 必填 | 最长128 |
| parentId | ID或0 | 可选 | 省略/null/0会设为根，不是保持原父节点 |

```http
POST /api/v1/integrations/hr/departments
X-Service-Key: <HR_SERVICE_KEY>
Content-Type: application/json

{
  "deptCode": "DEMO_DEPT",
  "deptName": "演示部门",
  "parentId": "1"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{
  "id": "2",
  "parentId": "1",
  "deptCode": "DEMO_DEPT",
  "deptName": "演示部门",
  "status": 1,
  "mergedIntoDeptId": null
}
```

### 怎么实现、改哪些表

1. 校验HR密钥。
2. 按编码找到对象；已有对象锁行验签。
3. 拒绝已合并部门再同步，检查父链不能成环。
4. 保存名字和父节点，签名和记录日志。

**数据库：** 新增/改 sys_dept；签名和日志。

**事务与锁：** 同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。

**签名与日志：** HR_DEPARTMENT_SYNC。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 同编码重复同步，保持同一部门ID且不产生重复部门。

**反向演示：** 把父节点设为后代，拒绝并保持原结构。

常见错误：

- 401 INVALID_SERVICE：HR密钥无效
- 422 BUSINESS_RULE_VIOLATION：合并部门或父链循环
- 404 RESOURCE_NOT_FOUND：父部门不存在

**老师可能问：** 省略parentId时为什么部门变成根？

**回答要点：** 当前代码将省略值解释为0，因此更新也要明确传期望父节点。


<a id="F02"></a>
## F02 · 按工号同步员工

`POST /api/v1/integrations/hr/employees` · 主讲：F · 业务接口

**入口与调用链：** [IntegrationController.employee](../../../src/main/java/com/whatname/iam/controller/IntegrationController.java#L30) → [OrganizationService.syncEmployee](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L86) → AccountRepository.findByEmployeeNo/findByUsername；DomainRepository.lock/get/save/clearRoles。

**持久层源码：** [AccountRepository](../../../src/main/java/com/whatname/iam/repository/AccountRepository.java#L1)；[DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** HR集成接口；用户页只展示结果

**权限：** X-Service-Key 必须匹配 iam.integration.hr-key；不要求人类用户JWT或IAM管理菜单。

**范围：** 可信HR对全组织执行同步；不套用某个人类管理员的部门范围。调用方负责发送正确人员/组织信息。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| employeeNo | string | 必填 | 最长64；用工号匹配用户 |
| realName | string | 必填 | 最长64 |
| phone | string | 必填 | 规范手机号且唯一 |
| deptId | ID | 必填 | 必须存在且未合并；可处于冻结状态 |
| password | string | 仅新建必填 | 8–64位含字母数字；更新不修改已有密码 |
| employmentType | integer | 可选 | 1正式/2外包；新建默认1，更新保持原值 |
| status | integer | 可选 | 1可用/2暂停；新建默认1 |

```http
POST /api/v1/integrations/hr/employees
X-Service-Key: <HR_SERVICE_KEY>
Content-Type: application/json

{
  "employeeNo": "DEMO001",
  "realName": "演示员工",
  "phone": "13800000002",
  "deptId": "2",
  "employmentType": 1,
  "status": 1,
  "password": "ExampleOnly123!"
}
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

1. 用工号查人，已有离职账号不允许此接口恢复。
2. 新建生成拼音首字母账号，重名追加手机号后四位和序号。
3. 已有人员实际换部门则先清空全部角色；status暂停时使旧会话失效。
4. 保存身份、签名和日志。

**数据库：** 新增/改 sys_user；实际换部门时清空 sys_user_role；更新签名和日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。 新建时不锁不存在的用户行，由唯一约束防重复。

**签名与日志：** HR_EMPLOYEE_SYNC。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 同工号更新姓名后仍是一人一账号；新员工创建后由D授权。

**反向演示：** 同工号改deptId，应清空原角色；新建缺密码应400。

常见错误：

- 401 INVALID_SERVICE：凭据错误
- 422 BUSINESS_RULE_VIOLATION：目标离职或部门已合并
- 409 DUPLICATE_RESOURCE：手机号/工号等冲突
- 400 INVALID_ARGUMENT：新建缺密码等

**老师可能问：** 工号和用户名谁是HR同步标识？

**回答要点：** 工号；用户名是系统生成的登录名，不能据此给同一员工建多个账号。


<a id="F03"></a>
## F03 · HR调岗

`POST /api/v1/integrations/hr/users/{id}/transfer` · 主讲：F · 业务接口

**入口与调用链：** [IntegrationController.transfer](../../../src/main/java/com/whatname/iam/controller/IntegrationController.java#L38) → [OrganizationService.transfer](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L119) → DomainRepository.lock(Account)/get(Department)/clearRoles。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** HR调用，无普通后台调岗按钮

**权限：** X-Service-Key 必须匹配 iam.integration.hr-key；不要求人类用户JWT或IAM管理菜单。

**范围：** 可信HR对全组织执行同步；不套用某个人类管理员的部门范围。调用方负责发送正确人员/组织信息。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | source=1且非离职员工 |
| deptId | ID | 必填 | 新部门存在且未合并 |

```http
POST /api/v1/integrations/hr/users/2/transfer
X-Service-Key: <HR_SERVICE_KEY>
Content-Type: application/json

{
  "deptId": "3"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 验证员工及目标部门签名。
2. 只允许HR来源、非离职账号。
3. 只有实际部门改变才清空全部角色并换部门。
4. 重新签名、记录；新部门后续手动授予角色。

**数据库：** 改 sys_user.dept_id；删该用户 sys_user_role；写签名和日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** HR_TRANSFER。

### 验证与答辩

**现有测试：** 服务级自动覆盖实际调岗和重复当前部门不清角色。 [CourseworkFlowTest.freezeTransferAndTermination](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L393)

**正向演示：** 调岗后业务与IAM管理角色都清空；重新授权后可访问新功能。

**反向演示：** 重复通知当前部门不应清空刚分配的新角色。

常见错误：

- 422 BUSINESS_RULE_VIOLATION：临时/离职账号或目标部门已合并
- 404 RESOURCE_NOT_FOUND：目标不存在

**老师可能问：** 只回收旧部门的角色还是全部？

**回答要点：** 本简化版没有来源区分，所以实际调岗清空全部旧角色。


<a id="F04"></a>
## F04 · HR离职

`POST /api/v1/integrations/hr/users/{id}/terminate` · 主讲：F · 业务接口

**入口与调用链：** [IntegrationController.terminate](../../../src/main/java/com/whatname/iam/controller/IntegrationController.java#L48) → [OrganizationService.terminate](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L134) → DomainRepository.lock(Account)/clearRoles。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** HR调用

**权限：** X-Service-Key 必须匹配 iam.integration.hr-key；不要求人类用户JWT或IAM管理菜单。

**范围：** 可信HR对全组织执行同步；不套用某个人类管理员的部门范围。调用方负责发送正确人员/组织信息。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | HR来源员工 |

```http
POST /api/v1/integrations/hr/users/2/terminate
X-Service-Key: <HR_SERVICE_KEY>
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 锁并验证HR员工身份。
2. 首次离职设status=3、清空角色、增加会话版本。
3. 重复离职不重复递增版本，仍保留账号及日志。
4. 不撤回此人过去给别人授予的角色。

**数据库：** 改 sys_user.status/session_version；清空其 sys_user_role；签名和日志。

**事务与锁：** 同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。

**签名与日志：** HR_TERMINATE。

### 验证与答辩

**现有测试：** 服务级自动覆盖角色清空、旧JWT失效和他人授权保留。 [CourseworkFlowTest.freezeTransferAndTermination](../../../src/test/java/com/whatname/iam/CourseworkFlowTest.java#L393)

**正向演示：** 离职后旧JWT和新登录均不可用，用户记录仍能追溯。

**反向演示：** 核对其他用户角色未因原授权者离职而被清空。

常见错误：

- 422 BUSINESS_RULE_VIOLATION：临时或初始化账号不能走HR离职
- 404 RESOURCE_NOT_FOUND：用户不存在

**老师可能问：** 为什么不物理删除账号？

**回答要点：** 保留身份ID与历史日志，能够追溯离职前操作。


<a id="F05"></a>
## F05 · HR合并部门

`POST /api/v1/integrations/hr/departments/{id}/merge` · 主讲：F · 业务接口

**入口与调用链：** [IntegrationController.merge](../../../src/main/java/com/whatname/iam/controller/IntegrationController.java#L56) → [OrganizationService.merge](../../../src/main/java/com/whatname/iam/service/OrganizationService.java#L147) → DomainRepository.lock(两部门)、query(Account/Department)、逐行lock。

**持久层源码：** [DomainRepository](../../../src/main/java/com/whatname/iam/repository/DomainRepository.java#L1)

**页面/调用方：** HR调用；普通组织页显示合并状态

**权限：** X-Service-Key 必须匹配 iam.integration.hr-key；不要求人类用户JWT或IAM管理菜单。

**范围：** 可信HR对全组织执行同步；不套用某个人类管理员的部门范围。调用方负责发送正确人员/组织信息。

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
| id | path ID | 必填 | 源部门 |
| targetDeptId | ID | 必填 | 目标部门；不能相同或为源部门后代 |

```http
POST /api/v1/integrations/hr/departments/2/merge
X-Service-Key: <HR_SERVICE_KEY>
Content-Type: application/json

{
  "targetDeptId": "3"
}
```

成功为 HTTP 200，外层统一 `{"success":true,"code":"OK","requestId":"…","data":…}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{}
```

### 怎么实现、改哪些表

1. 按ID顺序锁源和目标两行，避免相反合并请求锁顺序冲突。
2. 同源同目标重复请求直接返回；拒绝已合并对象和循环结构。
3. 移动直属员工与直属子部门，保留角色并重签受影响对象。
4. 源部门冻结并标记合并去向。

**数据库：** 改源部门status/merged_into_dept_id、直属子部门parent_id、直属员工dept_id；保留角色；签名和日志。

**事务与锁：** 一个普通事务，锁两部门及受影响人员/直属子部门；不使用全局管理员锁。

**签名与日志：** HR_DEPARTMENT_MERGE。

### 验证与答辩

**现有测试：** 无该接口的专用自动用例；按下列人工步骤验收。 暂无关联专用自动用例。

**正向演示：** 在小型演示部门合并后，核对人员归属、子部门父节点和原角色仍在。

**反向演示：** 目标设为源部门后代应拒绝，原数据不变。

常见错误：

- 400 INVALID_ARGUMENT：自己合并自己
- 422 BUSINESS_RULE_VIOLATION：循环或合并状态不允许
- 503 INTEGRITY_FAILURE：受影响对象签名异常

**老师可能问：** 合并与调岗为何角色处理不同？

**回答要点：** 已确认的业务规则不同：调岗清空，合并暂保留给新部门人工调整。
