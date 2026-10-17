# Task17：US01 姓名包含搜索

本变更依据负责人 Ye331“当前不能按姓名搜索用户”的实际反馈，实现原 US01 `keyword=张` 示例的姓名包含含义。基线为 A 分支 `c47606f5b01467f537458c85eb6232af4f1a67a3`。这是 A 的实现补充；真实D尚未审批新索引的隐私、密钥或签名契约。没有修改四份来源快照或 `source-manifest.json`，它们及 Task10 的未完成说明保留历史事实；本报告覆盖其中姓名检索的旧实现状态。

来源清单内生成README的旧哈希和23表计数也只对应Task11时点；当前阅读索引README已注明Task17补充，其现行哈希取本任务白名单，不改历史来源清单冒称重新批准。

## 对外契约

HTTP 路由、DTO 和成功响应不变，仍是18个A操作、56个总操作。`GET /api/v1/users` 的非空 keyword 按以下 OR 语义命中一个用户，不产生重复用户：

| 字段 | 规则 |
|---|---|
| 姓名 | Java `strip` 去外层空白后做 Unicode NFC；区分大小写、重音、内部空格及标点；按 Unicode code point 的任意连续子串匹配 |
| 用户名 / SG | 保留原 SQL `LOCATE` 和数据库排序规则的行为，字面子串，无 LIKE 通配符 |
| 手机号 | 保留规范化大陆完整手机号的 HMAC 精确匹配，包括可选 `+86` |

例如“张小明”可以被“张”“张小明”“小明”“小”找到，不能被“张明”找到。`José` 与 `Jose\u0301` 归一化相同；姓名的 `jose`、`Jose` 不等于 `José`。`%`、`_`、反斜线均为字面字符；若它们同时存在于用户名，原用户名规则也会命中。未提供、空串及 Java `isBlank` 空关键词保持无关键词筛选；非空关键词先 strip。keyword 原上限仍为64个 UTF-16 code unit，姓名原 DTO 上限64不变；另要求 NFC 后不超过64个 code point，超过或不配对代理项返回400。部分 canonical composition exclusion 字符会在NFC后展开，这项边界确保最多2080条token；失败仍由原事务回滚，不截断姓名。姓名支持 code point 边界，不截断补充平面字符；资料写入原 `NamePinyin` 的可转换限制仍保留，未因此放宽不支持的汉字。

每个条件都与当前会话、D功能权限、真实C M5范围及 `deptId/status/source` 取交集。显式范围外部门403；不查、解密或统计范围外姓名。`count` / `list` 共用 XML filter，在同一个 REPEATABLE_READ 事务中读取，排序为稳定用户ID，SQL先筛选/计数再分页。只对本页允许范围内用户解密、验签及手机号HMAC复核，没有全库解密查询。

## 索引及失败处理

`NameSearchTokens` 对 NFC 姓名的每个非空连续子串计算**完整 HMAC-SHA256**，相同子串去重。没有普通哈希姓名列、拼音旁路、明文列、密文LIKE或截断token，也没有n-gram组合造成的候选误报。长度n的姓名最多n(n+1)/2条token，n≤64，单用户最多2080条；一字及全长子串都支持。

`sys_user_name_token` 的主键是 `(user_id,token)`，另有 `(token,user_id)` 索引。EXISTS谓词让用户名/SG/手机和姓名重叠命中仍只返回一行。全长HMAC按密码学碰撞不可行假设作等值索引，随机单次碰撞概率约2^-256；不是数学上无碰撞的编码。本页投影额外带内部 `nameSearchMatched`，解密后再次验证字面包含：碰撞或被污染的本页命中整次503 `INTEGRITY_FAILURE`，不删项、不调整total、不在分页后过滤。未返回页上的理论碰撞仍受上述密码学假设约束。

`sys_user_name_state` 存每个用户的索引generation、姓名**密文字节**SHA256和token数。每次非空keyword查询先对同一范围及dept/status/source交集检查状态，缺失、密文已变、generation/密钥已变、token数量不符均503；不会把未回填用户当成没有匹配。缺表是现有数据库不可用503。空keyword不依赖姓名索引。未就绪但被来源/状态/部门/范围排除的资料不影响本次查询。

generation是专用密钥对版本域的HMAC，含 `nfc-cs-substring-v1`；同样配置名下换密钥也会改变generation并安全拒绝旧索引。姓名继续用原AES-GCM格式保存，原手机/密码/JWT算法及用途均未改变。

## 密钥、隐私与D待复核边界

新增 `iam.security.name-search-key`，由操作者通过私有配置或 `config/task05-security.example.yml` 的 `IAM_NAME_SEARCH_KEY` 注入。必须是独立随机32字节的标准Base64，不得使用姓名、口令或其他用途的裸密钥。代码拒绝缺失/格式无效/长度错误，以及与已配置的AES、手机HMAC、短信HMAC密钥相同的值。token域和generation域不同；没有缺省测试密钥或明文降级。测试密钥仅在 `src/test` 隔离装配中生成或定义，从不输出。

索引额外泄露：同一generation内相同片段可关联；一字token会暴露频率分布；用户的token集合、数量和共同片段可辅助推断姓名长度、结构及身份。专用HMAC阻止无密钥离线直接枚举，但不消除频率/关联及有查询资格者的在线猜测，必须与原M5/D权限、数据库最小权限和运维保护共同考虑。索引是可重建派生数据，改名时旧token在同事务删除，保留原账号/事件/签名/审计历史；暂停、离职不删除当前姓名索引，继续按US01明确状态筛选。

现有就绪检查检测缺失、版本及数量/密文变化，**不是对索引全集的数字签名**。数据库写权限攻击者若等量替换token，可能制造漏检；本页伪命中检查只检测被返回的错误成员，不能证明所有未命中token未被篡改。真实D仍缺，既有生产必需验签/功能/审计依赖保持拒绝。以下仍须与D/负责人共同复核，未称已批准：索引额外泄露与规模、专用密钥托管和轮换窗口、将索引generation/完整集合或认证根绑定到D身份签名的格式、维护身份和审计主体、跨成员姓名写入必须调用同事务索引组件。当前 `NAME_INDEX_BACKFILL` 使用本机维护操作标识、null用户操作者和目标用户ID，实际D审计如何表达维护身份待联调，不能当作真实D验签成功。

`UserAccount.nameSearchMatched` 是仅供查询复核的临时投影，应排除在D的规范身份签名载荷之外；它不进入UserView或数据库用户列。当前测试签名使用明确字段清单，真实D规范载荷仍须共同核对。

## 写入及回填入口

`UserService.createTemporary` 在原账号事务插入后调用 `NameSearchIndex.replace`；HR02 `HrService.applyEmployee` 在新建或profile更新后、身份签名和审计之前调用同一组件。`replace` 强制有活跃事务，执行token删除、批量插入、state替换；调用方拥有新行或用户行锁。账号/profile、index、原签名和审计任一失败共同回滚。姓名仍在任何资料变更前经 Task15 `NamePinyin.variants` 校验。HR03/04仅改归属/状态/版本，不改姓名密文，不需要重建索引；原登录名稳定规则和五项负责人业务规则不变。

维护只通过显式 `NameIndexMaintenance` 非web运行入口执行。它检查实际数据源是带端口的 localhost/127.0.0.1 MySQL。必须明确给稳定ID范围，按 `id > after-id AND id <= through-id ORDER BY id LIMIT batch-size` 分批，batch 1..100，默认25；不在迁移、普通启动或US01查询中自动解密。

每行独立新事务：锁用户→必要身份验签→内存解密→生成/替换token→读取完整token集合验证→同事务审计→提交。只在成功后推进本次内存cursor；失败不返回虚假的成功checkpoint，已成功前行允许保留。修正故障后从上次已保存checkpoint重复本批即可，全部替换幂等。命令输出仅数字checkpoint/上界，不含姓名/手机/密钥；不生成明文中间文件。遇到不可解密或身份不可信的历史行应停下排查，不跳过、不伪签、不用空姓名填索引。

现有库操作顺序（显式由本机运维执行）：

1. 先检查原23表、18触发器和已有两迁移，在正确库执行 `db/migrations/20261017_01_user_name_search.sql` **一次**。仅新增两表、两外键、一个CHECK；保留原表/约束/历史。MySQL DDL不具备跨两表的事务回滚，若执行中断，先检查已建表及定义后从未完成语句继续，不用 `--force` 隐藏错误或重跑建表。新空库 `db/01_schema.sql` 已含两表，共25表。
2. 私有配置四把独立数据/HMAC密钥及原JWT配置，接入实际需要的身份验签和审计能力；禁止临时允许所有用户通过。
3. 构建当前可运行jar（用项目共享构建锁、不clean；先通过仓库自测入口）。在仓库目录，用已私下设置的环境变量和配置运行一批，例如：

```powershell
& "$jdk\bin\java.exe" -jar .\target\iam-backend-0.1.0-SNAPSHOT.jar `
  --spring.config.additional-location=file:./config/task05-security.example.yml `
  --spring.main.web-application-type=none `
  --iam.maintenance.name-index=true `
  --iam.maintenance.after-id=100 --iam.maintenance.through-id=200 `
  --iam.maintenance.batch-size=25
```

示例100/200只是范围占位，不是执行授权或生产账号事实。实际操作者取固定上界（在私有维护窗口只读确定），将成功checkpoint替换after-id继续，直到checkpoint不再前进；退出后应用上下文和连接池关闭。程序不提供HTTP维护接口。缺真实D的当前普通生产装配会按原接口安全拒绝，不能为回填关闭验签。Task17实际只在测试装配下对隔离旧资料运行过完整维护入口，**真实账号全量回填0**，没有把这项写成现库全范围已就绪。

个人开发库还保留历轮随机测试密钥生成的加密夹具，以及测试专门构造的不可解密资料；这些不是可用当前生产密钥全库回填的真实员工数据。维护命令对不可信/不可解密行会停止，不能为这些历史夹具伪造签名、填假token或宣称所有账号已就绪。部署前应对实际持有正确原加密密钥、验签材料的目标数据范围执行迁移与回填。

4. 回填与在线HR/US03可以并发，按同一用户锁串行；US01保持提交前旧profile/旧index或提交后新profile/新index的一致视图。密钥轮换须协调停用旧写入者、换key再显式重建范围；没有双generation无缝轮换承诺，混合generation期间查询503。不要直接删token解除就绪检查。

## 规模与验证说明

全部子串带来O(n²)写放大：10,000个64 code point且全片段不同的姓名，最坏20,800,000条token，加10,000条state。每条token包含8字节userId及64 ASCII十六进制字符，另有两棵B树和InnoDB开销；不能把姓名小数据测试外推为该上界的容量/延迟保证。典型三字中文最多6token、四字10token。一次关键词查询的就绪检查会扫描当前范围过滤后用户的state及token计数，成本O(范围内用户+索引条数)，随后count/list用EXISTS；仅本页最多500条姓名解密。后续可在保持失败安全的前提下由团队评估索引体积、就绪证明与性能优化。

测试和实际数据库/报告哈希随任务交接提供，真实MySQL/HTTP的C/D/SMS替身边界保持：AMySql为A真实业务加测试C/D，ACM5是真实A+本地C M5（7a449d0适配）及测试D；不是完整真实C HR/D联调。测试包含红轮、单字/全名/中间、拉丁/NFC/字面符号、范围及筛选、分页total、旧字段兼容、HR改名、US03、回填幂等和失败重试、真实SQL后的注入回滚、并发写/回填/读和密钥错误拒绝。没有增加测试专用生产header或默认允许逻辑。

## 本次实际结果（2026-10-17，北京时间）

修复前 `task17-red-http` 对 `keyword=张` 的真实HTTP返回200，但 `total` 为0（应为1），1 test / 1 failure / 0 error / 0 skipped，Maven=1。此前普通沙箱 `task17-red` 是Tomcat环回连接权限错误，未执行业务断言，不作为缺陷复现。

| 最终采用报告 | 类/用例 | failures / errors / skipped | 所属Maven运行退出码 | 运行完成时间 |
|---|---|---|---|---|
| `task17-selftest-final-02/Unit` | 6类，55 | 0 / 0 / 0 | 0 | 16:25:15 |
| `task17-amysql-final/AMySql` | M1 37 + HR 21 + Department 20 + Mapper 3 = 81 | 0 / 0 / 0 | 0 | 16:36:33 |
| `task17-targeted-final-38` 中的 M5 XML | Task12M5整类17（实际专项还执行HR21） | 0 / 0 / 0 | 0（整轮38项均通过） | 16:32:24 |

最终选取11份XML，**153个不重复JUnit testcase**。38项专项中的HR21已由最终完整AMySql报告覆盖，不再累加。新增13个方法（盲索引Unit5、M1 7、HR 1）；真实C M5原方法加强姓名子串/范围外不可解密/跨页total断言，未增加套件计数。独立XML重解析、唯一case及SHA清单在仓库外 `tmp/task17-verification-evidence.json`，验证所选测试来源和当前生产源码哈希一致。最终完整AMySql的summary为PASSED；Unit取自final-02中单独成功的Unit运行，不能把该容器目录的整体FAILED改称PASSED。

失败和修正均保留：

- `task17-selftest-final` 的Unit55和AMySql81成功，但初次ACM5为17 tests / 1 failure / 0 error / 0 skipped，Maven=1。测试夹具初始化调用维护入口多写一条审计，原“零审计”断言不成立；改为同事务准备夹具索引后，`task17-acm5-final` 的ACM5 17项通过。
- 收尾发现NFC规范化可能展开为超过64个code point，新增规范化后长度保护及Unit边界断言，确保2080条上界。随后 `task17-selftest-final-02` 的Unit55、M1 37、Department20、Mapper3通过；HR的固定“赵子龙”样本撞上历轮保留的拼音用户名/SG后缀，HR 21中1 failure，AMySql整体Maven=1，未执行其后ACM5。保留409业务规则，将新HR样本姓名加本轮唯一前缀；`task17-targeted-final-38` 的HR21及M5 17全通过，再以当前源码执行完整 `task17-amysql-final` 的81项，全部通过。
- `task17-targeted-01` 的一个失败是字面 `_` 同时匹配两名用户原有用户名的合理结果，修正测试预期后通过；中间轮、两项smoke、维护专项以及各重复case不叠加最终153项。没有修改业务规则绕过这些夹具问题。

16:36:56，显式共享锁下最终 `package -DskipTests` Maven=0，产出含NFC上界修正的当前可运行jar；这是打包验证，不增加测试计数。原23表schema主体和四份来源快照保持，迁移新增两表到25表；18个触发器不变，新增两个PK、两个FK、一个CHECK。全部测试后111部门指纹仍为 `f47ad78eb5e9e8d7904fb9b19f43d6e5ab0bed3a9ad7c44af2173804b9751d98`。806个新增测试账号463暂停/343离职，无有效来源、关系或任命；490个新增夹具部门冻结，历史保留。索引保留771份state、44,010条token，无孤儿；这些是隔离夹具/专项回填，不是806个账号都完成生产密钥回填的承诺。

只读EXPLAIN样例使用部门索引和token主键eq_ref，单次计划及行数保存在 `tmp/task17-index-plan-final-02.json`；样例不代表10,000人最坏规模性能，information_schema的表字节统计可能滞后，未据此宣称真实磁盘容量。16:37:57收尾18085—18090无监听、无其他iam_dev连接、共享锁重新独占并释放；没有停容器、杀进程或清库。无Git写入、推送、PR或合并。
