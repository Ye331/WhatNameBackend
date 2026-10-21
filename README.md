# 统一权限管理系统

Java 21 + Spring Boot + MyBatis + MySQL，Vue 前端。Controller → Service → Mapper 三层；RBAC0 功能权限与管理员任命、范围分别校验。

## 唯一入口

| 工作 | 位置 |
| --- | --- |
| 空库建表 | db/schema.sql，Compose 首次启动自动执行 |
| 本地设置、数据准备、测试 | scripts/dev.ps1 setup / data / test |
| 个人配置模板 | config/application-local.example.yml |
| 手工联调 | [requests/system.http](requests/system.http) |
| 功能、演示、逐项要求和接口增补 | [docs/GUIDE.md](docs/GUIDE.md) |
| 测试和验证记录 | [docs/TESTING.md](docs/TESTING.md) |
| 原始设计依据 | [docs/contracts](docs/contracts/README.md) |
| 旧脚本、迁移、报告和计划 | [achieve](achieve/README.md) |

## 启动

在 backend 目录执行：

```powershell
.\scripts\dev.ps1 setup
docker compose up -d --wait
.\mvnw.cmd spring-boot:run
```

设置脚本生成数据库密码和 config/application-local.yml，已有文件不覆盖。按唯一模板配置 IAM_* 安全环境变量：三份独立 32 字节 Base64 对称密钥、RSA JWT 密钥对及 issuer/audience/key-id、独立 Ed25519 数据签名密钥对。私钥为 PKCS#8 DER Base64，公钥为 X.509 DER Base64。密钥本身不会创建账号。

已有个人配置应手工合并模板 iam 段，保留原数据库密码。src/main/resources/application.yml 只放框架默认值；config/application-local.yml 放个人设置，不能提交。默认数据库端口 3307、后端 8080；修改数据库端口须同步 .env 的 MYSQL_PORT 和本地 datasource URL。

前端在 frontend 目录执行 npm ci、npm run dev。唯一模板为 .env.example；平台编码必须与后端 iam.permission.management-app-code 一致。

## 数据与现有数据库

原始 Excel 统一位于工作区 data/。执行 scripts/dev.ps1 data，生成 target/prepared-data.json。默认调用 python，可用 -Python 指定路径。脚本只校验和整理来源，完整正式入库与初始管理员创建尚未完成。生成包含个人数据，仅存本地 target，不提交。旧的部门 SQL/JSON 种子已归档。

新库只执行 db/schema.sql；不要对现有数据库重复执行或删除 Docker 卷。旧库如需升级，核对实际结构后参考 [归档迁移](achieve/cleanup-20261021/db/02_add_canonical_path_and_index.sql)，不要重复执行已应用的变更。

演示主线是管理系统自身。受控初始化、超级用户恢复、真实页面全流程验收及正式性能指标仍未完成；测试夹具不能充当初始化。
