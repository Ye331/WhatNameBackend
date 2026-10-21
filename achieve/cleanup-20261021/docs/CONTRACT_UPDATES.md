# 当前兼容字段与配置增补

来源契约保留在 `contracts`，本页只记录整合后的兼容增补。

| 接口/配置 | 当前约定 |
| --- | --- |
| `GET /auth/menus` | 原 `appCode`、`menus` 不变；增加 `management`，只描述当前登录人。未任命返回 `{ "appointed": false }`；有效任命返回 `{ "appointed": true, "adminLevel": 1 }` 等。等级为 JSON 数字。摘要不能替代后端每次写入的会话、功能和范围校验。 |
| `GET /admins/me/scope` | 原权限及完整管理根返回不变。管理员页面仍使用此接口；资源/角色页面只需菜单接口的本人等级摘要。 |
| 菜单列表 | 保留原字段，补充 `menuCode`、`menuName`、`path`，用于显示与编辑回填。 |
| API 列表 | 保留原字段，补充 `apiName`。 |
| 资源/角色 PATCH | 必须包含实际修改字段，只有 `reason` 返回输入错误。 |
| 平台编码 | 后端统一使用 `iam.permission.management-app-code`，同时决定功能权限所属应用和禁止停用的平台应用；前端 `VITE_IAM_APP_CODE` 与之保持一致。旧的 `app.system-app-code` 不再使用。 |

角色仍不继承；本人管理员等级不自动赋予任何菜单权限。字段增补不新增数据库表、HTTP 接口或通用权限框架。
