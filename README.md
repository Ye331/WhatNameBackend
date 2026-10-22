# 统一权限管理系统

Java 21 + Spring Boot + MyBatis + MySQL，Vue 前端。Controller → Service → Mapper 三层；RBAC0 功能权限与管理员任命、范围分别校验。

## 唯一入口

| 工作 | 位置 |
| --- | --- |
| 空库建表 | db/schema.sql，Compose 首次启动自动执行 |
| 本地设置、数据准备、初始化、测试 | scripts/dev.ps1 setup / data / init / recover / test |
| 个人配置模板 | config/application-local.example.yml |
| 手工联调 | [requests/system.http](requests/system.http) |
| 功能、演示、逐项要求和接口增补 | [docs/GUIDE.md](docs/GUIDE.md) |
| 测试和验证记录 | [docs/TESTING.md](docs/TESTING.md) |
| 原始设计依据 | [docs/contracts](docs/contracts/README.md) |

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

## 数据与初始化

原始 Excel 统一位于工作区 data/。空库首次启动前执行一次受控初始化：

```powershell
.\scripts\dev.ps1 init -SuperPhone <超级用户手机号>
```

它会整理 Excel、导入部门和员工、登记 IAM 与两个演示业务系统（OA、人力资源）的菜单和 API、创建角色和超级用户，并全部签名。超级用户密码在运行时输入，代码中没有默认密码。唯一管理员误删自己权限时，用 `.\scripts\dev.ps1 recover` 恢复超级用户。细节见 [GUIDE 第五节](docs/GUIDE.md)。

新库只执行 db/schema.sql；不要对现有数据库重复执行或删除 Docker 卷。

正式性能指标（鉴权 ≤20ms）尚未测试，见 GUIDE 的已知限制。
