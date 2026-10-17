# 数据库脚本

`01_schema.sql`是数据库设计v3.2的空库建表快照，25张表（原23表及2张姓名索引表），包含CHECK、触发器及DELIMITER。

已在Docker MySQL 8.0.46实际执行验证：23张表、16个触发器、49个CHECK；后端数据库连通接口返回200。
Docker Compose在首次空卷启动时由MySQL初始化入口执行本脚本，支持DELIMITER，无需手动重复执行。
不使用Docker时可用支持DELIMITER的工具手动执行。Spring Boot不自动建表；有同名表时应停止。

建表使用具备CREATE DATABASE/CREATE TABLE/TRIGGER权限的初始化账号；应用使用自己的本地数据库账号。
后续表结构调整由组织者统一更新设计和这里的脚本；已有库的调整另建迁移文件，不直接重跑建表。
测试/演示业务数据和签名由后续模块实现生成；骨架不预置超级账号、默认密码或虚假授权。

2026-10-16：已有库先核对列/外键/数据后执行 `migrations/20261016_01_department_merge.sql` 一次。空库快照已包含该字段及约束。status仅冻结；merged_into_dept_id非空为合并空源去向。不得重跑建表或清数据。组织写采用SERIALIZABLE事务全表主键顺序FOR UPDATE（含间隙）串行保护，所有新增/移动/合并须遵循同一入口。

随后执行 `migrations/20261016_02_external_event_owner.sql` 一次，增加 nullable `claim_token`，不改已有事件状态。两项迁移均已于2026-10-16在个人MySQL核实落地，保留23表及111条基础部门；本次未重新建表。MySQL将未指定级联动作的合并外键报告为 `NO ACTION`，在InnoDB中限制删除。当前双份空库SQL含18个触发器，原16个保留，新增2个部门合并保护；49个CHECK不变。保留账号禁止删除约束，06独立测试账号离职、部门冻结、来源回收后保留，登记见 `docs/task06-organization-integration.md` 和仓库外交接。

2026-10-17：显式执行 `migrations/20261017_01_user_name_search.sql` 一次，仅新增 `sys_user_name_state`、`sys_user_name_token`。原23表及18触发器完整保留，新表增加2外键、1 CHECK。旧姓名不会在迁移或启动时解密；按 `docs/task17-name-search.md` 配置专用密钥和受控分批回填。
