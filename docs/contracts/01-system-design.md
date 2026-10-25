# 系统设计

## 一条请求

Controller 接收参数 → 管理拦截器检查当前用户及 IAM 菜单/API 映射 → Service 执行业务与部门范围检查 → Repository 使用 JPA 读写 MySQL → 返回统一 ApiResponse。

身份接口只校验本人身份；HR/OA 集成接口各自校验服务密钥；业务演示接口使用同一个 RBAC0 计算器。公开接口仅登录及运行检查，未登记的管理接口默认拒绝。

## 代码入口

| 文件 | 阅读重点 |
|---|---|
| entity/Domain.java | 11 张表的实体，关联使用显式 ID |
| repository/AccountRepository.java | Spring Data JPA 用户查询 |
| repository/DomainRepository.java | JPQL、分页、持久化、目标行锁 |
| service/AccessService.java | 身份、部门范围、角色权限并集、菜单/API 判定 |
| service/AccountService.java | 登录、临时账号、状态、范围、密码 |
| service/GrantService.java | 唯一用户角色关系及完整集合保存 |
| service/BatchService.java | 同步循环，每人一个事务 |
| service/ResourceService.java | 应用、角色、菜单、API 和关联 |
| service/OrganizationService.java | HR、冻结、调岗、离职、合并 |
| service/SignatureService.java | 固定格式 Ed25519 签名和验签 |
| service/AuditService.java | 简单持久操作记录 |
| service/InitializationService.java | 全量初始化和本地恢复 |

## 权限规则

功能权限与数据范围分别回答“能做什么”和“能管理谁”。没有独立的管理员身份；也不根据用户名或角色编码直接放行。初始管理角色通过真实关联获得 IAM 权限。

菜单权限计算：当前账号有效 → 应用有效 → 部门及祖先没有冻结或合并 → 读取用户角色 → 取启用角色菜单并集 → 检查菜单和祖先状态 → 检查目标 API 是否属于这些菜单。

目录仅供导航，不能授予角色。API 按方法与路由模板登记，拒绝同应用同方法的歧义模板。鉴权接口的 route 是服务端匹配后的路由模板，不是浏览器地址。

## 事务

普通业务使用 @Transactional。用户角色、角色菜单、菜单 API 集合更新时锁定对应用户/角色/菜单一条记录，验证旧签名，按差异增删关联，更新签名及日志，一起提交。保持未变关联可避免先插入后删除引起的唯一键冲突。

不带 expectedVersion，后保存覆盖先保存。没有全表锁和 SERIALIZABLE。READ_COMMITTED 用于读取已提交的新权限；JWT 不携带权限快照。已经开始执行的请求不强行中断，后续新请求读取新权限。

批量服务没有外层事务，通过另一个 Service 的 Spring 代理逐人调用事务方法。业务错误逐项返回；数据库或签名故障停止并报错，先查询状态再重试。

## 签名与日志

签名覆盖用户身份、手机号、范围、状态、会话版本和角色 ID 集合；角色覆盖菜单集合；菜单覆盖 API 集合；组织、应用、API 覆盖其关键字段。验签失败不修复、不重签放行。

日志与正常业务修改同事务。密码或服务密钥不写日志。登录失败先返回内部结果使日志提交，Controller 再报告 401。日志不使用签名封印或完整事实快照。
