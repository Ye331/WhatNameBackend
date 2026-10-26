# 统一权限管理系统 · 课程简化版

Java 21 + Spring Boot + Spring Data JPA/Hibernate + MySQL 8 + Vue 3。保留 RBAC0、全量基础数据、HR/OA 简单联动及基础签名，数据库缩减为 11 张表。

管理平台自己的权限也通过“用户 → 角色 → 菜单 → API”管理。没有独立管理员身份、等级、任命或可授权清单。

## 首次准备

使用数据库 unified_permission_coursework，默认端口 3308，并通过 Docker 数据卷持久化。

在 backend 目录，确认 Java 21、Python 3 与 Docker 可用：

```powershell
.\scripts\dev.ps1 setup
docker compose up -d --wait
.\scripts\dev.ps1 init -SuperPhone <初始化管理账号手机号>
```

初始化/恢复密码在运行时交互输入，至少 8 位且含字母和数字。管理用户名默认 superadmin，可通过 -SuperUser 指定。初始化完成后进程退出，结果保存在 target/initialization-report.json。

setup 创建本地配置 config/application-coursework.yml。它生成本地数据库密码和四个集成密钥；JWT 与数据签名密钥在首次运行时保存到 config/local-secrets/，后续复用。请保留并备份这些文件，不提交仓库。

本机 MySQL 也可以使用：先建立一个新的空数据库，在该库执行 db/schema.sql，再修改 application-coursework.yml 中的连接。JPA 不自动建表。启动前应完成建表和初始化。

## 日常启动

### 全程使用 IDEA

项目提供“初始化数据库（仅首次）”和“启动后端”两个运行配置，均指定 Java 21 和 backend 工作目录。

首次初始化：Run → Edit Configurations → 选择“初始化数据库（仅首次）”，在 Environment variables 中填写 IAM_INIT_SUPER_PASSWORD（8–64 位，含字母和数字）；Program arguments 中的 super-phone 为演示手机号，可按需修改。运行后会导入全量数据并退出。随后选择“启动后端”，日常只运行此配置。

仓库提供 .run/initialize.example.run.xml 空密码模板，请在 IDEA 中复制为自己的本地初始化配置再填写密码。本地运行配置已加入 Git 忽略，只有启动配置与空密码模板会提交。初始化使用工作区 data/runtime/prepared-data.json；它是原始 Excel 整理的全量数据包，Maven clean 不会删除。修改原始 Excel 后须重新整理数据包再初始化新空库。

初始化以非 Web 模式执行，不占用 8080。数据库容器需要事先运行；初始化不替代建库，已有业务数据时拒绝重复导入。

在 IDEA 打开 pom.xml，SDK/JRE 使用 Java 21，运行 IamApplication，工作目录必须是 backend。

也可运行：

```powershell
.\mvnw.cmd spring-boot:run
```

前端在 frontend 目录执行 npm ci、npm run dev，访问前端提示的地址。JWT 只保存在浏览器内存，刷新页面需要重新登录。

## 全量数据与恢复

原始文件在工作区 data/，完整导入 111 个部门、10,000 名员工、18 个来源应用和 359 条来源功能，并额外建立 IAM 与两套可运行演示应用。员工没有共享默认密码，通过管理页面重置选定员工密码后演示。

管理员误删自身权限时，在本地运行：

```powershell
.\scripts\dev.ps1 recover -SuperUser superadmin
```

只恢复受控初始化账号，需设置恢复密码，不开放 HTTP 恢复接口；不自动修复签名异常数据。

## 验证

```powershell
.\scripts\dev.ps1 test
.\scripts\dev.ps1 test -FullImport
```

测试代码位于 `src/test/`。每次运行先 clean，再执行测试并生成覆盖率报告。

常规业务回归使用隔离 H2 数据库，每个场景重新初始化，不接触本地 MySQL。FullImport 额外测试真实来源全量导入及恢复，结果不代表 MySQL 性能。`-Integration` 运行独立 MySQL 容器验证；Docker 不可用时会显示跳过，不能算通过。范围和报告见 [测试说明](docs/TESTING.md)。

## 阅读入口

- [项目设计约定](docs/PLAN.md)
- [功能范围](docs/FUNCTIONAL_SCOPE.md)
- [运行与演示](docs/GUIDE.md)
- [三层结构、权限与事务](docs/contracts/01-system-design.md)
- [接口](docs/contracts/02-api-spec.md)、[11 表设计](docs/contracts/04-database-design.md)
- [测试记录](docs/TESTING.md)
- [学习手册](docs/learning/README.md)
- [A–G分工与逐API答辩清单](docs/learning/分工-A-G与API清单.md)
