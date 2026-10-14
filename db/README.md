# 数据库脚本

`01_schema.sql`是数据库设计v3.1的空库建表快照，23张表，包含CHECK、触发器及DELIMITER。

已在Docker MySQL 8.0.46实际执行验证：23张表、16个触发器、49个CHECK；后端数据库连通接口返回200。
Docker Compose在首次空卷启动时由MySQL初始化入口执行本脚本，支持DELIMITER，无需手动重复执行。
不使用Docker时可用支持DELIMITER的工具手动执行。Spring Boot不自动建表；有同名表时应停止。

建表使用具备CREATE DATABASE/CREATE TABLE/TRIGGER权限的初始化账号；应用使用自己的本地数据库账号。
后续表结构调整由组织者统一更新设计和这里的脚本；已有库的调整另建迁移文件，不直接重跑建表。
测试/演示业务数据和签名由后续模块实现生成；骨架不预置超级账号、默认密码或虚假授权。
