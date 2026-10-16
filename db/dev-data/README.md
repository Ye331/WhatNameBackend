# 部门本地开发数据

仅用于个人开发数据库，包含111条部门。原Excel保留原样，不包含员工电话或人员导入。`department-source.json`保留部门名称、上级名称、经理姓名的来源信息，经理姓名不用于创建或关联用户。`DEV-DEPT-R0002`等编码由原始Excel行号生成，**不是HR正式编码**。行号重新排布或名称/父子变化后应重新审查映射，不能据此猜测正式HR部门。

生成器只使用Python标准库，通过workbook关系找到“部门信息”的真实sheetData，忽略错误的dimension=A1。按原始行号确定顺序、父节点先输出，JSON记录每条编码、原始行号和父编码。

在仓库根目录执行（使用现有Python运行时，无需安装依赖）：

```powershell
python scripts/dev-data/build-department-data.py
python -m unittest discover -s scripts/dev-data -p 'test_*.py' -v
python scripts/dev-data/verify-department-seed.py --import-and-verify
```

最后一条会连接现有Docker Compose的个人MySQL，以容器内挂载的应用密码执行SQL，验证两次导入、全部父子/路径、冲突报错与事务回滚、业务状态保留。它不启动/停止服务、不建库、不删除部门。仅在明确要准备个人数据时手动执行；不接入Docker初始化或应用启动。

也可以把`department-seed.sql`通过MySQL客户端的批处理标准输入执行。**必须使用全新单连接、遇错停止，不加`--force`**。错误后断开连接才能确保未提交事务回滚和命名锁释放；GUI中运行时应显式ROLLBACK并断开连接，不能在错误后继续执行COMMIT。推荐使用上述验证脚本，避免不同SQL客户端的错误处理差异。

导入规则：

- `parent_id=0`表示根，内部ID由数据库自增分配，不指定固定主键。路径采用`/<根ID>/<子ID>/`，包含自身且以斜杠结尾，避免`/1/`与`/10/`混淆。当前团队文档只规定完整路径，未给分隔符，本数据与读取XML明确采用这一格式，后续组织写流程需保持一致。
- 新部门`manager_id=NULL, status=1, hr_version=0`，排序使用原始行号。先插入再在同一事务内用真实自增ID补齐路径，未提交中间值不对外作为最终数据使用。
- 按编码锁行，并检查原始名称、父ID、完整路径。已有编码不一致会输出冲突编码并触发CHECK错误，整次批处理断开后回滚，绝不覆盖现有组织结构。应用用户需有创建临时表权限，现有本地账号具备该权限。
- 已存在且一致的行完全不更新，负责人、冻结状态、版本、排序、时间戳均保留。命名锁只用于串行运行本开发种子，不能替代正式HR事务并发控制。
- SQL和JSON均为开发材料，不是表结构迁移。没有HR01/HR05、冻结业务、M5范围或D审计实现。

部门Java读取测试使用任务03维护的JUnit依赖，直接创建MyBatis会话，不启动Spring/auth、不写数据库。默认跳过真实数据库读取，明确启用后运行：

```powershell
$env:JAVA_HOME = 'C:\Users\zhu-y\.jdks\ms-21.0.12.1'
$env:DEPARTMENT_DB_TEST = 'true'
$env:DEPARTMENT_DB_PASSWORD = (Get-Content config/local-secrets/mysql_app_password.txt -Raw).Trim()
try { .\mvnw.cmd '-Dtest=DepartmentMapperTest' test }
finally { Remove-Item Env:DEPARTMENT_DB_PASSWORD; Remove-Item Env:DEPARTMENT_DB_TEST }
```

默认连接本地`127.0.0.1:3307/unified_permission`、`iam_dev`。如有调整，通过`DEPARTMENT_DB_URL`、`DEPARTMENT_DB_USERNAME`覆盖，密码仅放环境变量。并行任务期间先检查其他Maven进程并串行运行，不能同时使用共享target或运行clean。测试只针对这些独立测试编码，不假设全库仅有111部门。真实数据演进后，这些初始状态断言可能不再适用，应在受控开发数据环境验收。
