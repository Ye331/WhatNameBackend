# 数据库脚本

`01_schema.sql`是数据库设计v3.1的空库建表快照，23张表，包含CHECK、触发器及DELIMITER。

已在Docker MySQL 8.0.46实际执行验证：23张表、16个触发器、49个CHECK；后端数据库连通接口返回200。
Docker Compose在首次空卷启动时由MySQL初始化入口执行本脚本，支持DELIMITER，无需手动重复执行。
不使用Docker时可用支持DELIMITER的工具手动执行。Spring Boot不自动建表；有同名表时应停止。

建表使用具备CREATE DATABASE/CREATE TABLE/TRIGGER权限的初始化账号；应用使用自己的本地数据库账号。
后续表结构调整由组织者统一更新设计和这里的脚本；已有库的调整另建迁移文件，不直接重跑建表。
测试/演示业务数据和签名由后续模块实现生成；骨架不预置超级账号、默认密码或虚假授权。

#B补充
### 新库（首次启动）
Docker Compose 启动时，会自动按文件名顺序执行 `db/` 下的 SQL 脚本：
- `01_schema.sql` — 建基础表
- `02_add_canonical_path_and_index.sql` — 为 `sys_api` 增加规范化路径与唯一索引

### 已有库
如果你在 02 脚本加入之前已经初始化了数据库，拉取最新代码后，需要手动执行一次 02 脚本，否则新代码在写入 API 时会因缺少字段报错。
执行命令（在项目根目录运行）：
docker exec -i whatname-iam-mysql-1 mysql -u iam_dev -p unified_permission < db/02_add_canonical_path_and_index.sql
按提示输入密码，执行完成后，请重启后端服务。
