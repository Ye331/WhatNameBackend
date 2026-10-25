# API 使用说明

地址前缀 `/api/v1`。统一响应 `{success,code,message,requestId,data}`。账号和关联 ID 均使用字符串传输。无会话 401，无功能权限或越界 403，缺失对象 404，重复资源 409，签名失败 503。

完整路由见 [OpenAPI](03-openapi.yaml)，示例见 [system.http](../../requests/system.http)。

## 身份与业务鉴权

- POST `/auth/login`：`{loginType:USERNAME|PHONE,identifier,password}`，返回 token 和 user。
- GET `/auth/me`：当前用户及 IAM 菜单权限。
- GET `/auth/menus?appCode=OA`：本人指定应用菜单，补齐导航目录。
- POST `/auth/logout`：退出该账号全部会话。
- PUT `/auth/password`：`{oldPassword,password}`，成功后旧令牌失效。
- POST `/auth/check`：用户 Bearer JWT + `X-App-Code` + 对应应用 `X-Service-Key`，请求 `{method,route}`。route 为后端匹配的模板，appCode 来自服务凭据绑定。
- GET `/demo/{appCode}/{feature}`：OA/HR 的实际业务演示接口，同一用户 JWT，逐次鉴权。

## 用户与授权

- GET `/users`：keyword、deptId、status、source、page、pageSize，分页且限定部门范围。
- POST `/users/temporary`：username、realName、phone、deptId、password。
- PATCH `/users/{id}`：临时账号姓名/手机号。
- PATCH `/users/{id}/status`：`{status:1|2}`，仅临时账号；暂停清空角色，恢复不自动返还。
- PUT `/users/{id}/scope`：`{managementScope:ALL|DEPT_TREE}`。
- PUT `/users/{id}/password`：`{password}`。
- GET/PUT `/users/{id}/roles`：读取角色 ID 数组；保存 `{roleIds:[...]}`，空数组回收全部。
- POST `/grants/batch`：`{userIds:[...],roleId,operation:GRANT|REVOKE}`，最多 100 人，同步逐人结果。

## 资源与组织

角色：GET/POST `/roles`、PATCH `/roles/{id}`、GET/PUT `/roles/{id}/menus`，集合保存 `{menuIds:[...]}`。

应用：GET/POST `/applications`、PATCH `/applications/{id}`。

菜单：GET/POST `/applications/{app}/menus`、PATCH `/applications/{app}/menus/{id}`。

API：GET/POST `/applications/{app}/apis`、PATCH `/applications/{app}/apis/{id}`。

菜单接口：GET/PUT `/menus/{id}/apis`，保存 `{apiIds:[...]}`。

部门：GET `/departments`，POST `/departments/{id}/freeze` 或 `/unfreeze`。

日志：GET `/audit/operations?operation=LOGIN&page=1&pageSize=20`，按当时部门快照限制范围。

## HR/OA

服务密钥只保存在后端配置，不能放在 VITE_* 或浏览器。各集成请求用 `X-Service-Key`；不接受用户自行声明为 HR/OA。

HR/OA 集成密钥使用 `iam.integration.hr-key` / `oa-key`，外部应用鉴权密钥使用独立的 `iam.integration.apps.<应用编码>.key`，不能互换。

- POST `/integrations/hr/departments`：deptCode、deptName、parentId（0 为根）。
- POST `/integrations/hr/employees`：employeeNo、realName、phone、deptId、employmentType（1 正式/2 外包）、status（1/2）；新员工必须另传 password，更新不重置密码。
- POST `/integrations/hr/users/{id}/transfer`：`{deptId}`；实际部门改变才清空全部角色。
- POST `/integrations/hr/users/{id}/terminate`：离职并使旧令牌失效。
- POST `/integrations/hr/departments/{id}/merge`：`{targetDeptId}`，保留原角色。
- POST `/integrations/oa/roles`：`{userId,roleId,operation:GRANT|REVOKE}`，直接修改唯一角色关系。

没有 eventId、nonce、expectedVersion、授权来源或持久任务 ID。
