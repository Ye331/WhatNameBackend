# Task09 本地员工校验与 HR02 输入准备

此工具只读取本机原表与04受控DEV部门映射，准备 HR02 初始化 UPSERT 输入。没有网络、数据库、密码、签名、nonce、JWT或默认允许开关。输出状态为 `PREPARED_NOT_IMPORTED`，实际导入数量始终为0。输入校验通过不能证明账号已创建、登录名可用、真实C/D接入或万人性能达标。

## 运行

在 `D:\Desktop\OOAD\WhatNameBackend` 运行。生成器及专属17项测试仅依赖 Python 3.10+ 标准库，无须安装依赖、修改POM或构建Maven。

```powershell
$pythonTask09 = 'C:\Users\zhu-y\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe'
$env:PYTHONIOENCODING = 'utf-8'
& $pythonTask09 -B scripts/dev-data/prepare-employee-events.py `
  --employment-type FORMAL --event-version 1 `
  --occurred-at '2026-10-16T14:00:00+08:00' --batch task09-dev-v1
& $pythonTask09 -B -m unittest discover -s scripts/dev-data -p test_employee_events.py -v
```

全部四个开发配置参数必填。`FORMAL` 是调用方显式选择的本轮开发配置，**原员工表没有employmentType列，不能据此将10000人认定为真实正式员工**。正式HR应提供真实类型；本工具当前支持显式全批类型，不实现逐SG类型清单。

默认输入：

- `D:\Desktop\OOAD\RBAC0\市政公司员工信息表.xlsx`
- `D:\Desktop\OOAD\RBAC0\市政公司部门信息表.xlsx`
- `D:\Desktop\OOAD\WhatNameBackend\db\dev-data\department-source.json`

可以用 `--employees`、`--departments`、`--mapping` 指定其他受控本机输入。单工作表自动选择；多工作表必须显式 `--sheet`。`--output-dir` 仅允许 `D:\Desktop\OOAD\tmp\task09-private` 或其子目录，拒绝源码/Git工作区、输入路径和符号链接输出文件。解析路径后再检查，不能用 `..` 或目录链接绕过。该目录是本地存放边界，不会自动配置Windows ACL或加密磁盘。

## 校验与组织匹配

按workbook关系定位真实sheet XML，遍历sheetData，不信任dimension。员工表A—F表头必须依次为姓名、用户名、分公司、部门、岗位、电话号码。所有数据行六字段均非空；拒绝首尾空白、控制/隐藏字符及超长值，不自行清洗到另一个身份。姓名/SG上限为64个UTF-16单位，与当前HR02 DTO一致；分公司、部门、岗位本地上限128；电话DTO上限32，同时严格要求11位ASCII大陆手机号 `1[3-9][0-9]{9}`。SG须为ASCII `SG[0-9]+` 且全局唯一，手机号也必须全局唯一。拒绝公式、错误/布尔/日期类型、重复行/单元格及额外非空列。

DEV映射先与部门原表SHA-256、每条source_row/名称/上级逐一校验，再验证编码、父引用、深度、单根和无环。保留04的部门编码与既有内部ID/path约定，不生成新组织ID。只使用部门原表A/C信息，不将负责人姓名关联为员工。

匹配规则：先按员工分公司字段定位总公司根或其直属节点，再在该节点直属部门中匹配部门原名或 `分公司-部门` 原名；部门字段与分公司自身同名时可归属分公司节点。仅一个候选才成功，多候选拒绝，未知/跨层级拒绝，不搜索全树后选第一条，不做模糊匹配。未知错误仅列受控映射中的候选code/path，不回显员工提供的原字符串。岗位只校验，不创建部门或授角色。

## 输出、重跑与失败

成功文件名为 `employees-<batch>-<dataset SHA前16位>.ndjson`，另有同名 `.manifest.json`。每行包含 `method=PUT`、`/api/v1/integrations/hr/employees/{sgNo}`、纯HR02 `body` 及独立 `provenance`（原工作表、员工行号、部门原表行号）。发送者只能发送 `body`，不能将整个包装行作为HTTP请求体。manifest保存三份输入SHA、完整dataset SHA、规则版本、显式配置、全量计数及输出SHA。可用 `Get-FileHash -Algorithm SHA256 <文件路径>` 复核，不能在公共日志中打印NDJSON。

`body` 严格只含：sourceSystem、eventId、eventVersion、sgNo、realName、phone、deptCode、employeeAction、employmentType、occurredAt。`sourceSystem=HR`，`employeeAction=UPSERT`。SG只作为稳定employee_no/path/body标识，不作为最终登录名。登录名由HR02按姓名拼音首字母及重名规则产生；工具不生成登录名、密码或角色。

`eventVersion` 是显式1到Java Long最大值的正整数，不读取数据库也不擅改当前hr_version。occurredAt须含秒和明确时区，偏移不超过±18:00。批次为1—16位ASCII字母/数字/下划线/横线，首位须字母或数字。工具没有“当前时间”默认值。此次14:00仅为显式开发事件时间，非原表提供的业务发生时刻，也不处理HR04预约离职。

规则版本 `task09-hr02-v1` 与输入文件字节SHA、表信息、类型、版本、时间、批次一起构成规范JSON，计算完整dataset SHA-256。eventId为 `dev-hr02:<batch>:<dataset SHA前32位>:<SG>`，最长123字符，满足DTO的128上限。摘要前缀为128位命名空间标识，完整SHA保留在manifest。变更任一输入字节或显式配置均产生新命名空间；修改生成规则必须递增规则版本并审查。资料变化应使用新批次，生成器不自动递增人员版本。

UTF-8、固定键序、紧凑JSON和LF使固定输入/参数输出字节、eventId及manifest重复一致。先完整验证全部行，再以同目录临时文件、flush/fsync、原子replace发布单个文件。NDJSON与manifest各自原子写入，不宣称两个文件是跨文件事务；I/O失败不能当作完整准备成功。验证失败只原子更新私有目录中的 `validation-errors.json`，返回非0，不生成部分事件，不删除/覆盖既有有效事件。错误记录仅含行号、字段名、错误类别、重复首次行号和必要受控组织元数据；不含姓名、完整电话或SG。不能依据遗留错误报告判断当前批次，须看本次退出码和对应manifest。

## 2026-10-16 实测

员工10000条，物理10001行；部门111条，物理112行。两表原始dimension均为A1。全部六字段非空/合法，SG10000唯一、手机号10000唯一，匹配10000、未知0、歧义0，覆盖110个组织节点，根总公司无直属员工。SG不是登录名，姓名重名不视为原表身份重复。

本轮配置即上面的FORMAL/version=1/含+08:00时间/task09-dev-v1。NDJSON为4541024字节、10000行，两次SHA-256相同：

```text
482c5b667310a160ba9ebbd0f360fb32b0eaaf079a98b230f20bfdf9474ea05a
```

实际输出：`D:\Desktop\OOAD\tmp\task09-private\employees-task09-dev-v1-888b46a68073ee3c.ndjson`。禁止提交或上传此文件。专属17项测试通过；仓库外 `D:\Desktop\OOAD\tmp\task09-verify-real.py` 使用已有openpyxl 3.1.5、reset_dimensions独立读取原表，全10000条逐字段/行号/组织层级/方法path/body/事件唯一性/DTO长度校验，并重复执行两遍生成器。结果保存在 `D:\Desktop\OOAD\tmp\task09-real-verification.json`；生产生成器不依赖openpyxl。该核对提取当前HR02 OpenAPI字段集合并校验已知DTO约束，不是完整OpenAPI验证器或真实HTTP调用。

## 接入界限与讲解索引

尚待正式HR类型/稳定部门编码，真实C/D服务认证、HR原始body字节Ed25519签名、防重放、身份/组织签名及审计。发送时D应对实际发送的UTF-8 body字节签名；重试使用新nonce、相同eventId及内容。准备文件不携带任何可信服务证明，不开启替身；实际导入仍为0，真实联调未完成。已有库可能存在手机号冲突、版本或生命周期限制、登录名碰撞/拼音限制、已合并或变更组织，需由真实HR02处理，文件准备不能证明这些业务条件已满足，也不能用UPSERT复活离职账号。

集中讲解调用链：`read_sheet → Organization → prepare → run → atomic_write`。对应源数据/字典校验、受控引用、纯输入处理、确定性与幂等、完整校验后发布、隐私与权限边界。教师追问：为什么A1不能证明只有一格？为什么同名办公室要带父节点？哈希如何让固定输入可复核、为何不能替代D签名？为什么10000条输入不能说已导入？为什么岗位与FORMAL参数不能变成真实授权/分类？用户掌握状态仍待A全部开发后集中讲解和复述验证。
