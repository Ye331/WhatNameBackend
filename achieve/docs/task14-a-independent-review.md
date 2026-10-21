# 子任务14：A十八HTTP与公共事件的独立实现审查

审查日期：2026-10-16（北京时间）。固定分支 `feat/backend-a-rbac0`，唯一业务基线 **`8fa95e4954f31782457f981dd5008e0a265d2e36`**。本文中的源码行号来自 `git show <SHA>:<path>`，不随13的测试参数化改动移动。本报告只交接审查，不实施修复、不安排其他任务。

## 1. 结论与证据强度

确认 **2项新增P2实现缺陷**，见第2节；未发现足以由本次证据确认的新增P0/P1越权、重复应用或部分提交缺陷。两个发现都是静态控制流确定，**未新发HTTP、未写库、未运行Java/Maven或故障注入**，不能称为“已跑出失败用例”。它们与已知真实C/D未接入、姓名搜索口径、真实员工导入和性能缺口分开。

逐段阅读四个Controller、Auth/User/Department/Hr/ExternalEvent Service、所调用Mapper及SQL、认证/密码组件，以及真实C管理适配链。79个固定对象的Git blob ID及SHA-256收录在 [静态证据](D:/Desktop/OOAD/tmp/task14-static-evidence.json)，其中也含逐方法测试定位；这些哈希用于复核来源，不等于每个辅助文件都进行了同等深度审查。

复用任务12冻结材料，重新解析XML而未重跑业务：旧A **67**（M1 27、HR 19、部门18、Mapper3）＋真实A/C M5 **17**＋C回归 **95**＝**179**，0失败/错误/跳过。较早M5的16项报告只核对完整性并显式排除。旧A67采用原C/D替身；17项实际走真实C M5、D仍为测试实现；95项C回归不证明A HR生产端口已完成。另读NamePinyinTest、PasswordPolicyTest源码，不将其额外计入179。

需求来源区分：[原始需求PDF](D:/Desktop/OOAD/RBAC0/统一权限管理系统需求.pdf)已提取6页并渲染核对第4页；它明确调岗先回收后新分配、合并暂保留权限及部门冻结，没有MANUAL/DEPT_POSITION等来源细化，也没有负责人五项答复。细化规则分别取当前S/API/OpenAPI与 [负责人确认记录](D:/Desktop/OOAD/负责人确认记录.md)；03/05/06/07/10/12交接中的实现选择不擅称负责人新增答复。

任务12记录的72个源码路径全部与固定SHA绑定成功：分别比较Git原始字节和CRLF检出表示，记录具体匹配表示，未将行尾差异误报为业务差异。18份冻结XML及9份日志哈希全部匹配；四份原始设计当前字节与契约来源清单一致。任务12/总控证据本身的哈希另列在静态证据中。

约束执行：无数据库读取或写入，无服务启动/事件重放，无Maven，无Git索引/提交/推送/合并/清理操作。13拥有构建锁和测试库；其在途测试与 `scripts/test`、support文件未修改。开始HEAD等于固定SHA；之后仍以对象读取为准，13新入口不在本报告验收内。

## 2. 新增确定发现

### R14-01 / P2：姓名成功写入后，可能使账号的所有设密/重置永久失败

**最小触发条件**：使用合法管理员及正常C/D/SMS依赖，向US03提交一个唯一测试手机号、范围内可分配部门、合法reason，但 `realName="··"`。该值非空白、长度2，通过DTO。创建成功后取SET_PASSWORD有效证明，提交任意满足密码规则的新密码。无须并发或数据库篡改。

**期望**：若系统采用“姓名必须能生成完整拼音”的既有实现限制，应在账号资料写入前拒绝不支持的姓名；不能先返回账号创建成功、再使其无法完成首次使用。保持既有策略约束，不建议在设密时静默省略已知姓名。

**实际控制流**：US03只strip、加密、插入并签名，没有检查拼音可生成。AU07/AU08通过证明及身份检查后，无条件调用 `NamePinyin.variants`。对 `··`，循环跳过两个字符，最后集合仅含空串；移除后为空，抛422。更换密码或重发验证码均无法改变该分支；US04只改状态，没有临时账号改名入口。该值还可经已有HR人员的HR02资料更新写入，导致该人的后续重置同样失败；HR02仅在新建人员时计算登录名，更新分支不做该检查。

**精确定位**（所有绝对路径均指固定SHA对应文件）：

- [UserRequests.java:6](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/dto/user/UserRequests.java:6)：Temporary.realName只有 `@NotBlank @Size(max=64)`。
- [UserService.java:86](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/user/UserService.java:86)：`u.realNameCiphertext=codec.encrypt(input.realName().strip())`；第90行插入账号并签名/审计，第91行返回。
- [HrService.java:84](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/hr/HrService.java:84)、[HrService.java:144](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/hr/HrService.java:144)：已有账号执行profile/update，profile直接加密新姓名。
- [AuthService.java:151](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/AuthService.java:151)：`List<String> pinyins=NamePinyin.variants(codec.decrypt(u.realNameCiphertext));`。
- [NamePinyin.java:26](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/NamePinyin.java:26)、[NamePinyin.java:34](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/NamePinyin.java:34)：`else continue;` → `values.remove("");` → 空集合抛业务错误。

**原文依据**：当前详细设计§3.2明确“临时人员通过绑定手机号……设置登录密码，设密后方可登录”；§3.5、§9.1要求根据已知姓名执行密码策略。当前API US03的姓名没有字符集限制。这不是姓名keyword搜索问题，也不要求新增汉字库或更改负责人五项规则。

**已有证据与缺口**：固定 [NamePinyinTest.java:11](D:/Desktop/OOAD/WhatNameBackend/src/test/java/com/whatname/iam/service/auth/NamePinyinTest.java:11) 已写明 `variants("··")` 和补充平面汉字抛异常；这证明是既有转换限制。A的 `createTemporaryOverHttpEncryptsIdentityRecordsTrustedCreatorAndNoRoles`（M1:272）和真实C的 `temporaryCreationWithRealM5RollsBackAllFactsOnEvidenceFailure`（M5:193）只使用可转换姓名；HR的 `profileSyncPreservesIdentityCredentialsAndUsernameAndSupportsOutsourced`（H:244）也未覆盖不支持姓名。179项无上述“写入→设密/重置”组合。

**影响/最小修复方向**：会留下接口已报告成功、又无正常首次使用路径的临时账号，或阻断已有员工找回密码。将现有NamePinyin可用性检查复用于US03和HR02资料写入，放在任何持久修改前，保证失败回滚；为可接受的姓名范围作一致说明。既有坏资料的受控纠正另由总控决定，不能靠跳过姓名弱口令限制放行。**本任务未实施。**

### R14-02 / P2：较旧密码历史损坏被归类为普通密码策略422

**最小触发条件**：一个可用且已初始化的账号，最新历史编码与当前password_hash相同且有效；最近5条内的另一条较旧历史编码损坏/不属于支持格式（例如已有数据库故障留下非空无效编码）。在身份验签正常、有效RESET_PASSWORD证明及正常姓名下，提交一个不复用、无身份片段的新密码。

**期望**：结构损坏/历史校验运行故障应安全拒绝并返回完整性/运行故障503；正常的密码过弱、命中历史仍返回422。换一个密码不能修复历史数据损坏，不应将其伪装成可由用户改输入解决的问题。

**实际**：最新历史相等检查能通过；`PasswordPolicy.validate`在较旧编码处返回 `INVALID_HISTORY`（算法校验运行失败则返回 `HISTORY_VERIFICATION_FAILED`），但AuthService把所有非VALID结果统一转为 `BUSINESS_RULE_VIOLATION`，HTTP422。密码、sv、证明均未更新，安全拒绝仍成立；缺陷是故障分类丢失和无法定位的持续找回失败，**没有密码绕过/历史复用成功证据**。

**精确定位**：

- [CredentialMapper.xml:12](D:/Desktop/OOAD/WhatNameBackend/src/main/resources/mapper/auth/CredentialMapper.xml:12)：按created_at/id倒序取最近5条，损坏的非最新历史会进入列表。
- [AuthService.java:149](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/AuthService.java:149) 只检查最新编码与当前一致；[AuthService.java:153](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/AuthService.java:153) 将所有非VALID结果转成422。
- [PasswordPolicy.java:93](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/PasswordPolicy.java:93)：INVALID_HASH返回INVALID_HISTORY；第95行default返回HISTORY_VERIFICATION_FAILED。
- [ErrorCode.java:28](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/common/api/ErrorCode.java:28)：现成INTEGRITY_FAILURE为503；该文件同时已有AUTH_STATE_UNAVAILABLE，毋须新增对外枚举。

**原文/已有证据**：详细设计§10.2将不可信/不可用事实与普通业务拒绝分开；§9.2要求完整性故障隔离，不能当普通数据使用。03组件文档刻意区分损坏编码与普通不匹配。固定 [PasswordPolicyTest.java:123](D:/Desktop/OOAD/WhatNameBackend/src/test/java/com/whatname/iam/service/auth/PasswordPolicyTest.java:123) 断言损坏/缺失编码返回INVALID_HISTORY，但不经过AuthService/HTTP。现有M1:248覆盖真实历史复用与裁剪，M1:386覆盖弱密码422及保留证明；两者均没有较旧损坏历史的HTTP分类断言。

**最小修复方向**：保留validate返回值并按枚举分类：历史缺失/损坏、上下文缺失等服务内部事实错误转完整性故障；历史校验运行失败转503；真实密码策略失败保持422。保留不核销证明、不修改账号的回滚边界。不要修复/删除历史后自动继续。**本任务未实施。**

## 3. 需求→代码/SQL→有效测试→未覆盖追踪矩阵

为避免长表重复路径，下列简称均绑定固定SHA的**绝对路径**。行号是基线行号；13当前工作区测试行号可能已不同。完整方法名、文件哈希与XML对应见静态证据。

| 简称 | 固定基线文件 |
|---|---|
| AS / US / DS / HS / ES | [AuthService](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/auth/AuthService.java) / [UserService](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/user/UserService.java) / [DepartmentService](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/department/DepartmentService.java) / [HrService](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/hr/HrService.java) / [ExternalEventService](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/event/ExternalEventService.java) |
| U / P / O / E | [UserAccountMapper.xml](D:/Desktop/OOAD/WhatNameBackend/src/main/resources/mapper/user/UserAccountMapper.xml) / [CredentialMapper.xml](D:/Desktop/OOAD/WhatNameBackend/src/main/resources/mapper/auth/CredentialMapper.xml) / [OrganizationWriteMapper](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/mapper/department/OrganizationWriteMapper.java) / [ExternalEventMapper](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/mapper/event/ExternalEventMapper.java) |
| A / H / T / M | [M1MySqlIntegrationTest](D:/Desktop/OOAD/WhatNameBackend/src/test/java/com/whatname/iam/service/auth/M1MySqlIntegrationTest.java) / [HrBusinessIntegrationTest](D:/Desktop/OOAD/WhatNameBackend/src/test/java/com/whatname/iam/service/hr/HrBusinessIntegrationTest.java) / [DepartmentBusinessIntegrationTest](D:/Desktop/OOAD/WhatNameBackend/src/test/java/com/whatname/iam/service/department/DepartmentBusinessIntegrationTest.java) / [Task12M5MySqlIntegrationTest](D:/Desktop/OOAD/WhatNameBackend/src/test/java/com/whatname/iam/service/auth/Task12M5MySqlIntegrationTest.java) |
| S / API / OpenAPI | [详细设计](D:/Desktop/OOAD/WhatNameBackend/docs/contracts/01-system-design.md) / [接口规范](D:/Desktop/OOAD/WhatNameBackend/docs/contracts/02-api-spec.md) / [OpenAPI](D:/Desktop/OOAD/WhatNameBackend/docs/contracts/03-openapi.yaml)；原始本地源字节另核对source-manifest |

Controller入口分别为 [AuthController](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/controller/auth/AuthController.java)、[UserController](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/controller/user/UserController.java)、[DepartmentController](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/controller/department/DepartmentController.java)、[HrController](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/controller/hr/HrController.java)。下表路径统一前缀 `/api/v1`；AU/US/DP/HR编号对应API同编号，18项映射不把ES另计HTTP。

| 需求/HTTP（来源） | 实际Service→Mapper/SQL及判断 | 已有有效测试原文定位（均为179内） | 未覆盖/未完成 |
|---|---|---|---|
| AU01 POST `/auth/login`；两种密码登录、5次/15分钟、统一401（S3.4/9.1） | AS61；U.lockByUsername/PhoneHash FOR UPDATE；状态/初始化/锁期检查；失败updateLoginState；Outcome提交后抛401；JWT.issue；P.insertLoginLog | A108 `usernameAndPhonePasswordLoginOverRealHttpWithSafeResponses`；A120不可用与未知同码；A128逐次计数、锁期及解锁；A138冻结仍登录 | 真D身份签名/审计和M6消费JWT未验收；失败计数故障时503回滚是既有边界，未证明万人性能 |
| AU05 POST `/auth/sms/send`；5分钟/60秒、用途隔离、未知号码不枚举（S3.4/9.4） | AS93/107；用户锁→P.lockLatestCode；invalidateCodes+insertCode；RR空行/间隙保护；悲观锁异常整单元有限重试；TestSms替换点 | A193重发旧码失效/不存明码；A205未知号码200/429且不发送；A212供应商/审计失败回滚；A221四并发仅一条证明 | 发送后数据库提交失败可能已投递无效码，为05已记录外部副作用边界；真实供应商不是当前课程必须凭据前置；账号换绑证明见第5节风险 |
| AU07 POST `/auth/password/initialize`；未设密、一次证明、身份规则/历史（S3.1/3.5/9.1） | AS130；用户和验证码锁；P.failCode持久计数，consumeCode条件核销；U.updatePassword；P.history+trim；签名/审计同事务 | A143初始化/重复401/历史1条；A156双并发同证明一赢家；A178错误/用途/过期/五次；A386策略422且证明未消耗 | **R14-01**；真实D未接；历史结构故障分类参R14-02 |
| AU08 POST `/auth/password/reset`；旧密码及旧会话失效、最近5次（S3.5/9.1） | AS130；RESET_PASSWORD证明；sv+1，清登录计数/锁；最近5编码真实scrypt逐条比较；核销/密码/历史/签名/审计同事务 | A167旧密码/旧sv被拒、可新登录；A248历史复用与裁剪；A262签名/审计失败全回滚；A386 HTTP422保留证明 | **R14-01、R14-02**；下一次真实D菜单/API旧JWT拒绝尚未完成 |
| AU04 POST `/auth/logout`；单jti、旧sv及暂停/离职可注销（S3.5） | AS179；JWT密码学验证→用户锁→P.findInvalidation/insertInvalidation；同jti幂等，不以当前sv/status限制注销 | A426 `logoutIsSingleJtiIdempotentLeavesOtherSessionAndWorksForTerminatedState`；A167旧sv可注销；A436审计故障回滚 | 过期JWT仍由verify拒绝；未假称无限期注销入口或真实D全链路 |
| US01 GET `/users`；范围内字段搜索/筛选/分页（S3.3/API US01） | US38；JWT→C完整锁准备→AS171→M5 scope/M6；U32共用filter，scope AND status/source/dept AND(keyword OR phoneHash)；count/list同RR；分页long offset；分页后验签解密/掩码 | A311列表/详情；A323 3×3筛选与百万页；A359范围外手机OR+不可解密诱饵不计数/不解密；A377非法输入400；M147真C子树items/total、M248—250等待后复核 | 姓名keyword待既有口径；未全库解密；真实M6未接；未证明高并发吞吐 |
| US02 GET `/users/{userId}`；目标直接范围重查（API US02） | US60；prepareManagement一次传actor+target；U.lockById当前锁读；不存在/范围外统一404；view核验phoneHash并掩码 | A311直接猜范围外404；M147真C详情；M208目标移动后404，M250等待目标移出后404 | 无新增确定问题；真实D完整身份字段/集合签名仍外部依赖 |
| US03 POST `/users/temporary`；可信创建人、无工号/无角色/未设密（S3.2） | US73；M5/M6+U.lockEligibleDepartment拒合并源；随机占位哈希；insertTemporary；source2、created_by取actor、无历史；唯一键→409 | A272创建/加密/属性/无角色；A285同手机并发一赢家；A299坏/合并/越范围部门；A235、M193三类故障回滚；M181合并源拒绝 | **R14-01**；179内未覆盖不支持姓名的首次使用；真实D未接 |
| US04 PATCH `/users/{userId}/status`；只临时1/2、暂停sv++、恢复不复活（S3.3） | US96；actor+target排序锁→当前会话/M5/M6；非source2/离职422；真实状态变化才更新/签名审计；不改来源 | A398保留授权及旧JWT、HR目标422、状态3输入400；M213真C/sv；M221签名/审计/约束异常回滚；M248—250资格/目标等待交错 | 真D受保护API仍待接；同状态不增sv不新增审计为已有实现选择，无新版本前置参数 |
| DP01 GET `/departments`；可见树/冻结/空源（S4.1/API DP01） | DS26；先真实C准备→O.organization→validateTree+verifyOrganization→scope；父越范围返回0，隐藏path；false只返回scope根；合并目标仅可见时返回 | T230范围根/平铺；T343祖先冻结/隐藏合并目标；M236真C部门读 | 真D完整组织验签仍待接；不会将effectivelyFrozen误当节点status |
| DP02 GET `/departments/{deptId}/users`；直属/子树、scope、分页（API DP02） | DS46；合法page/size，根必须在scope；校验path后按带尾斜线prefix与scope交集取ids；O.countUsers/users均同ids，ORDER BY id；verifyUsers只校验结果 | T230直属1/子树2、分页、缩scope、越范围403；T305合并空源0；M236真实C items/total | 179内没有DP02百万页/两页同total的独立参数化全矩阵；US01矩阵不代替DP02全部分支，但同SQL ids/long offset已读 |
| HR01 PUT `/integrations/hr/departments/{deptCode}`；负责人选填、缺省保留、版本/改挂（S4.1/4.2＋负责人C01） | DS65/72；源HR及path/body绑定；ES事务→O全组织主键锁；parent有效/无环；manager按BINARY SG+HR可用查询；省略/null保留；newer只比较本部门；repath后统一update | T166真实HTTP可省略/补经理/保留/未知/旧版本；T180整子树改挂与环拒绝；T289并发改挂防环；T298伪造服务拒绝 | C组织变更限制/重审、HR全局锁序及D仍已知未完；manager资料当前可信性/锁等由合法D实现及联调补证，不将单id查询当验签证明 |
| HR02 PUT `/integrations/hr/employees/{sgNo}`；UPSERT/REHIRE/SUSPEND/RESUME（S4.2/4.4＋负责人C04） | HS47/53；HR/path绑定→ES→组织→SG用户锁；同一人员hr_version；新建随机占位、用户名唯一；已有账号用户名/凭据保留；SUSPEND状态2且sv++，RESUME不减sv；离职仅REHIRE且资格检查；跨部门调用move | H231新建；H244更新凭据/username保留及外包；H251冲突回滚/后缀；H258暂停恢复/幂等/旧会话；H302离职只复职且不恢复来源；H324输入/来源 | **R14-01的资料更新分支**；C资格/回收为替身；换手机号/复职凭据策略属于旧D14未批范围，不在这里新定规则 |
| HR03 POST `/integrations/hr/transfers`；旧岗位精确回收→变部门、无新授予（S4.3） | HS90/148；原部门精确匹配/非离职/不同目标；C.revokePosition(oldID)→U.updateHr新dept→C.departmentChanged；最终只推进人员版本；finish重读grantRevision后签名 | H276保留MANUAL/OA/其他部门来源、相同role剩余来源有效；H284 HR02隐式复用同move；H357十一阶段失败快照不变；H375缺历史/缺C拒绝 | 真实C的HR端口、来源签名/全局锁序未完；这些不是本次新发现；原需求“交接”与细化来源语义见旧02D13，不杜撰新批准 |
| HR04 POST `/integrations/hr/terminations`；只已生效、回收/状态/sv（S4.4＋负责人C05） | HS106；effectiveAt映射事件时间，不新增occurredAt HTTP字段；锁后Instant比较未来422；C.revokeAll→status3/sv++/hrVersion→签名审计/APPLIED同事务 | H289未来1秒422/全事实不变/REJECTED、等时新event成功/旧event继续拒绝；H302 REHIRE旧授权不恢复；H364回收/管理员/证据回滚 | 真实C全部回收/任命失效与D业务拒绝未验收；没有预约调度，不把留REJECTED事件说成推进人员版本 |
| HR05 POST `/integrations/hr/merges`；留空源/去向、源版本、冻结前置（S4.5＋负责人C02/C03） | DS108/113；全组织及源人员锁；拒自/后代/已合并；frozen逐祖先；O.moveUsers只改dept；子部门改parent+repath，源清manager并标去向；目标版本不变；C重审/签名审计同事务 | T188源10/目标40/事件11，直属人/子树/子冻结/关系来源/人员版本保持及重放；T214源/祖先冻结需显式解冻；T224自/后代拒绝；T247故障全回滚；T305真HTTP空源 | 真实组织重审/D仍未完；现有verifyUsers替身不证明完整Ed25519，未将迁到冻结目标当自动解冻 |
| DP03 POST `/departments/{deptId}/freeze`；只改部门标记（S4.6） | DS133；SERIALIZABLE；M5准备与当前会话/M6→组织锁→scope→active；只O.update部门status0；签名/审计 | T230 HTTP冻结；T257审计失败部门/账号不变；M236真C范围与冻结；A138证明冻结仍可登录 | 真D菜单空/每次API拒绝未完成；不是账号暂停/不递增sv，不改授权 |
| DP04 POST `/departments/{deptId}/unfreeze`；不突破祖先/不加授权（S4.6） | 同DS133，status1；C.requireActive检查操作者所在链及管理根；上级正常且具权限方可操作；scope内合并源仍拒绝 | T214子解冻不能绕源祖先冻结；T343 effectivelyFrozen仍true；M174被冻结祖先操作者拒绝；M236审计失败回滚解冻 | 真实超级恢复通道归C/D，未把普通登录当恢复授权；M6真实链路仍待接 |
| 公共ES（非HTTP第19项）；幂等/摘要/失败/恢复（S4.2/9.5/10.1及API末尾实现补充） | ES70/111/155；validate+服务校验；独立RC claim；唯一(source,event)；canonical含类型/目标/版本/内容，排序对象/保序数组；业务SERIALIZABLE先锁事件核owner；结果校验+E.applied同事务；回滚后独立failed条件更新；recover授权/密文对比/换token | T262同事件并发一次+异内容409；T272 OA空版本/排传输字段/原结果/分类/恢复授权；T330模拟崩溃状态+换token旧更新0；T362不安全结果回滚callback写；T378 ambient事务/篡改恢复拒绝；H335/344人员幂等/乱序/并发 | 不是自动恢复/真正kill进程或未知commit结果测试；恢复必须服务端登记handler，当前无生产自动消费器；同瞬间不同offset及身份等待见第5节 |

## 4. 关键控制流与SQL复核结果

### 4.1 认证、证明和隐私

错误登录与错误短信证明通过事务内Outcome返回，事务成功提交后在外部unwrap抛401，避免预期失败把计数回滚。P10的SQL为 `attempts=attempts+1`，第五次同时写consumed_at；P11仅在未用、次数<5、未过期时核销。正确证明但策略422不核销，代码和A386一致。发生真实审计/数据库故障时整个单元失败503，此时不承诺失败计数仍独立于故障提交。

密码重置用用户行锁串行密码/状态/注销；updatePassword同时写哈希、初始化及sv、清计数；history按created_at DESC,id DESC选5，裁剪发生在同事务。占位随机秘密不返回、不进历史；离职复职沿用凭据是07已有实现选择。JWT为RS256，固定本地公钥、算法/kid/iss/aud/token_use/sub/sv/iat/exp/jti检查，1小时，不固化角色菜单；verify只是令牌验证，AS171再查当前状态/初始化/sv/jti。logout只注销自身jti，允许可验证的旧sv。

姓名/手机通过AES-GCM保存，手机号单独HMAC索引；UserView在范围内分页后验签、解密及phoneHash一致性检查，仅输出掩码，不输出密码/码哈希/phone_hash。异常处理只记异常类名，未输出SQL参数。事件输入加密存储；结果字段名过滤只是保底，仍依赖各handler选取脱敏返回，不将它称为任意敏感内容识别器。未读取个人密钥/真实员工内容。

### 4.2 范围、版本和不变量

U32—46的filter先约束dept scope；keyword OR位于括号，phoneHash OR不会绕scope/status/source；count和list复用同一片段。空scope拒绝，显式越范围dept403，详情及状态目标不存在/范围外404，非法参数400；不把这些差异混成越权。真实C中暂停/离职操作者先被M5拒403（M168已测），旧sv但账号可用则AS171返回401（M213已测）。这是当前先后校验结果，未新造401优先于所有403的要求。

管理首锁实际链：UserService/DepartmentService → prepareManagement → [AManagementPolicy.prepare](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/admin/AManagementPolicy.java:19) → [AdminPolicyService.requireManagementContext](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/admin/AdminPolicyService.java:78) → [AdminStateLoader.load](D:/Desktop/OOAD/WhatNameBackend/src/main/java/com/whatname/iam/service/admin/AdminStateLoader.java:15) → [AdminMapper.xml](D:/Desktop/OOAD/WhatNameBackend/src/main/resources/mapper/admin/AdminMapper.xml:5)。顺序为任命、部门、角色、清单、完整actor/target用户集合去重排序。随后AS171读取当前会话，目标当前锁读；M248—250包含实际等待与撤销/重任命/目标fixture移动提交后复核。目标移动是fixture SQL，不是已接入的HR调岗。

HR组织锁SQL为 `SELECT * FROM sys_dept FORCE INDEX(PRIMARY) ORDER BY id FOR UPDATE`，配合SERIALIZABLE全组织/插入间隙保护；树路径按 `/id/.../id/`递归重算并检查缺父/环/长度。HR人员mapper.updateHr不写username/password_hash/password_initialized/grant_revision；finish重读用户后签实际C返回的grant_revision。HR01/05与人员各按相应对象版本推进，没有拿目标hr_version覆盖源。新员工/临时账号/新父节点均拒已合并源；真实C任命也采用已合并链拒绝。冻结status与合并生命周期独立。

### 4.3 外部事件与事务

短认领RC REQUIRES_NEW与业务SERIALIZABLE REQUIRES_NEW明确分开，事务模板规避自调用代理失效。ES拒绝调用者已有事务。业务先锁事件并核对token/status，callback/C/D/结果/APPLIED一次提交；RuntimeException退出业务回滚后，短事务才以id+token+PROCESSING记REJECTED或PENDING_RECOVERY。即使提交结果未知但实际APPLIED，failed条件也不能覆盖APPLIED；这是SQL保证，尚无真实断网未知commit结果试验。

恢复锁会等待活跃业务事件行；如果业务已完成，读到APPLIED重放；如果旧owner只认领未进入业务，recover换token后其回调前检查失败；旧owner的failed更新同样匹配旧token，不能覆盖新owner。已有T330只模拟PROCESSING状态和旧token条件更新，未证明真实进程kill、长期锁/超时或所有交错。无自动过时抢占、无把测试PENDING_RECOVERY批量当生产待办重放。

## 5. 推测/未覆盖风险（不计新增确定缺陷）

1. **手机号换绑与短信证明的账号绑定**：P按phone_hash+purpose检索，表无user_id/绑定代次；HS.profile改变手机不失效旧号码证明。可静态构造“U1收到码→HR换绑U1→该号绑定U2→旧码仍匹配该号当前U2”的流程，但未动态验证；是否要求旧号码证明随账号换绑失效、与既有D14凭据策略如何衔接尚未批准，不能将其写成已确认接管漏洞。补测前先明确证明所绑定的账号/号码时点；不在本审查擅改契约。
2. **服务身份的事务内复核覆盖**：HS.lock明确在组织锁后verifyTrustedEvent；DS的HR01/05主要依赖ES在认领前的校验，DS.locked只验组织。等待中服务资格变化时合法D应如何锁定/再验，当前没有真实D契约/证据；不能假设前置方法已经持跨事务锁，也不能断言服务请求在验证后必须按最新撤销失效。列入真实联调的最小交错检查，不把真实D缺失重复计新bug。
3. **相同时刻不同offset重试**：ES顶层occurredAt转Instant，但DTO仍被整体放入businessPayload，内层时间保留序列化表示，语义相同时间的不同offset可能产生不同摘要。当前契约承诺同内容，是否要求跨offset语义等价未明，不以“应自动等价”擅定新规范。
4. **恢复/提交故障的实测强度**：现有T330是模拟崩溃状态，T378验证密文篡改；未真的kill工作者，未测试有效业务正在持锁时recover等待后重放、也未注入提交成功但响应/连接丢失。这是补证范围，不反向否定已读的token条件和APPLIED原子性。

## 6. 已知依赖保持原状态

- 真实D的服务验签/nonce、可信请求上下文、当前M6、完整Ed25519字段/集合验签与重签、同事务审计和真实菜单/API使用A会话尚未验收。测试指纹不是Ed25519；默认装配拒绝保持。
- C真实M5已局部接入，HR专用的旧岗位来源精确回收、离职全部来源/关系/任命失效、复职残余资格核对、组织重审与全局HR锁序尚待提供/对齐。人工GrantOperations.revoke有管理员范围/角色前置，不可冒充HR入口。
- 姓名keyword口径待答、真实10000员工可信入库、完整性能和部署验收仍未完成。未复查远端refs，不替代08，不对成员本机进度作推断；原需求中的用户名消歧/缓存与当前设计差异仍依原台账，不当本次新发现。
- 已确认五条负责人规则全部保留，不重问、不新增预约离职/HTTP必填occurredAt/目标合并版本/自动恢复授权。

## 7. 最小高价值补测建议与教学定位

仅建议与发现/证据空白有关的少量用例；由总控决定修复及安排，本聊天不运行、不新建测试或后续任务。

| 建议 | 可观察断言 | 与已有证据的区别 |
|---|---|---|
| R14-01写入→设密组合 | US03不支持姓名应写入前拒绝且账号/签名/审计无残留；HR02已有资料更新同检查；正常中文/Latin名称仍可设密 | NamePinyinTest只证明转换器拒绝，A272/M193未覆盖拒绝前资料成功写入 |
| R14-02较旧历史损坏 | 最新历史保持与当前相同；只损坏较旧历史；有效证明+新密码应503且hash/sv/history/证明/证据均不变；复用有效历史仍422 | PasswordPolicyTest只证明枚举；A248/A386未证明AuthService错误分类 |
| 活跃事件恢复与响应丢失 | 持事件业务锁时recover不得另执行；释放后重放相同结果；若第一次实际已APPLIED但丢响应，新nonce同eventId只重放一次 | T330覆盖模拟遗留状态/token，T262覆盖普通同事件并发，不是这两个交错 |

教师可追问的实际位置（待A全完成后集中讲解，不记用户已掌握）：

- **为何错误次数提交后才抛401？** AS61/130的Outcome与P10，区别预期业务错误和审计/数据库故障回滚。
- **为什么同密码不能比较哈希字符串？** PasswordPolicy逐条scrypt匹配；AS149的当前编码一致性只是历史首条绑定，不能替代复用检查，也不能吞掉INVALID_HISTORY。
- **为什么要一次传actor和target并排序？** AdminStateLoader15、UserService60/96；结合M248—250等待后当前读及死锁/旧RR快照的边界。
- **SQL的OR与分页在哪里保证范围？** U32共用filter与US53，DP02的O.countUsers/users共用ids；total泄露也是范围泄露。
- **为什么合并不推进目标或人员版本？** DS113、O28的moveUsers和U17的updateHr；版本属于相应来源对象，去向不等于新人员版本。
- **为什么完成记录必须和业务同事务？** ES70/111、E15—20的token/status条件；独立认领与同事务APPLIED分别解决什么失败窗口。
- **为什么“组件拒绝”还会形成流程bug？** R14-01与R14-02，联系输入验证、需求追踪、枚举契约和故障分类；Java类/三层是实现组织，不能据此把当前结构化设计说成OO分析。

## 8. 可复核证据与输出

- [task14-static-evidence.py](D:/Desktop/OOAD/tmp/task14-static-evidence.py)：仅本地Git/文件读取、哈希与XML解析；无构建/网络/数据库调用。
- [task14-static-evidence.json](D:/Desktop/OOAD/tmp/task14-static-evidence.json)：79固定对象Git ID/SHA、72路径与12证据绑定、18份XML方法/计数及哈希、9日志哈希、旧16项排除、原设计来源、精确方法行号与当时Git状态（含13在途文件）。
- [task14-code-excerpts.md](D:/Desktop/OOAD/tmp/task14-code-excerpts.md)：从固定对象提取发现相关生产/组件测试原文，以及合并版本、未来离职、等待锁复核和恢复owner等关键断言；行号不取当前工作区。
- [task14-original-requirement-text.txt](D:/Desktop/OOAD/tmp/task14-original-requirement-text.txt)、[第4页渲染](D:/Desktop/OOAD/tmp/task14-original-page4.png)：原始需求只读提取/查看记录，未编辑源PDF。
- [task12-verification-evidence.json](D:/Desktop/OOAD/tmp/task12-verification-evidence.json)、[coordinator-task12-review.json](D:/Desktop/OOAD/tmp/coordinator-task12-review.json)：原冻结运行/总控核查材料；本任务没有重跑其中脚本。
- [task14-output-manifest.json](D:/Desktop/OOAD/tmp/task14-output-manifest.json)：本文、交接、静态脚本/证据的最终SHA-256；排除自身避免递归。精确输出仅本报告、根目录交接及tmp/task14-*。

到此完成本任务只读审查和交接；未替总控制定修复任务或发送任何跨聊天/外部消息。
