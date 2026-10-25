"""Generate the A-G API handbook from reviewed metadata; verify it against controller routes.

Only Markdown, JSON and HTTP examples are written. No database or network operations.
"""
from pathlib import Path
import json
import re

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'docs/learning'
API_DIR = OUT / 'api'
CARDS = []
GROUPS = {
    'A': ('账号与登录', 'AccountService、JwtTokenService、PasswordHasher', '账号与临时人员；另负责两个运行检查接口'),
    'B': ('在线鉴权与平台自我管理', 'AccessService、ManagementInterceptor、QueryService.menus/check', 'RBAC0 计算、菜单输出、IAM 自我鉴权和业务演示'),
    'C': ('应用菜单与API资源', 'ResourceService 的资源方法、RouteTemplateUtils', '三层资源及菜单/API 映射'),
    'D': ('角色定义与用户授权', 'ResourceService 的角色方法、GrantService', '区分角色定义、角色菜单和用户角色三种操作'),
    'E': ('部门范围与组织冻结', 'AccessService.within/scope、AccountService.scope、OrganizationService', '替代旧管理员等级与任命；数据范围是跨接口公共规则'),
    'F': ('HR与人员组织变动', 'IntegrationController 的 HR 方法、OrganizationService', '同步、调岗、离职、部门合并'),
    'G': ('批量OA审计与公共安全', 'BatchService、SignatureService、AuditService、InitializationService', '批量、OA、审计；另主讲签名、事务、初始化和恢复'),
}


def field(name, kind, required, rule):
    return [name, kind, required, rule]


def card(group, method, route, title, service, permission, scope, fields, body,
         result, tables, repo, flow, transaction, audit, errors, success, failure,
         question, answer, coverage='无该接口的专用自动用例；按下列人工步骤验收。',
         tests=(), frontend='', query='', support=False):
    number = sum(c['owner'] == group for c in CARDS) + 1
    CARDS.append(dict(id=f'{group}{number:02d}', owner=group, method=method,
                      path='/api/v1' + route, title=title, service=service,
                      permission=permission, scope=scope, fields=fields, body=body,
                      result=result, tables=tables, repository=repo, flow=flow,
                      transaction=transaction, audit=audit, errors=errors,
                      success=success, failure=failure, question=question, answer=answer,
                      coverage=coverage, tests=list(tests), frontend=frontend,
                      query=query, support=support))


USER = dict(id='101', username='demo_user', realName='演示用户', phone='13800000002',
            employeeNo='DEMO001', deptId='2', status=1, source=1, employmentType=1,
            managementScope='DEPT_TREE', createdAt='2026-10-25T10:00:00')
TEMP = {**USER, 'username': 'demo_temp', 'source': 2, 'employeeNo': None, 'employmentType': None}
DEPT = dict(id='2', parentId='1', deptCode='DEMO_DEPT', deptName='演示部门', status=1,
            mergedIntoDeptId=None)
APP = dict(id='3', appCode='DEMO_APP', appName='演示应用', appDesc=None, status=1)
MENU = dict(id='4', appId='3', parentId='0', menuCode='DEMO_MENU', menuName='演示菜单',
            menuType=2, path='/demo', sortOrder=0, status=1)
API = dict(id='5', appId='3', apiCode='DEMO_API', apiName='查看演示',
           apiPath='/demo/items/{id}', canonicalPath='/demo/items/{}', httpMethod='GET', status=1)
ROLE = dict(id='6', roleCode='DEMO_ROLE', roleName='演示角色', roleDesc=None, status=1)
AUTH_TABLES = '共同读取：sys_user、sys_dept、sys_application、sys_user_role、sys_role、sys_role_permission、sys_menu、sys_menu_api、sys_api、sys_data_signature。'
READ_TX = 'Service 的普通事务；不申请管理写锁，不改变业务数据。校验读取的是数据库当前授权，不从 JWT 读取角色快照。'
USER_TX = '同一事务：锁目标 sys_user 一行 → 验签/范围检查 → 修改 → 更新用户签名 → 记录日志；任一步异常整体回滚。'
RESOURCE_TX = '同一事务：新建对象或锁目标对象一行 → 校验 → 保存 → 更新对象签名 → 写日志。没有 expectedVersion。'
MANAGED = 'Bearer JWT；同时经过 ManagementInterceptor 的 IAM 菜单/API 映射检查。Service 还检查：'
SELF = '本人 Bearer JWT，不要求另有 IAM 管理菜单权限。账号状态和会话版本仍必须有效。'

# A: ten business APIs and two system checks.
card('A', 'POST', '/auth/login', '账号或手机号密码登录', 'AccountService.login',
     '公开登录入口；不需要旧 JWT。', '仅验证输入凭据对应的账号，不使用管理数据范围。',
     [field('loginType','string','必填','USERNAME 或 PHONE'), field('identifier','string','必填','最长100；PHONE 会规范化并检查手机号'), field('password','string','必填','登录只验证已有哈希；新密码复杂度由设密接口检查')],
     {'loginType':'USERNAME','identifier':'demo_temp','password':'ExampleOnly123!'},
     {'authenticated':True,'token':'<JWT>','user':TEMP}, '读 sys_user、sys_data_signature；写 sys_operation_log。',
     'AccountRepository.findByUsername/findByPhone；DomainRepository.query/save。',
     ['按登录类型找到账号并验签。','检查 status=1 和 scrypt 密码哈希。','写成功/失败 LOGIN 日志；成功签发含用户ID和会话版本的 JWT。','失败先返回内部结果提交日志，再由 Controller 返回401。'],
     '登录失败日志通过正常返回先提交；Controller 在事务外报告凭据错误，避免把失败日志回滚。',
     'LOGIN：成功与普通凭据失败均记录；完整性故障抛异常，不保证该路径有失败日志。',
     ['400 INVALID_ARGUMENT：登录类型/字段不合法','401 INVALID_CREDENTIALS：密码错误或账号不可用','503 INTEGRITY_FAILURE：用户签名异常'],
     '创建临时测试账号后，用 USERNAME 和 PHONE 分别登录，均获得 token。',
     '同一账号输入错误密码，应401；随后由G查询 LOGIN 失败记录。',
     '为什么登录不能只看密码正确？','还要验证账号签名和可用状态，暂停/离职账号即使密码正确也不能登录。',
     'HTTP 自动用例覆盖用户名成功和手机号错误密码；手机号成功需人工补验。',
     ['loginAndScopedTemporaryAccount'], '登录页 App.vue → api.js.login')
card('A','GET','/auth/me','读取本人身份与IAM权限','AccountService.me',SELF,
     '只返回本人，不接受目标 userId。',[],None,{'user':USER,'menus':[MENU]},AUTH_TABLES,
     'DomainRepository.get/roles/menus/apis/query。',
     ['校验JWT、用户签名、状态和会话版本。','读取 IAM 应用，计算本人的有效菜单并集。','返回 user 和 menus，供前端导航与按钮使用。'],READ_TX,'不写操作日志。',
     ['401 INVALID_TOKEN：令牌失效','503 INTEGRITY_FAILURE：身份或权限数据异常'],
     '登录后携带 Bearer 调用，返回本人及实际拥有的 IAM 菜单。','退出登录后复用旧JWT，应401。',
     '这里返回的是不是管理员任命？','不是；返回普通用户及其 IAM 菜单权限，没有 sys_admin 身份。',
     'HTTP 自动覆盖成功及不返回 passwordHash；令牌失效由其他用例覆盖。',['protectedEndpointsAndSelfManagedRbac'], 'api.js.refreshSession → App.vue 导航')
card('A','POST','/auth/logout','退出该账号全部会话','AccountService.logout',SELF,'仅本人。',[],None,{},
     '读写 sys_user；写 sys_data_signature、sys_operation_log。','DomainRepository.lock/get/save。',
     ['取得本人并锁用户行。','递增 sessionVersion，重新签名。','保存 LOGOUT，随后旧JWT均因版本不符失效。'],USER_TX,'LOGOUT。',
     ['401 INVALID_TOKEN：旧或无效JWT','503 INTEGRITY_FAILURE：用户签名异常'],
     '准备同账号两枚JWT，退出其中一个会话。','两枚旧JWT随后访问 /auth/me 都应401。',
     '为什么另一个浏览器也退出了？','设计采用账号会话版本统一失效，未保留单令牌黑名单。',
     frontend='App.vue 退出 → api.js.logout')
card('A','PUT','/auth/password','修改本人密码','AccountService.ownPassword',SELF,'仅本人。',
     [field('oldPassword','string','必填','必须匹配当前哈希'),field('password','string','必填','8–64位，含字母和数字')],
     {'oldPassword':'OldExample123!','password':'NewExample456!'}, {},
     '读写 sys_user；写签名、日志。','DomainRepository.lock；实体脏检查。',
     ['锁本人并验签。','校验原密码。','生成新scrypt哈希，递增会话版本，签名并记日志。'],USER_TX,'PASSWORD_CHANGE。',
     ['401 INVALID_CREDENTIALS：原密码不符','400 INVALID_ARGUMENT：新密码不合规则'],
     '用正确原密码改密，再用新密码登录。','原密码错误应401，旧密码仍可登录；成功改密后原JWT应401。',
     '为什么改完密码需要重新登录？','会话版本递增，旧JWT不能继续使用。',frontend='App.vue 修改密码对话框')
card('A','GET','/users','分页查询范围内用户','AccountService.users',MANAGED+'IAM_USERS_VIEW 或 IAM_GRANTS_VIEW。',
     '先计算本人可见部门；指定 deptId 必须也在范围内，且仅筛该精确部门，不自动包含其子部门。',
     [field('keyword','query string','可选','匹配账号、姓名、工号；不是手机号搜索'),field('deptId','query ID','可选','指定部门精确筛选'),field('status/source','query integer','可选','1/2/3 含义见公共说明；当前查询未单独验证枚举'),field('page','query integer','默认1','>=1'),field('pageSize','query integer','默认20','1–100')],None,
     {'items':[USER],'total':'1','page':1,'pageSize':20},'授权公共读取＋sys_user 范围分页；无写表。',
     'AccountService.visibleDepartments → DomainRepository.all(Department)、page(Account)、query(count)。',
     ['验证菜单权限。','计算可见部门并检查 deptId。','按关键字/状态/来源分页；逐条验证返回用户签名。','返回 items/total/page/pageSize。'],READ_TX,'不写日志。',
     ['403 OUT_OF_SCOPE：指定范围外部门','400 INVALID_ARGUMENT：分页参数不合法','503 INTEGRITY_FAILURE：返回用户或组织签名异常'],
     '部门管理员查询只看到本部门及后代用户；ALL用户可看到全公司。','指定兄弟部门的deptId，应403。',
     '范围过滤应该由前端还是后端完成？','后端查询就限制部门集合，前端筛选不能作为权限防线。',
     'HTTP 自动覆盖范围内总数，服务级用例覆盖其他范围规则。',['protectedEndpointsAndSelfManagedRbac'], 'UsersView.vue 用户账号/权限分配', '?page=1&pageSize=20')
card('A','GET','/users/{id}','查询一个用户','AccountService.detail',MANAGED+'IAM_USERS_VIEW。',
     '目标用户必须在本人部门范围内。',[field('id','path ID','必填','目标用户ID')],None,USER,
     '授权公共读取＋sys_user；无写表。','DomainRepository.get(Account)；AccessService.scope。',
     ['校验功能权限。','读取目标用户。','验证目标签名和目标部门范围后返回实体。'],READ_TX,'不写日志。',
     ['404 RESOURCE_NOT_FOUND：用户不存在','403 OUT_OF_SCOPE：目标部门越界'],
     '查询已知范围内员工，返回用户对象。','查询兄弟部门员工，应403。',
     '知道别人的ID能不能直接查看？','不能，服务端仍按目标所属部门检查范围。',frontend='当前列表直接使用分页数据；详情接口可用 IDEA HTTP Client 演示')
card('A','POST','/users/temporary','创建临时账号','AccountService.temporary',MANAGED+'IAM_USERS_WRITE。',
     '新账号部门必须在操作者范围内，且祖先链不冻结、不合并。',
     [field('username','string','必填','最长64且唯一'),field('realName','string','必填','最长64'),field('phone','string','必填','规范化后1[3-9]开头的11位手机号且唯一'),field('deptId','ID','必填','目标部门'),field('password','string','必填','8–64位，含字母和数字')],
     {'username':'demo_temp','realName':'演示外部人员','phone':'13800000002','deptId':'2','password':'ExampleOnly123!'},TEMP,
     '新增 sys_user；写 sys_data_signature、sys_operation_log；查询 sys_dept。',
     'DomainRepository.get(Department)/save(Account)；JPA自动生成ID。',
     ['检查权限、部门范围和部门有效性。','设置 source=2，无工号、无人员类型，默认DEPT_TREE。','密码哈希后保存账号、签名和日志；不自动分配角色。'],
     '一次普通写事务；新增用户无旧行锁，唯一约束处理重复账号和手机号。','USER_CREATE。',
     ['403 OUT_OF_SCOPE：创建到范围外','422 BUSINESS_RULE_VIOLATION：部门链冻结/合并','409 DUPLICATE_RESOURCE：账号或手机号重复'],
     '创建账号后由D分配角色，再登录演示。','不分配角色就登录，只能证明身份，不应获得业务菜单。',
     '临时账号需要另一张表吗？','不需要，source=2 区分来源，使用同一套角色与权限关系。',
     '服务级自动覆盖创建、越界；前端创建交互为模拟接口契约测试。',['loginAndScopedTemporaryAccount'], 'UsersView.vue 创建临时账号')
card('A','PATCH','/users/{id}','修改临时账号信息','AccountService.updateTemporary',MANAGED+'IAM_USERS_WRITE。',
     '目标在范围内且 source=2。',
     [field('id','path ID','必填','目标临时账号'),field('realName','string','可选','给出时非空、最长64'),field('phone','string','可选','给出时规范化并验证唯一')],
     {'realName':'更新后的演示人员'},TEMP,'修改 sys_user；更新签名并记日志。',
     'DomainRepository.lock(Account)；实体脏检查保存修改。',
     ['锁目标并检查范围。','拒绝非临时账号。','只修改给出的姓名/手机号；不改工号、来源、部门或角色。'],USER_TX,'USER_EDIT。',
     ['422 BUSINESS_RULE_VIOLATION：正式员工不能走此接口','409 DUPLICATE_RESOURCE：手机号重复','403 OUT_OF_SCOPE：目标越界'],
     '修改临时账号姓名，再查列表验证。','对HR员工调用，应422。',
     '为什么正式员工不能在这里改？','正式员工由HR维护，避免本地手工数据与HR来源冲突。',frontend='UsersView.vue 临时账号“编辑”')
card('A','PATCH','/users/{id}/status','暂停或恢复临时账号','AccountService.status',MANAGED+'IAM_USERS_WRITE。',
     '目标在范围内且 source=2。',[field('id','path ID','必填','临时账号ID'),field('status','integer','建议必填','1恢复、2暂停；代码省略时保留当前状态')],
     {'status':2},{},'修改 sys_user；暂停删除 sys_user_role；更新签名和日志。','DomainRepository.lock/clearRoles。',
     ['锁用户并验签、检查范围及来源。','status=2时清空角色并增加会话版本。','status=1只恢复账号，不自动返还角色。'],USER_TX,'USER_STATUS。',
     ['400 INVALID_ARGUMENT：不接受status=3','422 BUSINESS_RULE_VIOLATION：非临时账号'],
     '暂停有角色的临时账号，核对角色清空且旧JWT失效。','恢复后未重新分配角色，业务请求仍应拒绝。',
     '恢复账号为什么没有恢复权限？','角色暂停时已删除，恢复只改变账号状态。',
     '服务级自动覆盖暂停、清空角色与JWT失效；恢复需人工补验。',['loginAndScopedTemporaryAccount'], 'UsersView.vue 暂停/恢复')
card('A','PUT','/users/{id}/password','管理者重置目标密码','AccountService.resetPassword',MANAGED+'IAM_PASSWORD_RESET。',
     '目标必须在本人范围内；离职账号拒绝重置。',[field('id','path ID','必填','目标用户ID'),field('password','string','必填','8–64位，含字母和数字')],
     {'password':'ResetExample456!'}, {},'修改 sys_user.password_hash/session_version；写签名和日志。','DomainRepository.lock(Account)；setPassword。',
     ['校验重置权限与目标范围。','拒绝status=3。','更新哈希和会话版本，重新签名并写日志。'],USER_TX,'PASSWORD_RESET。',
     ['403 OUT_OF_SCOPE：越界','422 BUSINESS_RULE_VIOLATION：目标已离职','400 INVALID_ARGUMENT：弱密码'],
     '重置选定测试员工密码，用新密码登录。','目标旧JWT应失效；离职账号重置应422。',
     '重置需要知道目标原密码吗？','管理者不需要原密码，但必须具有专门权限及目标数据范围。',frontend='UsersView.vue 重置密码')
card('A','GET','/system/ping','检查后端进程','AuthController.ping','公开运行检查。','不返回用户或业务数据。',[],None,
     {'status':'UP'},'不读写数据库。','无Repository调用。',['直接构造UP响应。'],'无业务事务。','不写日志。',[],
     '后端启动后请求返回UP。','数据库关闭时，此接口本身仍不能作为数据库就绪证明。',
     'ping成功能证明数据库能用吗？','不能，数据库连接由另一个检查接口验证。',support=True)
card('A','GET','/system/database','检查数据库连接','QueryService.database','公开运行检查。','仅返回连接结果，不返回员工数量。',[],None,
     {'connected':True},'只读 sys_user 计数。','DomainRepository.query(count Account)。',
     ['执行一个用户计数查询。','查询成功返回connected=true；数据库异常交给统一异常处理。'],READ_TX,'不写日志。',
     ['503 AUTH_STATE_UNAVAILABLE：数据库不可访问'],
     'MySQL正常时返回connected=true。','在独立环境停止数据库后请求，应该503。',
     '为什么内部查数量却不返回数量？','这里只用查询确认数据库可用，对外只给运行状态。',support=True)

# B: authorization, the same evaluator protects IAM itself.
card('B','GET','/auth/menus','查询本人某应用的菜单','QueryService.menus',SELF,
     '仅本人；按指定应用计算业务权限，不使用“管理其他人”的部门范围。',
     [field('appCode','query string','默认IAM','已登记且启用的应用编码')],None,
     {'appCode':'OA','user':USER,'menus':[MENU]},AUTH_TABLES,
     'DomainRepository.roles/menus/get；补祖先目录。',
     ['根据JWT取得本人和目标应用。','AccessService.menus 取有效角色菜单并集，检查组织和资源状态。','补齐导航祖先，返回菜单及本人信息。'],READ_TX,'不写日志。',
     ['404 RESOURCE_NOT_FOUND：应用不存在','403 PERMISSION_DENIED：应用停用','401 INVALID_TOKEN：身份失效'],
     '给测试账号分配演示角色后，OA返回4个菜单。','回收角色后重查，菜单为空；目录本身不能产生API权限。',
     '菜单为空会不会自动退出登录？','不会；身份和权限分开，账号有效仍可登录。',
     frontend='DemoView.vue 选择OA/HR；api.js用于身份刷新时另调用/auth/me',query='?appCode=OA')
card('B','POST','/auth/check','外部业务后端在线鉴权','QueryService.check',
     '用户Bearer JWT ＋ X-App-Code ＋ 对应 iam.integration.apps.<code>.key 的 X-Service-Key。',
     '应用由对应服务凭据绑定；不接受用HR/OA集成密钥代替应用鉴权密钥。',
     [field('method','string','必填','请求方法，应与登记的大写方法一致'),field('route','string','必填','最长256；传后端匹配后的路由模板，如/orders/{id}'),field('X-App-Code','header','必填','1–64位字母/数字/下划线/连字符'),field('X-Service-Key','header','必填','对应应用专用密钥')],
     {'method':'GET','route':'/api/v1/demo/OA/feature1'}, {'allowed':True,'reasonCode':'ALLOW'},
     AUTH_TABLES+' 成功执行判断时写 sys_operation_log。','DomainRepository.query(Api)、roles/menus/apis。',
     ['Controller先校验应用凭据。','验证用户身份、应用及目标API签名。','按规范化路径+方法找到唯一API，再检查有效菜单是否映射它。','返回allowed/reasonCode并记AUTH_CHECK。'],
     'QueryService普通事务，判断结果与日志同事务；日志失败不返回允许。异常身份/验签路径不保证有AUTH_CHECK日志。','AUTH_CHECK，含方法、模板、ALLOW/DENY。',
     ['401 INVALID_SERVICE：密钥不匹配','401 INVALID_TOKEN：JWT失效','400 INVALID_ROUTE_TEMPLATE：模板非法','503 INTEGRITY_FAILURE：签名异常'],
     '正确应用凭据和角色应返回200且allowed=true。','回收角色后同JWT返回200且allowed=false；错误服务密钥返回401。',
     'HTTP200是否就可以放行业务？','不能，业务系统必须继续检查data.allowed；只有true才放行。',
     'HTTP自动覆盖允许、应用密钥不能替换为OA/HR集成密钥。',['externalCheckBindsApplicationToItsOwnCredential'], '外部后端调用；管理前端不保存应用密钥')
card('B','GET','/demo/{appCode}/{feature}','演示业务接口逐次鉴权','QueryService.check',SELF,
     '对当前用户在路径指定应用中的菜单/API权限重新计算。',
     [field('appCode','path string','必填','演示使用OA或HR'),field('feature','path string','必填','feature1到feature4；以已登记路由为准')],None,
     {'appCode':'OA','feature':'feature1','message':'业务接口鉴权通过'},AUTH_TABLES+' 写AUTH_CHECK。',
     '复用AccessService.allowed与QueryService.check。',
     ['组合本次实际演示路径。','复用同一RBAC0计算器判断。','允许返回业务演示结果，拒绝由Controller返回403。'],
     '鉴权和日志在QueryService事务内完成；拒绝判断正常返回后由Controller抛403，因此该拒绝日志保留。','AUTH_CHECK。',
     ['403 PERMISSION_DENIED：业务权限不足','401 INVALID_TOKEN：账号失效'],
     '业务演示点一个已授权OA功能，显示通过。','保留旧页面，另一会话撤权后点击原功能，应403。',
     '前端菜单还没刷新，为什么也能阻止操作？','每次业务请求都重新校验，旧页面不能绕过后端。',
     'HTTP自动覆盖多角色并集、撤权、冻结与解冻。',['multiRoleUnionImmediateRevokeAndOaRepeat','freezeTransferAndTermination'], 'DemoView.vue')

# C: all eleven resource endpoints.
for method,route,title,service,result in [
    ('GET','/applications','查询应用列表','applications',[APP]),
    ('GET','/applications/{app}/menus','查询应用菜单','menus',[MENU]),
    ('GET','/applications/{app}/apis','查询应用API','apis',[API]),
    ('GET','/menus/{id}/apis','查询菜单关联API ID','menuApis',['5']),
]:
    fs=[] if route=='/applications' else [field('app' if '{app}' in route else 'id','path ID','必填','应用ID或菜单ID，以路由为准')]
    card('C',method,route,title,'ResourceService.'+service,MANAGED+'IAM_RESOURCES_VIEW。','资源为全局定义，不按操作者部门裁剪。',fs,None,result,
         '授权公共读取＋对应 sys_application/sys_menu/sys_api/sys_menu_api；无写表。',
         'DomainRepository.all/query/get/apis；逐个返回对象验签。',
         ['验证资源查看权限。','按路由参数读取资源或关联ID。','验证相应资源签名后返回；列表不是分页接口。'],READ_TX,'不写日志。',
         ['404 RESOURCE_NOT_FOUND：路径对象不存在','503 INTEGRITY_FAILURE：资源或关联签名异常'],
         '打开应用资源页并选择应用，与接口返回对象对应。','无资源查看权限的账号应403。',
         '这里为什么没有部门范围过滤？','应用资源是全局定义，管理资格由对应IAM功能权限控制。',frontend='ResourcesView.vue；角色选菜单也使用应用和菜单目录')
for create in [True,False]:
    card('C','POST' if create else 'PATCH','/applications' if create else '/applications/{id}',
         '新建应用' if create else '修改应用','ResourceService.application',MANAGED+'IAM_RESOURCES_WRITE。','全局资源操作。',
         ([field('appCode','string','必填','最长64；只在创建时读取，更新不改编码')] if create else [field('id','path ID','必填','应用ID')])+
         [field('appName','string','必填','最长128；PATCH也必须提供'),field('appDesc','string','可选','最长512；省略或null清空'),field('status','integer','可选','0/1；省略保持原值，新建默认1')],
         {'appCode':'DEMO_APP','appName':'演示应用','status':1} if create else {'appName':'演示应用新名称','status':1},APP,
         '写 sys_application、sys_data_signature、sys_operation_log。','DomainRepository.save 或 lock(Application)；实体脏检查。',
         ['检查写权限。','创建或锁定并验签原对象。','保存名称/描述/状态；IAM应用不能停用。','签名并记录APPLICATION_SAVE。'],RESOURCE_TX,'APPLICATION_SAVE。',
         ['400 INVALID_ARGUMENT：缺少appName等必填字段','409 DUPLICATE_RESOURCE：应用编码重复','422 BUSINESS_RULE_VIOLATION：停用IAM'],
         '创建演示应用或修改其名称，再查询应用列表。','更新时只传status但缺少appName，会400；停用IAM会422。',
         'PATCH在这里是不是任意单字段修改？','不是；当前实现仍要求appName，并会将省略的描述清空，演示应按真实代码说明。',frontend='ResourcesView.vue 新建/编辑应用')
for create in [True,False]:
    card('C','POST' if create else 'PATCH','/applications/{app}/menus' if create else '/applications/{app}/menus/{id}',
         '新建菜单或目录' if create else '修改菜单或目录','ResourceService.menu',MANAGED+'IAM_RESOURCES_WRITE。','全局资源；父菜单必须与当前菜单属于同应用。',
         [field('app','path ID','必填','所属应用')]+([] if create else [field('id','path ID','必填','目标菜单')])+
         ([field('menuCode','string','必填','最长64；只创建时读取')] if create else [])+
         [field('menuName','string','必填','最长128；PATCH也必填'),field('menuType','integer','可选','1目录/2权限菜单；默认2或保持原值'),field('parentId','ID或0','可选','省略/null/0设为顶级；更新时也如此'),field('path','string','可选','最长256；省略清空'),field('status','integer','可选','0/1；省略保持原值')],
         {'menuCode':'DEMO_MENU','menuName':'演示菜单','menuType':2,'parentId':'0','path':'/demo','status':1} if create else {'menuName':'更新菜单','menuType':2,'parentId':'0','path':'/demo','status':1}, MENU,
         '写 sys_menu、签名、日志；检查 sys_application、sys_role_permission、sys_menu_api。',
         'DomainRepository.get/lock/save/query/apis。',
         ['校验应用及原菜单。','检查父链同应用、不能指向自己或形成循环。','已有角色授权或API关联的菜单不能改成目录。','保存父节点/路径/状态并签名、记录日志。'],RESOURCE_TX,'MENU_SAVE。',
         ['400 INVALID_ARGUMENT：跨应用父节点或字段不合法','422 BUSINESS_RULE_VIOLATION：循环父链或已有授权菜单改目录','409 DUPLICATE_RESOURCE：同应用菜单编码重复'],
         '创建目录与子菜单，列表显示父子ID对应。','将父节点设为自己或自己的后代，应422。',
         '目录是否直接允许API？','目录只导航；角色只能获得type=2菜单，接口关联也只允许权限菜单。',frontend='ResourcesView.vue 菜单页签')
for create in [True,False]:
    card('C','POST' if create else 'PATCH','/applications/{app}/apis' if create else '/applications/{app}/apis/{id}',
         '新建API资源' if create else '修改API资源','ResourceService.api',MANAGED+'IAM_RESOURCES_WRITE。','全局定义；应用归属不可通过更新迁移。',
         [field('app','path ID','必填','所属应用')]+([] if create else [field('id','path ID','必填','API ID')])+
         ([field('apiCode','string','必填','最长128；仅创建时读取')] if create else [])+
         [field('apiName','string','必填','最长128'),field('apiPath','string','必填','最长256；规范路由模板'),field('httpMethod','string','必填','GET/POST/PUT/PATCH/DELETE/HEAD/OPTIONS'),field('status','integer','可选','0/1')],
         {'apiCode':'DEMO_API','apiName':'查看演示','apiPath':'/demo/items/{id}','httpMethod':'GET','status':1} if create else {'apiName':'查看演示','apiPath':'/demo/items/{id}','httpMethod':'GET','status':1},API,
         '写 sys_api、签名和日志。','DomainRepository.query(同应用同方法)、save/lock(Api)。',
         ['验证写权限及应用。','规范化模板，并将变量名转为{}保存canonicalPath。','拒绝与同应用同方法其他模板重叠的路径。','保存并签名，记录API_SAVE。'],RESOURCE_TX,'API_SAVE。',
         ['400 INVALID_ROUTE_TEMPLATE：模板格式错误','400 INVALID_ARGUMENT：方法非法','409 DUPLICATE_RESOURCE：路径歧义/重复'],
         '登记GET /demo/items/{id}并查询，canonicalPath应为/demo/items/{}。','再登记GET /demo/items/{itemId}或重叠模板，应409。',
         '仅换参数名为什么也重复？','请求路由含义没有变化，不能让一个实际请求对应两个权限资源。',
         '现有服务级回归覆盖重复路径拒绝；新建成功与编辑需人工补验。',['roleAndResourceConfigurationsUseOneRbacChain'], 'ResourcesView.vue 接口资源页签')
card('C','PUT','/menus/{id}/apis','完整保存菜单接口关联','ResourceService.replaceApis',MANAGED+'IAM_RESOURCES_WRITE。','全局资源；菜单与每个API必须属于同应用。',
     [field('id','path ID','必填','菜单ID'),field('apiIds','ID array','必填','最多10000个原始元素；去重排序；[]清空')],{'apiIds':['5']},{},
     '改 sys_menu_api，更新 sys_menu 对象签名，写日志。','DomainRepository.lock(Menu)/apis/query/save/remove。',
     ['锁菜单、验签并要求type=2。','验证全部API及同应用约束。','差异增删关联，重新签名菜单的API集合。'],RESOURCE_TX,'MENU_APIS_REPLACE，记录前后ID集合。',
     ['400 INVALID_ARGUMENT：跨应用API或列表非法','422 BUSINESS_RULE_VIOLATION：目录不能关联API'],
     '勾选两个API保存，再GET关联ID集合。','加入另一个应用的API，应拒绝且原集合保留。',
     '为什么集合签名挂在菜单上？','菜单签名覆盖其API ID集合，直接篡改关联表也能被发现。',
     '服务级自动覆盖跨应用拒绝；前端保存交互为契约测试。',['roleAndResourceConfigurationsUseOneRbacChain'], 'ResourcesView.vue 关联接口')

# D: seven role and user-role endpoints.
card('D','GET','/roles','查询角色目录','ResourceService.roles',MANAGED+'IAM_ROLES_VIEW 或 IAM_GRANTS_VIEW。','角色是全局目录，不按部门过滤。',[],None,[ROLE],
     '授权公共读取＋sys_role及其菜单集合签名；无写表。','DomainRepository.all(Role)、SignatureService.verify(role)。',
     ['允许角色查看者或授权查看者读取目录。','读取全部角色并逐个验签。','返回角色数组，包含停用角色；前端按用途筛选。'],READ_TX,'不写日志。',
     ['403 PERMISSION_DENIED：缺少两种查看权限','503 INTEGRITY_FAILURE：角色或关联异常'],
     '角色管理和权限分配都能取得角色目录。','只有用户查看、没有角色/授权查看的账号应403。',
     '授权人员为什么可以读角色但不能编辑角色？','查看和编辑是不同菜单权限。',frontend='RolesView.vue；UsersView.vue 角色选择')
for create in [True,False]:
    card('D','POST' if create else 'PATCH','/roles' if create else '/roles/{id}',
         '新建角色' if create else '修改或停用角色','ResourceService.role',MANAGED+'IAM_ROLES_WRITE。','全局角色定义，不按部门过滤。',
         ([field('roleCode','string','必填','最长64；更新不读取编码')] if create else [field('id','path ID','必填','目标角色')])+
         [field('roleName','string','必填','最长128；PATCH也必填'),field('roleDesc','string','可选','最长512；省略清空'),field('status','integer','可选','0/1；新建默认1，更新省略保持')],
         {'roleCode':'DEMO_ROLE','roleName':'演示角色','status':1} if create else {'roleName':'演示角色新名称','status':0},ROLE,
         '写 sys_role、签名和日志；不直接写用户角色。','DomainRepository.save 或 lock(Role)。',
         ['校验角色维护权限。','创建或锁定原角色并验签。','保存名称、描述和状态；签名包含现有菜单集合。'],RESOURCE_TX,'ROLE_SAVE。',
         ['400 INVALID_ARGUMENT：缺名称/状态非法','409 DUPLICATE_RESOURCE：roleCode重复'],
         '创建角色，再由另一个API配置菜单，最后给用户分配角色。','停用角色后其原用户关联仍存在，但该角色不贡献权限。',
         '创建角色会立即给某个用户权限吗？','不会，创建角色、配置菜单、分配给用户是三步。',
         'POST有HTTP创建回归；PATCH暂无专用正向HTTP用例。',['roleAndResourceConfigurationsUseOneRbacChain'] if create else [],'RolesView.vue')
card('D','GET','/roles/{id}/menus','读取角色菜单ID集合','ResourceService.roleMenus',MANAGED+'IAM_ROLES_VIEW。','全局角色配置。',
     [field('id','path ID','必填','角色ID')],None,['4'],'读 sys_role、sys_role_permission、签名。','DomainRepository.get(Role)/menus。',
     ['检查查看权限。','验证角色及关联集合签名。','返回当前menuId数组供复选框回显。'],READ_TX,'不写日志。',
     ['404 RESOURCE_NOT_FOUND：角色不存在','503 INTEGRITY_FAILURE：关联签名不符'],
     '读取已有角色，与配置权限对话框勾选项对应。','直接篡改该角色菜单关联，再读应503。',
     '为何返回ID数组而不是菜单详情？','关联接口只负责关系；菜单详情由C的资源目录提供。',frontend='RolesView.vue 配置权限回显')
card('D','PUT','/roles/{id}/menus','完整保存角色菜单权限','ResourceService.replaceMenus',MANAGED+'IAM_ROLE_PERMISSIONS_WRITE。','全局角色配置。',
     [field('id','path ID','必填','角色ID'),field('menuIds','ID array','必填','最多10000个原始元素；只能选type=2菜单；[]清空')],{'menuIds':['4']},{},
     '改 sys_role_permission；更新 sys_role 签名；写日志。','DomainRepository.lock(Role)/menus/query/save/remove。',
     ['锁角色并验签。','验证所有目标菜单签名和type=2。','差异增删关联，签名新集合并记录日志。'],RESOURCE_TX,'ROLE_MENUS_REPLACE。',
     ['400 INVALID_ARGUMENT：传目录或非法ID集合','404 RESOURCE_NOT_FOUND：菜单不存在'],
     '为角色配置业务菜单，其已有用户下一次请求获得权限。','数组含不存在菜单，整体拒绝且旧集合保留。',
     '这是不是RBAC1角色继承？','不是，角色只直接关联菜单，没有角色到角色的关系。',
     'HTTP自动覆盖角色菜单保存；无效替换回滚另有用户角色服务级测试。',['roleAndResourceConfigurationsUseOneRbacChain'], 'RolesView.vue；界面还需要资源查看权限用于选菜单')
card('D','GET','/users/{id}/roles','读取用户角色ID集合','GrantService.roles',MANAGED+'IAM_GRANTS_VIEW。','目标用户必须在操作者范围内。',
     [field('id','path ID','必填','目标用户')],None,['6'],'读 sys_user、sys_user_role及用户签名。','DomainRepository.get(Account)/roles。',
     ['检查授权查看权限。','验证目标身份签名和部门范围。','返回唯一用户角色关系中的roleId数组。'],READ_TX,'不写日志。',
     ['403 OUT_OF_SCOPE：越界','404 RESOURCE_NOT_FOUND：用户不存在'],
     '打开用户角色对话框，与当前关系一致。','查看其他部门范围的用户角色应403。',
     '为什么没有授权来源接口？','现在只有一份用户角色关系，来源体系已删除。',frontend='UsersView.vue 角色对话框')
card('D','PUT','/users/{id}/roles','完整保存用户角色','GrantService.replace',MANAGED+'IAM_GRANTS_WRITE。','目标必须在范围内；非空角色集合要求用户可用且部门祖先有效。',
     [field('id','path ID','必填','目标用户'),field('roleIds','ID array','必填','最多500个原始元素；去重；角色必须存在并启用；[]清空')],{'roleIds':['6']},{},
     '改 sys_user_role；更新用户签名；写日志。','DomainRepository.lock(Account)/roles/query/save/remove。',
     ['检查功能权限，锁目标并检查部门范围。','校验目标状态和全部角色。','对唯一关系做差异增删，签名并记录前后集合。'],USER_TX,'USER_ROLES_REPLACE。',
     ['403 OUT_OF_SCOPE：越界','422 BUSINESS_RULE_VIOLATION：非空授权给停用/冻结目标或选停用角色','404 RESOURCE_NOT_FOUND：角色不存在'],
     '为测试用户加两个角色，再取消一个；有效权限为剩余角色并集。','包含一个不存在角色，整体失败且原关系不变。',
     '两个管理者同时保存会怎样？','锁同一个用户行后依次保存，最后得到后提交的完整集合，没有旧页面版本冲突检测。',
     '服务级自动覆盖并集、回滚、并发完整保存及自我撤权。',['multiRoleUnionImmediateRevokeAndOaRepeat','tamperingFailsAndInvalidReplacementRollsBack','competingSavesRemainCompleteAndSigned','selfRevocationNeedsNoIndependentAdministratorAndPersistsOtherGrants'],'UsersView.vue 单人角色配置')

# E: new scope and organization responsibility replaces independent administrator identity.
card('E','PUT','/users/{id}/scope','配置管理数据范围','AccountService.scope',MANAGED+'IAM_SCOPE_WRITE。','目标先在本人范围内；将目标设为ALL时，操作者自己也必须是ALL。',
     [field('id','path ID','必填','目标用户'),field('managementScope','string','必填','ALL或DEPT_TREE')],{'managementScope':'DEPT_TREE'},{},
     '改 sys_user.management_scope；更新签名、日志。','DomainRepository.lock(Account)；AccessService.scope。',
     ['校验范围配置权限。','锁目标并检查原部门范围。','仅已有ALL范围的人可授予ALL；修改字段、签名、日志。'],USER_TX,'USER_SCOPE。',
     ['400 INVALID_ARGUMENT：范围枚举不合法','403 OUT_OF_SCOPE：目标越界或非ALL操作者授予ALL'],
     'ALL操作者给测试人员设置DEPT_TREE，再用测试账号查看用户列表。','DEPT_TREE操作者尝试把目标设置ALL，应403。',
     'ALL是否等于全部功能权限？','不是，ALL只扩大对象范围，每个操作仍需对应菜单权限。',
     '服务级自动覆盖非ALL不能授予ALL；前端对话框为契约测试。',['batchPartialSuccessAndDepartmentScope'],'UsersView.vue 范围')
card('E','GET','/departments','查询本人可管理部门','OrganizationService.departments',MANAGED+'IAM_DEPTS_VIEW、IAM_USERS_VIEW、IAM_GRANTS_VIEW 任一。','ALL返回全部；DEPT_TREE仅本人部门及后代。',[],None,[DEPT],
     '读 sys_dept 与组织签名，不写表。','DomainRepository.all(Department)；AccessService.within/departmentChain。',
     ['验证允许的目录查看权限。','对每个部门检查与本人部门的祖先关系。','返回可见部门数组，供组织页和用户筛选器使用。'],READ_TX,'不写日志。',
     ['403 PERMISSION_DENIED：缺少任一种查看权限','503 INTEGRITY_FAILURE：组织环或签名异常'],
     '同一接口在组织页和用户部门选择器都可使用。','部门管理员不应得到兄弟部门；伪造前端选项仍会被后端范围校验挡住。',
     '怎样判断目标部门是否属于下属？','沿目标的parentId向上找；祖先链包含操作者deptId就属于范围。',frontend='OrganizationView.vue；UsersView.vue 部门筛选')
for frozen in [True,False]:
    card('E','POST','/departments/{id}/'+('freeze' if frozen else 'unfreeze'),
         '冻结部门权限' if frozen else '解冻部门权限','OrganizationService.freeze',MANAGED+'IAM_DEPTS_FREEZE。','目标部门必须在本人范围内。',
         [field('id','path ID','必填','目标部门')],None,{},'改 sys_dept.status；更新组织签名和日志，不删除用户角色。',
         'DomainRepository.lock(Department)；AccessService.scope。',
         ['验证功能权限。','锁目标部门，检查范围。','设置status='+('0' if frozen else '1')+'，签名并记录；后续鉴权检查祖先链。'],
         '一次普通事务，只锁目标部门一行；对后代的影响由鉴权递归检查实现。','DEPARTMENT_FREEZE，detail中的frozen为'+str(frozen).lower()+'。',
         ['403 OUT_OF_SCOPE：冻结/解冻越界','401/403：操作者账号失效或自身权限已受冻结影响'],
         '冻结祖先部门后，后代测试员工业务请求拒绝；解冻后角色仍在并恢复允许。',
         '冻结自己部门后可能失去解冻资格，应由未受该冻结影响且有权限的人操作。',
         '冻结为什么不用逐人清空角色？','把状态放在部门节点，鉴权检查祖先链即可影响整个子树，解冻也不必重建授权。',
         '服务级自动冻结/解冻，配合HTTP业务鉴权验证结果。',['freezeTransferAndTermination'],'OrganizationView.vue')

# F: five HR integration APIs, not OA.
HR_PERMISSION = 'X-Service-Key 必须匹配 iam.integration.hr-key；不要求人类用户JWT或IAM管理菜单。'
HR_SCOPE = '可信HR对全组织执行同步；不套用某个人类管理员的部门范围。调用方负责发送正确人员/组织信息。'
card('F','POST','/integrations/hr/departments','按部门编码同步组织','OrganizationService.syncDepartment',HR_PERMISSION,HR_SCOPE,
     [field('deptCode','string','必填','最长64；用它查找已有部门'),field('deptName','string','必填','最长128'),field('parentId','ID或0','可选','省略/null/0会设为根，不是保持原父节点')],
     {'deptCode':'DEMO_DEPT','deptName':'演示部门','parentId':'1'},DEPT,
     '新增/改 sys_dept；签名和日志。','DomainRepository.query(按deptCode)、lock/save。',
     ['校验HR密钥。','按编码找到对象；已有对象锁行验签。','拒绝已合并部门再同步，检查父链不能成环。','保存名字和父节点，签名和记录日志。'],RESOURCE_TX,'HR_DEPARTMENT_SYNC。',
     ['401 INVALID_SERVICE：HR密钥无效','422 BUSINESS_RULE_VIOLATION：合并部门或父链循环','404 RESOURCE_NOT_FOUND：父部门不存在'],
     '同编码重复同步，保持同一部门ID且不产生重复部门。','把父节点设为后代，拒绝并保持原结构。',
     '省略parentId时为什么部门变成根？','当前代码将省略值解释为0，因此更新也要明确传期望父节点。',frontend='无普通管理页按钮；由HR后端或IDEA HTTP Client调用')
card('F','POST','/integrations/hr/employees','按工号同步员工','OrganizationService.syncEmployee',HR_PERMISSION,HR_SCOPE,
     [field('employeeNo','string','必填','最长64；用工号匹配用户'),field('realName','string','必填','最长64'),field('phone','string','必填','规范手机号且唯一'),field('deptId','ID','必填','必须存在且未合并；可处于冻结状态'),field('password','string','仅新建必填','8–64位含字母数字；更新不修改已有密码'),field('employmentType','integer','可选','1正式/2外包；新建默认1，更新保持原值'),field('status','integer','可选','1可用/2暂停；新建默认1')],
     {'employeeNo':'DEMO001','realName':'演示员工','phone':'13800000002','deptId':'2','employmentType':1,'status':1,'password':'ExampleOnly123!'},USER,
     '新增/改 sys_user；实际换部门时清空 sys_user_role；更新签名和日志。',
     'AccountRepository.findByEmployeeNo/findByUsername；DomainRepository.lock/get/save/clearRoles。',
     ['用工号查人，已有离职账号不允许此接口恢复。','新建生成拼音首字母账号，重名追加手机号后四位和序号。','已有人员实际换部门则先清空全部角色；status暂停时使旧会话失效。','保存身份、签名和日志。'],USER_TX+' 新建时不锁不存在的用户行，由唯一约束防重复。','HR_EMPLOYEE_SYNC。',
     ['401 INVALID_SERVICE：凭据错误','422 BUSINESS_RULE_VIOLATION：目标离职或部门已合并','409 DUPLICATE_RESOURCE：手机号/工号等冲突','400 INVALID_ARGUMENT：新建缺密码等'],
     '同工号更新姓名后仍是一人一账号；新员工创建后由D授权。','同工号改deptId，应清空原角色；新建缺密码应400。',
     '工号和用户名谁是HR同步标识？','工号；用户名是系统生成的登录名，不能据此给同一员工建多个账号。',frontend='HR集成接口；用户页只展示结果')
card('F','POST','/integrations/hr/users/{id}/transfer','HR调岗','OrganizationService.transfer',HR_PERMISSION,HR_SCOPE,
     [field('id','path ID','必填','source=1且非离职员工'),field('deptId','ID','必填','新部门存在且未合并')],{'deptId':'3'},{},
     '改 sys_user.dept_id；删该用户 sys_user_role；写签名和日志。','DomainRepository.lock(Account)/get(Department)/clearRoles。',
     ['验证员工及目标部门签名。','只允许HR来源、非离职账号。','只有实际部门改变才清空全部角色并换部门。','重新签名、记录；新部门后续手动授予角色。'],USER_TX,'HR_TRANSFER。',
     ['422 BUSINESS_RULE_VIOLATION：临时/离职账号或目标部门已合并','404 RESOURCE_NOT_FOUND：目标不存在'],
     '调岗后业务与IAM管理角色都清空；重新授权后可访问新功能。','重复通知当前部门不应清空刚分配的新角色。',
     '只回收旧部门的角色还是全部？','本简化版没有来源区分，所以实际调岗清空全部旧角色。',
     '服务级自动覆盖实际调岗和重复当前部门不清角色。',['freezeTransferAndTermination'],'HR调用，无普通后台调岗按钮')
card('F','POST','/integrations/hr/users/{id}/terminate','HR离职','OrganizationService.terminate',HR_PERMISSION,HR_SCOPE,
     [field('id','path ID','必填','HR来源员工')],None,{},
     '改 sys_user.status/session_version；清空其 sys_user_role；签名和日志。','DomainRepository.lock(Account)/clearRoles。',
     ['锁并验证HR员工身份。','首次离职设status=3、清空角色、增加会话版本。','重复离职不重复递增版本，仍保留账号及日志。','不撤回此人过去给别人授予的角色。'],USER_TX,'HR_TERMINATE。',
     ['422 BUSINESS_RULE_VIOLATION：临时或初始化账号不能走HR离职','404 RESOURCE_NOT_FOUND：用户不存在'],
     '离职后旧JWT和新登录均不可用，用户记录仍能追溯。','核对其他用户角色未因原授权者离职而被清空。',
     '为什么不物理删除账号？','保留身份ID与历史日志，能够追溯离职前操作。',
     '服务级自动覆盖角色清空、旧JWT失效和他人授权保留。',['freezeTransferAndTermination'],'HR调用')
card('F','POST','/integrations/hr/departments/{id}/merge','HR合并部门','OrganizationService.merge',HR_PERMISSION,HR_SCOPE,
     [field('id','path ID','必填','源部门'),field('targetDeptId','ID','必填','目标部门；不能相同或为源部门后代')],{'targetDeptId':'3'},{},
     '改源部门status/merged_into_dept_id、直属子部门parent_id、直属员工dept_id；保留角色；签名和日志。',
     'DomainRepository.lock(两部门)、query(Account/Department)、逐行lock。',
     ['按ID顺序锁源和目标两行，避免相反合并请求锁顺序冲突。','同源同目标重复请求直接返回；拒绝已合并对象和循环结构。','移动直属员工与直属子部门，保留角色并重签受影响对象。','源部门冻结并标记合并去向。'],
     '一个普通事务，锁两部门及受影响人员/直属子部门；不使用全局管理员锁。','HR_DEPARTMENT_MERGE。',
     ['400 INVALID_ARGUMENT：自己合并自己','422 BUSINESS_RULE_VIOLATION：循环或合并状态不允许','503 INTEGRITY_FAILURE：受影响对象签名异常'],
     '在小型演示部门合并后，核对人员归属、子部门父节点和原角色仍在。','目标设为源部门后代应拒绝，原数据不变。',
     '合并与调岗为何角色处理不同？','已确认的业务规则不同：调岗清空，合并暂保留给新部门人工调整。',frontend='HR调用；普通组织页显示合并状态')

# G: original batch, OA and audit ownership, plus shared signature/initialization work.
card('G','POST','/grants/batch','同步批量授予或回收一个角色','BatchService.execute',MANAGED+'每一项内部仍要求 IAM_GRANTS_WRITE。','每个目标都独立检查操作者的部门范围。',
     [field('userIds','ID array','必填','原始数组最多100个；去重排序后至少1个'),field('roleId','ID','必填','本批次同一个角色'),field('operation','string','必填','GRANT或REVOKE')],
     {'userIds':['101','102'],'roleId':'6','operation':'GRANT'},
     {'items':[{'userId':'101','success':True},{'userId':'102','success':False,'message':'目标不在管理范围内'}],'succeeded':'1','total':2},
     '逐人改 sys_user_role、更新用户签名、写日志；没有批量任务表。',
     'BatchService → GrantService.change代理 → DomainRepository.lock/query/save/remove。',
     ['校验批量输入。','同步循环，每人通过另一个Service代理进入独立事务并重新检查权限/范围。','业务4xx逐项记录并继续；401或5xx直接中断。','正常完成返回每项结果、成功数和总数。'],
     'BatchService无外层事务；每个GrantService.change独立事务。后续项失败不回滚前面成功项。',
     '每项USER_ROLE_CHANGE；没有独立任务日志或任务ID。',
     ['400 INVALID_ARGUMENT：超过100或空数组','200内success=false：单项越界等业务失败','401/503：身份或基础设施问题中断；应查询当前结果'],
     '同一批包含范围内和范围外用户，前者成功，后者失败。','批量超过100应400；中途基础设施失败不能认为此前全部回滚。',
     '为什么不把整个循环放一个事务？','这里需要部分成功；整个循环一个事务会改变已确认语义。',
     '服务级自动覆盖部分成功和范围拒绝。',['batchPartialSuccessAndDepartmentScope'],'UsersView.vue 权限分配批量栏')
card('G','POST','/integrations/oa/roles','OA审批后执行角色授予或回收','GrantService.oa',
     'X-Service-Key 必须匹配 iam.integration.oa-key；不接受应用鉴权密钥替代。',
     'OA是可信全局集成，不套人类操作者范围；输入决定目标员工和角色。',
     [field('userId','ID','必填','目标用户'),field('roleId','ID','必填','角色ID'),field('operation','string','必填','GRANT或REVOKE')],
     {'userId':'101','roleId':'6','operation':'GRANT'}, {},
     '改 sys_user_role；更新用户签名；写日志。','DomainRepository.lock(Account)/query(UserRole)/save/remove。',
     ['验证OA专用密钥。','锁目标用户并验签；授予时检查用户/部门有效和角色启用。','已有关系重复授予不重复插入，不存在关系重复回收不报来源错误。','签名并记录OA_ROLE_CHANGE，不执行审批流程。'],
     '单目标普通事务，角色关系、用户签名、日志原子提交。','OA_ROLE_CHANGE；没有人类操作者时userId为空，targetUserId为目标。',
     ['401 INVALID_SERVICE：密钥错','400 INVALID_ARGUMENT：操作不是GRANT/REVOKE','422 BUSINESS_RULE_VIOLATION：不能给无效目标授予'],
     '审批结果重复授予同角色，关系仍只有一条。','先手工授予，再OA回收，此角色应直接移除，因为没有多来源保留。',
     'OA是否能知道是手工还是审批授予？','不能区分，也不需要；本版两者修改同一份关系。',
     'HTTP自动覆盖无效密钥和重复回收。',['multiRoleUnionImmediateRevokeAndOaRepeat'],'OA服务或IDEA HTTP Client')
LOG = dict(id='1', userId='101', targetUserId='101', deptId='2', operationType='AUTH_CHECK',
           targetType='APPLICATION', targetId='3', detail='GET /demo/items/{id} ALLOW',
           success=True, createdAt='2026-10-25T10:00:00')
card('G','GET','/audit/operations','分页查询操作与鉴权日志','QueryService.logs',MANAGED+'IAM_AUDIT_VIEW。',
     'ALL看全部；DEPT_TREE按日志当时保存的deptId快照与当前可管理部门集合筛选。全局/无部门日志只有ALL可见。',
     [field('operation','query string','可选','按operationType精确匹配；省略看所有类型'),field('page','query integer','默认1','>=1'),field('pageSize','query integer','默认20','1–100')],None,
     {'items':[LOG],'total':'1','page':1,'pageSize':20},'读 sys_operation_log 和范围/授权表；无写表。',
     'DomainRepository.page(OperationLog)/query(count)；AccountService.visibleDepartments。',
     ['校验日志查看权限与分页。','构造类型过滤及部门快照过滤。','按id倒序分页，返回items/total/page/pageSize。'],READ_TX,'查询本身不再写日志，避免循环审计。',
     ['400 INVALID_ARGUMENT：分页非法','403 PERMISSION_DENIED：缺日志权限'],
     '筛选LOGIN看到成功及失败记录；筛选AUTH_CHECK看到允许和拒绝。','部门管理员不能看到范围外或deptId为空的全局日志。',
     '为什么不能只看当前员工所在部门来过滤历史日志？','日志保存当时部门快照，当前调岗不能重写历史操作归属。',frontend='AuditView.vue',query='?page=1&pageSize=20')


def controller_routes():
    result = {}
    for file in (ROOT / 'src/main/java/com/whatname/iam/controller').glob('*.java'):
        text = file.read_text(encoding='utf-8')
        prefix = re.search(r'@RequestMapping\("([^"]+)"\)', text).group(1)
        for match in re.finditer(r'@(Get|Post|Put|Patch|Delete)Mapping\("([^"]+)"\)', text):
            method = re.search(r'public\s+ApiResponse<[^>]+>\s+(\w+)\s*\(', text[match.end():]).group(1)
            key = (match.group(1).upper(), prefix + match.group(2))
            if key in result:
                raise ValueError(f'Duplicate controller route: {key}')
            result[key] = {'file': file.relative_to(ROOT).as_posix(),
                           'line': text[:match.start()].count('\n') + 1,
                           'method': file.stem + '.' + method}
    return result


def source_ref(symbol, from_api=True):
    cls, method = symbol.split('.', 1) if '.' in symbol else (symbol, '')
    candidates = list((ROOT / 'src').rglob(cls + '.java'))
    if len(candidates) != 1:
        raise ValueError('Cannot resolve source: ' + symbol)
    p = candidates[0]
    text = p.read_text(encoding='utf-8')
    start = 0
    if method:
        match = re.search(r'\b(?:public|private|protected)\s+(?:static\s+)?[^;{}=]*?\b' + re.escape(method) + r'\s*\(', text)
        if match is None:
            match = re.search(r'(?m)^\s*(?:void|boolean|String|long|int)\s+' + re.escape(method) + r'\s*\(', text)
        if match is None:
            raise ValueError('Cannot resolve method: ' + symbol)
        start = match.start()
    line = text[:start].count('\n') + 1
    prefix = '../../../' if from_api else '../../'
    return f'[{symbol}]({prefix}{p.relative_to(ROOT).as_posix()}#L{line})'


def compact(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def group_file(group):
    return group + '-' + GROUPS[group][0] + '.md'


def render_card(c, controller):
    c['controller'] = controller
    control_link = f'[{controller["method"]}](../../../{controller["file"]}#L{controller["line"]})'
    service = source_ref(c['service'])
    repository_links = []
    for repository in ['AccountRepository', 'DomainRepository']:
        if repository in c['repository']:
            repository_links.append(source_ref(repository))
    repository_links = '；'.join(repository_links) or '无持久层调用。'
    req_path = re.sub(r'\{([^}]+)\}', '2', c['path']) + c['query']
    if c['path'].startswith('/api/v1/demo/'):
        req_path = '/api/v1/demo/OA/feature1'
    request = f'{c["method"]} {req_path}'
    if c['path'] == '/api/v1/auth/check':
        request += '\nAuthorization: Bearer <JWT>\nX-App-Code: OA\nX-Service-Key: <OA_APP_KEY>'
    elif '/integrations/hr/' in c['path']:
        request += '\nX-Service-Key: <HR_SERVICE_KEY>'
    elif '/integrations/oa/' in c['path']:
        request += '\nX-Service-Key: <OA_SERVICE_KEY>'
    elif c['path'] != '/api/v1/auth/login' and not c['support']:
        request += '\nAuthorization: Bearer <JWT>'
    if c['body'] is not None:
        request += '\nContent-Type: application/json\n\n' + json.dumps(c['body'], ensure_ascii=False, indent=2)
    fields = '\n'.join('| ' + ' | '.join(row) + ' |' for row in c['fields']) or '| 无 | — | — | 无请求体与业务参数 |'
    steps = '\n'.join(f'{i}. {step}' for i, step in enumerate(c['flow'], 1))
    errors = '\n'.join('- ' + e for e in c['errors']) or '- 没有本接口专用业务错误；HTTP层与运行故障见公共说明。'
    tests = '；'.join(source_ref('CourseworkFlowTest.' + t) for t in c['tests']) or '暂无关联专用自动用例。'
    return f'''<a id="{c['id']}"></a>
## {c['id']} · {c['title']}

`{c['method']} {c['path']}` · 主讲：{c['owner']} · {'运行检查' if c['support'] else '业务接口'}

**入口与调用链：** {control_link} → {service} → {c['repository']}

**持久层源码：** {repository_links}

**页面/调用方：** {c['frontend'] or 'IDEA HTTP Client / 运维检查。'}

**权限：** {c['permission']}

**范围：** {c['scope']}

### 参数与示例

| 字段 | 类型/位置 | 必填/默认 | 实际实现规则 |
|---|---|---|---|
{fields}

```http
{request}
```

成功为 HTTP 200，外层统一 `{{"success":true,"code":"OK","requestId":"…","data":…}}`。下面仅展示 `data`，对象字段的空值省略规则见 [公共说明](00-公共接口规则.md)。

```json
{json.dumps(c['result'], ensure_ascii=False, indent=2)}
```

### 怎么实现、改哪些表

{steps}

**数据库：** {c['tables']}

**事务与锁：** {c['transaction']}

**签名与日志：** {c['audit']}

### 验证与答辩

**现有测试：** {c['coverage']} {tests}

**正向演示：** {c['success']}

**反向演示：** {c['failure']}

常见错误：

{errors}

**老师可能问：** {c['question']}

**回答要点：** {c['answer']}

'''


COMMON = '''# 公共接口规则：每位同学先读

本手册依据当前三个 Controller 的真实路由整理。共45个路由，按“HTTP方法＋完整路径模板”唯一识别；其中43个业务接口、2个运行检查。一个路由由一个人主讲，公共类可以共同阅读。

## 请求、返回和示例

- 前缀 `/api/v1`，管理接口通常使用 `Authorization: Bearer <JWT>`。
- 示例中的密码、人员、ID均为虚构格式示例；演示前从测试库查询并替换为准备好的对象。HTTP示例文件不会自动执行。
- 成功响应为 `{success:true,code:"OK",requestId:"…",data:…}`；失败为 `{success:false,code,message,requestId}`。创建接口当前也返回200，不是201。
- Java Long（用户ID、关联ID、count结果）由WebConfig序列化成字符串；int仍为数字。分页total通常是字符串，空可见集合的特判返回int 0；前端使用Number(total)兼容。批量succeeded是Long字符串，total是int数字。
- ApiResponse外层空值被省略；Domain实体使用普通Jackson字段序列化，employeeNo/roleDesc等可为null。Account.passwordHash与sessionVersion带@JsonIgnore，不返回；客户端不能据此修改会话版本。
- 400：参数/路径格式；401：身份、密码或服务凭据错误；403：功能权限或部门越界；404：对象不存在；409：唯一冲突；422：业务规则；503：签名或数据库故障。不能把任何运行错误都说成“没权限”。

## 三种入口

1. 身份接口：login公开；me/menus/logout/password仅处理本人，仍验证JWT和账号状态。
2. 管理接口：ManagementInterceptor先检查登记的IAM菜单/API映射；Service再检查菜单编码与目标范围。IAM自己也受RBAC0约束，不能用用户名或管理员等级绕过。
3. HR/OA集成：对应服务密钥；外部auth/check同时需要用户JWT及该应用专用密钥。两组密钥命名空间不同，不允许互换。Demo接口模拟业务后端并复用同一权限计算器。

## 部门范围不等于功能权限

`ALL`表示全公司，`DEPT_TREE`表示本人deptId及后代。E负责讲解公共范围算法；A/D等接口负责人仍要说明本接口在哪一步调用scope。角色定义和资源目录是全局配置，不按部门过滤。

冻结检查的是“被鉴权用户”的部门祖先链。管理人员冻结自己的部门，自己也可能失去操作权限；并不存在超级用户名自动放行。

## 一次请求的数据库读取与写入

管理入口通常会读取整条权限主链，所以接口卡中的“业务表”不能理解为该请求唯一访问的表。常见公共读取为sys_user、sys_dept、sys_application、sys_user_role、sys_role、sys_role_permission、sys_menu、sys_menu_api、sys_api、sys_data_signature。

改用户角色锁用户行，改角色菜单锁角色行，改菜单API锁菜单行；不存在expectedVersion和全表管理锁。后保存可以覆盖旧页面，但事务结果必须是一个完整集合。签名与变更日志随业务同事务，失败回滚。

BatchService无外层事务，通过GrantService代理逐人提交。HR/OA不保存事件重放结果、不自动识别乱序旧通知。调岗清空全部原角色，合并暂保留角色，离职保留账号但使旧会话失效。

## 测试证据如何讲

每张卡区分直接HTTP、服务级测试与人工演示。前端Playwright请求被拦截，只证明页面契约，不等于真实数据库端到端验证。某个相关测试存在不代表该接口所有分支都已覆盖。

`/auth/check`权限不足可返回HTTP200且allowed=false；业务后端必须检查allowed。Demo接口则在判断拒绝后返回403。不要把这两种返回方式混淆。

## 当前实现的细节不要讲成别的需求

- 用户修改临时资料是真正按给出字段更新；应用、角色、菜单、API的PATCH仍要求名称等字段。菜单PATCH省略parentId会设为根，省略path会清空；应用/角色省略描述会清空。
- 角色ID、菜单ID、API ID数组在校验原始大小后去重排序；数量限制不能只数去重后的元素。
- 没有独立管理员任命、多来源授权、短信、密码历史、后台任务、日志封印。
- 当前日志不保证对所有身份/签名异常留下拒绝记录；不能宣称记录完整授权证据快照。

以上均描述现有实现。本次手册补充不改变业务规则。
'''


def render_index(routes):
    lines = ['# A–G 分工与逐API答辩入口', '',
             '当前代码共 **45个HTTP接口：43个业务接口＋2个运行检查**。下面每个接口只出现一次；每个人先读自己的接口卡，再准备公共链路说明。', '',
             '| 人员 | 主讲范围 | 业务接口数 | 运行检查数 | 阅读入口 |', '|---|---|---:|---:|---|']
    for owner, (title, _, responsibility) in GROUPS.items():
        cards = [c for c in CARDS if c['owner'] == owner]
        lines.append(f'| {owner} | {responsibility} | {sum(not c["support"] for c in cards)} | {sum(c["support"] for c in cards)} | [{title}](api/{group_file(owner)}) |')
    lines += ['', '保持原A–G边界：A账号，B鉴权，C资源，D角色授权，E管理范围与组织，F HR，G批量/OA/审计。旧管理员任命已删除，E转为主讲实际参与多个接口的数据范围算法。G还负责签名、初始化和恢复，不能仅按HTTP接口数量评价工作量。', '',
              '## 所有人共同掌握', '',
              '先读 [公共接口规则](api/00-公共接口规则.md)。每人应能解释：身份如何验证、权限从哪里来、目标范围怎样限制、事务如何提交、错误为什么发生。', '',
              '## 完整接口归属表', '', '| 编号 | 主讲 | 方法与完整路径 | 用途 |', '|---|---|---|---|']
    for c in CARDS:
        lines.append(f'| [{c["id"]}](api/{group_file(c["owner"])}#{c["id"]}) | {c["owner"]} | `{c["method"]} {c["path"]}` | {c["title"]} |')
    lines += ['', '## 每人的交付物', '',
              '1. 按自己的API卡走读源码，能定位Controller、Service、Repository与涉及表。',
              '2. 至少完成一条完整正向链路和两条反向用例，记录HTTP状态、响应和数据库变化。',
              '3. 准备3–5分钟讲解：解决什么问题 → 请求怎么走 → 权限/范围在哪里检查 → 数据如何变化 → 怎样验证。',
              '4. 在本人的 `.http` 示例中填测试账号、JWT、部门/角色ID；服务密钥只放本地私有环境，不上传。',
              '5. 没有专用自动用例的接口，明确标为人工验收，不能把相关测试冒充完整覆盖。', '',
              '## 跨人员交接', '',
              '- A给B一个有效JWT；D准备用户角色，C准备菜单/API关系，B展示允许、拒绝和即时撤权。',
              '- E准备ALL与DEPT_TREE两名测试管理者；A/D/G用同一批目标证明范围检查一致。',
              '- F调岗或离职测试员工后，D核对角色，B核对鉴权，G核对签名与日志。',
              '- G演示OA与手工操作同一份授权关系、批量部分成功，以及初始化账号误撤权后的本地恢复。', '',
              '## 接口覆盖核对', '',
              '执行 `python scripts/generate-api-handbook.py --check`：与三个Controller路由及OpenAPI核对，确认45个接口无遗漏、无重复；同时验证源码方法和文档链接。这个命令只读检查文档，不启动后端、不修改数据库。', '']
    return '\n'.join(lines)


def render_person(owner, routes):
    title, sources, responsibilities = GROUPS[owner]
    cards = [c for c in CARDS if c['owner'] == owner]
    lines = [f'# {owner} · {title}', '', f'职责：{responsibilities}。主讲 {len(cards)} 个接口。', '',
             f'核心源码：{sources}。', '',
             '[分工总表](../分工-A-G与API清单.md) · [公共规则](00-公共接口规则.md)', '',
             '建议顺序：先读接口表 → 对照调用链 → 在IDEA HTTP Client完成正反案例 → 准备每张卡最后的答辩问题。', '',
             '| 编号 | 方法与路径 | 用途 |', '|---|---|---|']
    for c in cards:
        lines.append(f'| [{c["id"]}](#{c["id"]}) | `{c["method"]} {c["path"]}` | {c["title"]} |')
    lines += ['', f'HTTP示例文件：[本组请求](../../../requests/learning/{owner}.http)。变量均为待填写占位，密码和密钥不要提交。', '']
    if owner == 'B':
        lines += ['## B必须额外主讲的公共链路', '',
                  'IAM自我管理不是额外一个HTTP接口，而是所有管理API入口都执行的公共机制。走读 '+source_ref('ManagementInterceptor.preHandle')+' → '+source_ref('AccessService.requireApi')+' → '+source_ref('AccessService.allowed')+' → '+source_ref('AccessService.menus')+'。', '',
                  '演示同一管理页面：只读角色没有维护按钮；即使手工调用写接口也403；撤掉IAM角色后本人也失去管理能力。没有sys_admin或用户名放行。', '']
    if owner == 'E':
        lines += ['## E必须额外主讲的公共算法', '',
                  '没有独立管理员身份API。E负责 '+source_ref('AccessService.within')+'、'+source_ref('AccessService.scope')+'、'+source_ref('AccessService.departmentChain')+'，这些方法被A/D/G等人的接口调用。', '',
                  '画一棵三层部门树：标出本人部门、后代、兄弟部门；分别解释ALL、DEPT_TREE和冻结祖先的影响。范围不产生功能权限。', '']
    if owner == 'G':
        lines += ['## G必须额外主讲的公共能力（不是HTTP接口）', '',
                  '| 能力 | 源码入口 | 演示重点 |', '|---|---|---|',
                  f'| 基础签名 | {source_ref("SignatureService.sign")} / {source_ref("SignatureService.verify")} | 用户签名覆盖角色集合，角色覆盖菜单集合，菜单覆盖API集合；篡改拒绝，不自动重签 |',
                  f'| 业务日志 | {source_ref("AuditService.record")} | 日志随变更提交；部门快照和服务操作者为空的含义 |',
                  f'| 全量初始化 | {source_ref("InitializationService.initialize")} | 空库、111部门、10000员工、来源资源和IAM自举；不是HTTP接口 |',
                  f'| 本地恢复 | {source_ref("InitializationService.recover")} | 恢复初始化账号管理角色、范围和密码，不修复篡改数据 |',
                  f'| CLI入口 | {source_ref("InitializationRunner.run")} | IDEA“初始化数据库”配置，密码环境变量，不提交个人配置 |',
                  f'| 集成身份 | {source_ref("ServiceKeys.require")} / {source_ref("ServiceKeys.requireApplication")} | HR/OA集成密钥和业务鉴权密钥不可互换 |', '',
                  '自动证据：'+source_ref('CourseworkFlowTest.fullSourceImportAndLocalRecovery')+'、'+source_ref('CourseworkFlowTest.tamperingFailsAndInvalidReplacementRollsBack')+'。初始化全量用例需开启coursework.fullImport=true，不应在日常已有数据库上重复执行。', '']
    for c in cards:
        lines.append(render_card(c, routes[(c['method'], c['path'])]))
    return '\n'.join(lines)


def render_http(owner):
    parts = [f'# {owner}组：{GROUPS[owner][0]}。仅为示例；不会自动执行。',
             '# 将同目录 http-client.private.env.example.json 复制为 http-client.private.env.json。',
             '# 只在私有文件填写账号/密码/密钥和测试对象ID；在IDEA选择coursework-local环境。',
             '# 私有文件已被Git忽略，请不要把真实凭据填进本.http文件。', '']
    for c in [c for c in CARDS if c['owner'] == owner]:
        path = c['path']
        id_var = 'userId' if '/users/' in path else 'roleId' if '/roles/' in path else 'menuId' if '/menus/' in path and '{app}' not in path else 'apiId' if '/apis/' in path else 'menuId' if '{app}' in path else 'appId' if '/applications/' in path else 'deptId'
        path = path.replace('{app}', '{{appId}}').replace('{id}', '{{'+id_var+'}}')
        path = path.replace('{appCode}', 'OA').replace('{feature}', 'feature1')
        parts += [f'### {c["id"]} {c["title"]}',f'{c["method"]} {{{{base}}}}{path}{c["query"]}']
        if '/integrations/hr/' in c['path']: parts.append('X-Service-Key: {{hrKey}}')
        elif '/integrations/oa/' in c['path']: parts.append('X-Service-Key: {{oaKey}}')
        elif c['path'] != '/api/v1/auth/login' and not c['support']: parts.append('Authorization: Bearer {{token}}')
        if c['path'] == '/api/v1/auth/check': parts += ['X-App-Code: OA','X-Service-Key: {{appKey}}']
        if c['body'] is not None:
            body = dict(c['body'])
            for key, var in [('deptId','deptId'),('userId','userId'),('roleId','roleId'),('targetDeptId','targetDeptId'),('parentId','parentDeptId')]:
                if key in body: body[key] = '{{'+var+'}}'
            if 'userIds' in body: body['userIds']=['{{userId}}']
            if 'identifier' in body: body['identifier']='{{loginIdentifier}}'
            if 'password' in body: body['password']='{{loginPassword}}' if c['path']=='/api/v1/auth/login' else '{{newPassword}}'
            if 'oldPassword' in body: body['oldPassword']='{{oldPassword}}'
            if 'roleIds' in body: body['roleIds']=['{{roleId}}']
            if 'menuIds' in body: body['menuIds']=['{{menuId}}']
            if 'apiIds' in body: body['apiIds']=['{{apiId}}']
            parts += ['Content-Type: application/json','',json.dumps(body, ensure_ascii=False, indent=2)]
        parts.append('')
    return '\n'.join(parts)


def validate_inventory(routes):
    keys = [(c['method'], c['path']) for c in CARDS]
    if len(keys) != len(set(keys)):
        raise ValueError('Duplicate API ownership')
    if set(keys) != set(routes):
        raise ValueError(f'Controller mismatch: missing={set(routes)-set(keys)}, extra={set(keys)-set(routes)}')
    spec = json.loads((ROOT/'docs/contracts/03-openapi.yaml').read_text(encoding='utf-8'))
    spec_keys = {(method.upper(), path) for path, methods in spec['paths'].items() for method in methods}
    if set(keys) != spec_keys:
        raise ValueError('OpenAPI route mismatch')
    for c in CARDS:
        source_ref(c['service'])
        for test in c['tests']: source_ref('CourseworkFlowTest.'+test)
    return keys


def validate_links():
    checked = 0
    for p in [OUT/'分工-A-G与API清单.md', *API_DIR.glob('*.md')]:
        text = p.read_text(encoding='utf-8')
        for link in re.findall(r'\]\(([^)]+)\)', text):
            if '://' in link: continue
            file, _, anchor = link.partition('#')
            target = (p.parent/file).resolve() if file else p
            if not target.is_file(): raise ValueError(f'Broken link in {p.name}: {link}')
            if anchor.startswith('L') and anchor[1:].isdigit():
                if not 1 <= int(anchor[1:]) <= len(target.read_text(encoding='utf-8').splitlines()):
                    raise ValueError('Invalid source line: '+link)
            elif anchor and not re.search(r'id="'+re.escape(anchor)+'"',target.read_text(encoding='utf-8')):
                raise ValueError('Invalid API anchor: '+link)
            checked += 1
    return checked


def main():
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args=parser.parse_args()
    routes=controller_routes()
    validate_inventory(routes)
    generated={API_DIR/'00-公共接口规则.md':COMMON, OUT/'分工-A-G与API清单.md':render_index(routes)}
    for owner in GROUPS:
        generated[API_DIR/group_file(owner)]=render_person(owner,routes)
        generated[ROOT/f'requests/learning/{owner}.http']=render_http(owner)
    environment = {'coursework-local':{
        'base':'http://localhost:8080','token':'填写测试用户JWT',
        'loginIdentifier':'填写测试账号','loginPassword':'仅在私有文件填写密码',
        'oldPassword':'仅在私有文件填写原密码','newPassword':'仅在私有文件填写新密码',
        'userId':'填写测试用户ID','deptId':'填写源测试部门ID',
        'targetDeptId':'填写目标测试部门ID','parentDeptId':'0',
        'appId':'填写测试应用ID','roleId':'填写测试角色ID',
        'menuId':'填写测试菜单ID','apiId':'填写测试API的ID',
        'hrKey':'仅在私有文件填写HR集成密钥','oaKey':'仅在私有文件填写OA集成密钥',
        'appKey':'仅在私有文件填写OA应用鉴权密钥'}}
    generated[ROOT/'requests/learning/http-client.private.env.example.json']=json.dumps(environment,ensure_ascii=False,indent=2)+'\n'
    generated[OUT/'api-index.json']=json.dumps(CARDS,ensure_ascii=False,indent=2)+'\n'
    generated = {file: text.rstrip() + '\n' for file, text in generated.items()}
    if args.check:
        for file,text in generated.items():
            if not file.exists() or file.read_text(encoding='utf-8') != text:
                raise ValueError('Missing or stale generated handbook: '+str(file.relative_to(ROOT)))
    else:
        for file,text in generated.items():
            file.parent.mkdir(parents=True,exist_ok=True)
            file.write_text(text,encoding='utf-8')
    links=validate_links()
    print(json.dumps({'routes':len(CARDS),'business':sum(not c['support'] for c in CARDS),
                      'support':sum(c['support'] for c in CARDS),'owners':{g:sum(c['owner']==g for c in CARDS) for g in GROUPS},
                      'verifiedLinks':links,'mode':'check' if args.check else 'generated'},ensure_ascii=True))


if __name__=='__main__':
    main()
