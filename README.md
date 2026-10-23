# 统一权限管理系统

Java 21 + Spring Boot + Spring Data JPA + MySQL，Vue 前端。Controller → Service → Repository 三层；RBAC0 功能权限与管理员任命、范围分别校验。

## 常用入口

| 工作 | 位置 |
| --- | --- |
| 空库建表 | db/schema.sql，Compose 首次启动自动执行 |
| 本地设置、数据准备、初始化、测试 | scripts/dev.ps1 setup / data / init / recover / test |
| 个人配置模板 | config/application-local.example.yml |
| 手工联调 | [requests/system.http](requests/system.http) |
| 功能、演示、逐项要求和接口增补 | [docs/GUIDE.md](docs/GUIDE.md) |
| 测试和验证记录 | [docs/TESTING.md](docs/TESTING.md) |
| 原始设计依据 | [docs/contracts](docs/contracts/README.md) |

## 启动（推荐 IntelliJ IDEA）

后端可以直接在 IDEA 中点击 Run / Debug 启动，不需要执行 Maven 启动命令。MySQL 需要事先运行；可以使用本机或已有的 MySQL 8，也可以使用项目提供的 Docker 数据库。

### 1. 导入项目

在 IDEA 中打开 `backend/pom.xml`，作为 Maven 项目导入并等待依赖同步。Project SDK、模块 SDK 与运行配置的 JRE 均使用 **Java 21**。

### 2. 准备个人配置（首次）

如果已有 `config/application-local.yml`，直接检查其中的数据库连接，保留现有密码与密钥设置。没有该文件时，在 IDEA 项目视图中复制 `config/application-local.example.yml`，命名为 `application-local.yml`，仍放在 `backend/config` 下。

如果旧个人配置只有数据库连接，请把模板中的 `iam` 段补入现有文件，保留原数据库密码。模板的 `data-signing.local-auto-generate: true` 启用本地签名密钥自动生成；`setup` 不会覆盖或补齐已有文件。

修改 `spring.datasource.url`、`username`、`password` 为实际数据库信息。模板使用 `127.0.0.1:3307/unified_permission`，适合项目 Docker 配置；本机 MySQL 常用 3306，应按实际端口填写。模板中的 `__MYSQL_APP_PASSWORD__` 必须替换成真实数据库密码。

已有项目数据库无需重建。使用全新数据库时，先由数据库客户端执行 `db/schema.sql` 建表，再完成下文的数据初始化；JPA 设置为 `ddl-auto=none`，不会自动创建表。

### 3. 创建 IDEA 运行配置

打开 `src/main/java/com/whatname/iam/IamApplication.java`，点击 `main` 方法旁的运行按钮。随后在 Run → Edit Configurations 中检查该配置；也可以手动创建 **Application** 配置，使用以下值：

| 配置项 | 填写内容 |
| --- | --- |
| Main class | `com.whatname.iam.IamApplication` |
| Use classpath of module | 后端 Maven 模块（包含 `IamApplication` 的模块） |
| JRE | Java 21 |
| Working directory | `backend` 的绝对路径，例如 `D:\WhatName\backend` |
| Program arguments | 日常启动留空 |
| Environment variables | 下表所列安全配置；沿用已有开发环境密钥 |

**工作目录必须指向 backend。** 项目通过 `optional:file:./config/application-local.yml` 读取个人配置；如果工作目录设成父目录，文件可能被静默跳过，转而使用默认连接参数。不需要设置 `local` profile，也不需要添加 `--spring.profiles.active=local`，因为文件已经由主配置显式导入。

个人配置模板通过下列环境变量读取密钥。在 IDEA 运行配置的 Environment variables 中逐项填写，Run 和 Debug 都会使用它们；只在另一个终端设置的变量不会自动传给已打开的 IDEA。

| 环境变量 | 内容 |
| --- | --- |
| `IAM_SMS_HMAC_KEY` | 短信验证码使用的 32 字节随机密钥，标准 Base64 编码 |
| `IAM_JWT_PRIVATE_KEY`、`IAM_JWT_PUBLIC_KEY` | RSA 密钥对；私钥 PKCS#8 DER Base64，公钥 X.509 DER Base64 |
| `IAM_JWT_ISSUER`、`IAM_JWT_AUDIENCE`、`IAM_JWT_KEY_ID` | 本地 JWT 签发者、受众与密钥标识，沿用当前环境设置 |
| `IAM_DATA_SIGNING_PRIVATE_KEY`、`IAM_DATA_SIGNING_PUBLIC_KEY` | 可留空：个人配置已启用本地自动生成数据签名密钥；已有环境可继续显式配置 |

本地首次启动会把数据签名密钥保存到 `config/local-secrets/data-signing.properties`，后续启动复用。不要删除该文件或每次重新生成密钥，否则已有签名无法校验。个人配置和包含密钥的 IDEA 运行配置不要提交到仓库。`scripts/dev.ps1 setup` 只准备 Docker 数据库密码与配置文件，不生成 JWT 或短信验证码密钥；数据签名密钥由后端首次启动生成。

### 4. 点击 Run 或 Debug

默认后端地址为 `http://localhost:8080`。浏览器打开：

- `/api/v1/system/ping`：检查后端进程是否正常响应。
- `/api/v1/system/database`：检查数据库连接；数据库不可达时返回 503。

进程启动成功不代表数据库、密钥与业务数据都已准备完成。登录及业务功能需要有效的安全配置和初始化数据。日常开发配置不要填写 `iam.init.*` 参数；初始化模式执行完成后会主动退出。

### 可选：使用 Docker 数据库或命令行启动

如果尚无 MySQL，可在 `backend` 目录准备项目自带数据库（首次设置）：

```powershell
.\scripts\dev.ps1 setup
docker compose up -d --wait
```

之后仍可回到 IDEA 点击 Run。已有本机或远程 MySQL 时不必执行这两条命令。Docker 默认数据库端口为 3307，修改时同步 `.env` 的 `MYSQL_PORT` 和个人配置的 datasource URL。

如果偏好命令行，也可以在配置好相同环境变量后执行：

```powershell
.\mvnw.cmd spring-boot:run
```

前端在 `frontend` 目录执行 `npm ci`、`npm run dev`。模板为 `.env.example`；平台编码必须与后端 `iam.permission.management-app-code` 一致。

## 数据与初始化

原始 Excel 统一位于工作区 data/。空库首次启动前执行一次受控初始化：

```powershell
.\scripts\dev.ps1 init -SuperPhone <超级用户手机号>
```

它会整理 Excel、导入部门和员工、登记 IAM 与两个演示业务系统（OA、人力资源）的菜单和 API、创建角色和超级用户，并全部签名。超级用户密码在运行时输入，代码中没有默认密码。唯一管理员误删自己权限时，用 `.\scripts\dev.ps1 recover` 恢复超级用户。细节见 [GUIDE 第五节](docs/GUIDE.md)。

新库只执行 db/schema.sql；不要对现有数据库重复执行或删除 Docker 卷。

正式性能指标（鉴权 ≤20ms）尚未测试，见 GUIDE 的已知限制。


## 持久层与学习入口

持久层已迁移为 Spring Data JPA / Hibernate：`repository` 包替代原持久层接口和查询 XML，普通查询使用 JPQL 或 `@Query`，复杂锁定、版本条件更新使用自定义 Repository 片段中的原生 SQL。

表结构继续由 `db/schema.sql` 管理，`spring.jpa.hibernate.ddl-auto=none`，本次迁移无需重建现有数据库。初始化保留专用 `JdbcTemplate`，与 JPA 通过同一 DataSource 共享事务。业务接口和数据库字段保持原契约。

完整讲解与练习见 [学习手册](../学习手册/README.md)，先读 [Spring Data JPA 与三层链路](../学习手册/01-三层链路.md)。测试入口与本次验证记录见 [TESTING.md](docs/TESTING.md)。

## 本阶段的简化范围

姓名和手机号使用明文演示数据，响应返回 `realName`、`phone`，不做展示脱敏；不再配置 `IAM_DATA_KEY` 或 `IAM_PHONE_HMAC_KEY`。密码哈希、手机号唯一约束、权限及手机号防篡改签名、超级用户兜底均保留。此项隐私保护范围与原始需求 PDF 不同，演示请使用虚构数据。

旧的空 Docker 数据库使用 [空库升级脚本](db/migrations/20261023-demo-identity.sql) 调整三张表，不需要删除 Docker 卷或重建其他表。脚本发现账号、短信或事件数据时会拒绝执行；已经有加密业务数据的数据库不能直接使用该脚本，需要另行解密迁移并重新签名。新库直接使用更新后的 `db/schema.sql`。
