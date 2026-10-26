# 项目设计约定

项目使用 Java 21、Spring Boot、Spring Data JPA/Hibernate、MySQL、Vue 3，按 Controller → Service → Repository 分层。数据库共 11 张表。

- 用户 → 角色 → 功能菜单 → API，多个角色的权限取并集。
- IAM 自身使用同一套 RBAC0，管理能力由角色授权。
- 管理范围为 ALL 或 DEPT_TREE；范围限制操作对象，不授予功能权限。
- HR 同步人员与部门，处理调岗、离职和合并；OA 通知批准后的角色授予或撤销。
- 个人调岗清空角色，重复通知当前部门不清空新授权；部门合并保留角色。
- OA 和管理页面共同维护用户角色关系，撤销会移除该角色关联。
- 修改关联时锁定目标对象；完整集合保存以最后执行的保存为准。
- 改密、重置、暂停、离职和注销通过会话版本使已有凭证失效。
- 使用密码哈希、JWT、Ed25519 数据签名及操作日志。
- 全量基础数据和 OA、HR 演示功能用于验证权限效果。

数据库为 `unified_permission_coursework`，默认端口 3308。使用 `db/schema.sql` 建表，再在空表中执行初始化。详见 [数据库设计](contracts/04-database-design.md)。
