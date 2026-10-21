# C2-01 A/D 真实接入与事务基线 Implementation Plan

> 在当前对话顺序执行；用户已授权实现和验证，不启动并行修改。

**Goal:** 接通 C 管理/单人授权的 A JWT、D 会话/功能、签名及审计，明确登录依赖。
**Architecture:** 保留三层和现有端口；生产配置装配适配器，缺少依赖仍拒绝。持锁后验签/鉴权，业务、双签名、审计同事务。批量仅接通已有逐项身份，不实现恢复。
**Tech Stack:** Java 21、Spring Boot、MyBatis、MySQL Testcontainers、JUnit。
**Spec:** [阶段总计划](2026-10-18-c-stage2.md) C2-01、docs/D_STAGE1.md、docs/task12-c-integration.md 顶部更正。

**执行结果（2026-10-18）：**本清单完成；最终 verify 为 216 项通过、122 项外部数据库开关跳过，0 失败/错误。新增真实集成 32 项包含在总数内。A 登录端到端仍依赖阻塞，详见 [交接](../../C_STAGE2_HANDOFF.md)。

## 全局约束

- 不新增 HTTP、表或字段；不实施 OA、HR 回收、批量恢复及管理角色等级规则。
- 完整 JWT 不入审计；保留会话版本、有效期和 jti 摘要。身份不替代功能/资格/范围。
- grant_revision 变化同时重签 USER_IDENTITY / USER_GRANTS；管理员变化重签 ADMIN / ADMIN_DELEGABLE_ROLES 全部 changedAdminIds。
- 事务中复核；不补签旧数据，不异步审计，不缓存允许结果。

## 1. 测试与装配

文件：`service/grant/CStage2IntegrationIT.java`、既有两个 `IntegrationConfigurationTest`。

- [x] 新增隔离 MySQL + 随机内存密钥，使用生产 C/A-JWT/D 服务及生产配置；仅在 SQL 失败注入处使用测试拦截器。
- [x] 先运行授权闭环测试，观察当前默认 INVALID_TOKEN 拒绝；再替换配置和增加 `service/admin/integration` / `service/grant/integration` 适配器。
- [x] HTTP 授权后调用真实 D 菜单/API 判定，精确回收最后来源后验证空集合双签名及拒绝。

```java
var granted = grants.grant(request());
assertThat(allowed()).isTrue();
grants.revoke(new RevokeRequest(sid(20), sid(30), granted.grantSourceId(), "finished", "v2"));
assertThat(allowed()).isFalse();
```

## 2. 会话、证据和事务

- [x] 覆盖缺失/畸形/过期 JWT、旧 sv、注销 jti、缺功能、越界、缺签/篡改；断言无来源、无版本推进、无成功审计。
- [x] `Identity` 仅做 A 密码学认证，不在 M5 之前读取用户；`Functions` 在锁后调用 D currentUser + requireFunction，记录事务内证据。
- [x] 后台仅凭已读取的持久任务/项建立执行上下文；无请求本身不是可信后台身份。每项重新检查当前账号和功能。
- [x] 审计白名单包含来源完整前后事实、资格、原因、会话/功能及签名引用；自回收使用变更前的功能证据。
- [x] SQL 注入第二个重签失败和审计 INSERT 后失败；验证来源、关系、版本、两份签名和成功日志均回滚。

```java
assertThatThrownBy(() -> grants.grant(request())).isInstanceOf(BusinessException.class);
assertThat(revision()).isEqualTo(1);
assertThat(count("sys_user_role_source")).isZero();
assertThat(audits()).isZero();
```

## 3. 当前读和锁序

文件：`AdminStateLoader` 的生产完整性适配、资源锁 Mapper、C HTTP 入口及 `BatchWorker`。

- [x] C 写入口拒绝加入已有事务，避免旧 RR 快照；批量保持既有 REQUIRES_NEW 逐项事务。
- [x] M5 排序锁后、首次普通查询前锁应用/菜单/API/映射；之后验签、会话及功能检查，直到提交持有锁。
- [x] 使用数据库锁等待观测与 latch 控制并发，证明等待任命/功能/资源变更提交后拒绝，不用 sleep 作为同步证据。
- [x] 记录管理、批量、A 事件三条锁序；HR/OA 的反向锁序保持依赖阻塞，不拼接人工入口。

## 4. 验证和交接

- [x] JDK 21 下执行普通回归及 `-Pintegration-tests`；记录实际失败/跳过，保留独立日志目录，不 clean。
- [x] 更新总计划、`docs/C_STAGE2_INTEGRATION.md` 和 `docs/C_STAGE2_HANDOFF.md`，明确真实 JWT/C/D 与未接通的 A 登录、B 写入签名、服务认证。
- [x] `git diff --check`；不自动提交或推送。源码基线 805db4c，阶段计划原为用户已有未跟踪文件。
