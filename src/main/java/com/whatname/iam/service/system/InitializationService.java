package com.whatname.iam.service.system;

import static com.whatname.iam.service.permission.SignedDataType.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.security.SensitiveDataCodec;
import com.whatname.iam.common.util.RouteTemplateUtils;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.auth.NamePinyin;
import com.whatname.iam.service.auth.PasswordHasher;
import com.whatname.iam.service.auth.PasswordPolicy;
import com.whatname.iam.service.hr.HrLoginName;
import com.whatname.iam.service.permission.DataSignatureService;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.util.*;

/**
 * 受控初始化（详细设计 7.6 超级用户初始化与兜底、10 数据准备）。只在空库上线时运行一次：
 *
 * <ol>
 *   <li>导入部门树和员工（scripts/prepare-data.py 从 Excel 生成的 prepared-data.json）；
 *   <li>登记本系统（IAM）和两个演示业务系统的菜单、API 及菜单—API 映射；
 *   <li>创建超级角色、一级管理角色和演示业务角色；
 *   <li>创建超级用户：持有超级角色，并任命为一级管理员；
 *   <li>为以上数据签名，记录一条初始化审计。
 * </ol>
 *
 * recover 只恢复超级用户的任命和超级角色，用于唯一管理员误删自己权限后的兜底。
 */
@Service
public class InitializationService {
    /** IAM 自身菜单：编码、名称、页面路径、对应 API（方法 + 路径）。 */
    static final List<MenuSpec> IAM_MENUS =
            List.of(
                    menu(
                            "IAM_USERS_VIEW",
                            "用户账号",
                            "/users",
                            "GET /api/v1/users",
                            "GET /api/v1/users/{userId}",
                            "GET /api/v1/departments/{deptId}/users",
                            "GET /api/v1/users/{userId}/permissions"),
                    menu(
                            "IAM_USERS_WRITE",
                            "账号维护",
                            null,
                            "POST /api/v1/users/temporary",
                            "PATCH /api/v1/users/{userId}/status"),
                    menu(
                            "IAM_ROLES_VIEW",
                            "角色管理",
                            "/roles",
                            "GET /api/v1/roles",
                            "GET /api/v1/roles/{roleId}",
                            "GET /api/v1/roles/{roleId}/menus"),
                    menu(
                            "IAM_ROLES_WRITE",
                            "角色维护",
                            null,
                            "POST /api/v1/roles",
                            "PATCH /api/v1/roles/{roleId}",
                            "PUT /api/v1/roles/{roleId}/menus"),
                    menu(
                            "IAM_RESOURCES_VIEW",
                            "应用管理",
                            "/resources",
                            "GET /api/v1/applications",
                            "GET /api/v1/applications/{appId}/menus",
                            "GET /api/v1/applications/{appId}/apis",
                            "GET /api/v1/menus/{menuId}/apis"),
                    menu(
                            "IAM_RESOURCES_WRITE",
                            "应用维护",
                            null,
                            "POST /api/v1/applications",
                            "PATCH /api/v1/applications/{appId}",
                            "POST /api/v1/applications/{appId}/menus",
                            "PATCH /api/v1/menus/{menuId}",
                            "POST /api/v1/applications/{appId}/apis",
                            "PATCH /api/v1/apis/{apiId}",
                            "PUT /api/v1/menus/{menuId}/apis"),
                    menu(
                            "IAM_GRANTS_VIEW",
                            "权限分配",
                            "/grants",
                            "GET /api/v1/grants/history",
                            "GET /api/v1/users/{userId}/roles",
                            "GET /api/v1/users/{userId}/roles/{roleId}/sources",
                            "GET /api/v1/grants/batches/{batchId}"),
                    menu(
                            "IAM_GRANTS_WRITE",
                            "授权与回收",
                            null,
                            "POST /api/v1/grants/user-roles",
                            "POST /api/v1/grants/user-roles/revoke",
                            "POST /api/v1/grants/user-roles/batch"),
                    menu(
                            "IAM_GRANTS_DELEGATE",
                            "委派可授权角色",
                            null,
                            "GET /api/v1/admins/{userId}/delegable-roles",
                            "PUT /api/v1/admins/{userId}/delegable-roles"),
                    menu("IAM_DEPTS_VIEW", "人员与组织变动", "/departments", "GET /api/v1/departments"),
                    menu(
                            "IAM_DEPTS_FREEZE",
                            "部门冻结",
                            null,
                            "POST /api/v1/departments/{deptId}/freeze",
                            "POST /api/v1/departments/{deptId}/unfreeze"),
                    menu(
                            "IAM_ADMINS_VIEW",
                            "管理员管理",
                            "/admins",
                            "GET /api/v1/admins",
                            "GET /api/v1/admins/me/scope"),
                    menu(
                            "IAM_ADMINS_WRITE",
                            "任命与撤销",
                            null,
                            "POST /api/v1/admins/appointments",
                            "POST /api/v1/admins/{userId}/revoke"),
                    menu(
                            "IAM_AUDIT_VIEW",
                            "操作日志",
                            "/audit",
                            "GET /api/v1/audit/operations",
                            "GET /api/v1/audit/authorizations",
                            "GET /api/v1/audit/logins",
                            "GET /api/v1/audit/evidence/{eventId}"));

    /** 演示业务系统一：OA 协同办公（菜单取自功能权限清单）。 */
    static final List<MenuSpec> OA_MENUS =
            List.of(
                    menu(
                            "OA_FLOW",
                            "流程发起与审批",
                            "/flow",
                            "GET /oa/flows",
                            "POST /oa/flows",
                            "POST /oa/flows/{flowId}/approve"),
                    menu("OA_LEAVE", "请假申请", "/leave", "GET /oa/leaves", "POST /oa/leaves"),
                    menu(
                            "OA_MEETING",
                            "会议室预约",
                            "/meeting",
                            "GET /oa/meetings",
                            "POST /oa/meetings"),
                    menu(
                            "OA_DOCUMENT",
                            "收文登记与流转",
                            "/document",
                            "GET /oa/documents",
                            "POST /oa/documents"));

    /** 演示业务系统二：人力资源（考勤打卡等）。 */
    static final List<MenuSpec> HR_MENUS =
            List.of(
                    menu(
                            "HR_ATTENDANCE",
                            "考勤打卡",
                            "/attendance",
                            "GET /hr/attendance/records",
                            "POST /hr/attendance/clock-in"),
                    menu("HR_LEAVE_BALANCE", "休假管理", "/leave-balance", "GET /hr/leave-balances"),
                    menu(
                            "HR_OVERTIME",
                            "加班管理",
                            "/overtime",
                            "GET /hr/overtimes",
                            "POST /hr/overtimes"),
                    menu(
                            "HR_EMPLOYEE",
                            "员工台账管理",
                            "/employees",
                            "GET /hr/employees",
                            "GET /hr/employees/{employeeNo}"));

    record MenuSpec(String code, String name, String path, List<String> apis) {}

    public record SuperUser(String username, String realName, String phone, String password) {}

    private final JdbcTemplate db;
    private final SensitiveDataCodec codec;
    private final PasswordHasher passwords;
    private final PasswordPolicy policy;
    private final DataSignatureService signatures;
    private final AuditService audit;
    private final ObjectMapper json;
    private final String managementAppCode;

    public InitializationService(
            JdbcTemplate db,
            SensitiveDataCodec codec,
            PasswordHasher passwords,
            PasswordPolicy policy,
            DataSignatureService signatures,
            AuditService audit,
            ObjectMapper json,
            @Value("${iam.permission.management-app-code:IAM}") String managementAppCode) {
        this.db = db;
        this.codec = codec;
        this.passwords = passwords;
        this.policy = policy;
        this.signatures = signatures;
        this.audit = audit;
        this.json = json;
        this.managementAppCode = managementAppCode;
    }

    /** 全部步骤在同一事务中完成：任何一步失败，数据库保持空库。 */
    @Transactional
    public Map<String, Object> initialize(Path preparedData, SuperUser superUser)
            throws IOException {
        Integer existing = db.queryForObject("SELECT COUNT(*) FROM sys_user", Integer.class);
        if (existing == null || existing != 0)
            throw new IllegalStateException("只能在空库上初始化；已有账号，拒绝执行");
        JsonNode tables = json.readTree(Files.readString(preparedData)).path("tables");

        Map<String, Long> departments = importDepartments(tables.path("departments"));
        int employees = importEmployees(tables.path("employees"), departments);

        long iam = createApplication(managementAppCode, "统一权限管理系统", IAM_MENUS);
        long oa = createApplication("OA", "OA协同办公系统", OA_MENUS);
        long hr = createApplication("HR", "人力资源管理系统", HR_MENUS);

        List<Long> iamMenus = menuIds(iam);
        long superRole = createRole("SUPER_ADMIN", "超级管理员", 3, iamMenus);
        createRole("IAM_ADMIN", "一级管理员", 2, iamMenus);
        // 需求示例：环卫工人等一线员工权限相同（考勤打卡 + OA 请假），可整批授予此角色。
        createRole(
                "BASIC_EMPLOYEE",
                "一线员工基础权限",
                1,
                List.of(menuId(hr, "HR_ATTENDANCE"), menuId(oa, "OA_LEAVE")));

        long root = departments.get(rootName(tables.path("departments")));
        long superUserId = createSuperUser(superUser, root, superRole);

        signAll();
        audit.change(
                "SYSTEM_INITIALIZE",
                superUserId,
                "SYSTEM",
                0L,
                null,
                Map.of(
                        "reason",
                        "受控初始化",
                        "departments",
                        departments.size(),
                        "employees",
                        employees,
                        "applications",
                        List.of(managementAppCode, "OA", "HR"),
                        "superUserId",
                        superUserId));
        return Map.of(
                "departments",
                departments.size(),
                "employees",
                employees,
                "superUserId",
                superUserId,
                "superUsername",
                superUser.username());
    }

    /** 兜底恢复：重新启用超级用户的一级任命和超级角色，不绕过验签，并记录审计。 */
    @Transactional
    public void recover(String username) {
        Long userId =
                db.queryForObject(
                        "SELECT id FROM sys_user WHERE username=? AND source=3",
                        Long.class,
                        username);
        Long superRole =
                db.queryForObject(
                        "SELECT id FROM sys_role WHERE role_code='SUPER_ADMIN' AND role_type=3",
                        Long.class);
        signatures.verify(USER_IDENTITY, userId);
        signatures.verify(USER_GRANTS, userId);
        signatures.verify(ADMIN, userId);
        signatures.verify(ROLE_MENUS, superRole);
        long root =
                db.queryForObject(
                        "SELECT id FROM sys_dept WHERE parent_id=0 ORDER BY id LIMIT 1",
                        Long.class);
        db.update(
                "UPDATE sys_admin SET status=1,admin_level=1,dept_id=? WHERE user_id=?",
                root,
                userId);
        db.update("UPDATE sys_role SET status=1 WHERE id=?", superRole);
        db.update(
                "UPDATE sys_user_role_source SET status=1,revoked_by=NULL,revoked_at=NULL"
                        + " WHERE user_id=? AND role_id=? AND source_ref='INIT'",
                userId,
                superRole);
        db.update(
                "INSERT IGNORE INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, superRole);
        db.update("UPDATE sys_user SET grant_revision=grant_revision+1 WHERE id=?", userId);
        signatures.sign(ADMIN, userId);
        signatures.sign(ADMIN_DELEGABLE_ROLES, userId);
        signatures.sign(ROLE_MENUS, superRole);
        signatures.sign(USER_IDENTITY, userId);
        signatures.sign(USER_GRANTS, userId);
        audit.change(
                "SUPER_USER_RECOVER", userId, "USER", userId, null, Map.of("reason", "超级用户兜底恢复"));
    }

    // ---------- 1. 组织与人员 ----------

    /** 按 Excel 行号顺序建部门树；上级必须先出现。返回 部门名称 → ID。 */
    private Map<String, Long> importDepartments(JsonNode table) {
        Map<String, Long> ids = new HashMap<>();
        Map<Long, String> paths = new HashMap<>();
        int code = 1;
        for (JsonNode row : table.path("records")) {
            String name = row.path("部门名称").asText();
            String parentName = row.path("上级部门").asText();
            long parentId = parentName.isEmpty() ? 0 : required(ids, parentName);
            long id =
                    insert(
                            "INSERT INTO"
                                + " sys_dept(parent_id,dept_code,dept_name,dept_path,sort_order)"
                                + " VALUES(?,?,?,'/',?)",
                            parentId,
                            String.format("D%03d", code),
                            name,
                            code);
            String path = (parentId == 0 ? "/" : paths.get(parentId)) + id + "/";
            db.update("UPDATE sys_dept SET dept_path=? WHERE id=?", path, id);
            ids.put(name, id);
            paths.put(id, path);
            code++;
        }
        return ids;
    }

    /** 员工按 SG 工号导入为 HR 同步账号（source=1），密码未初始化，须本人用短信验证码首次设密。 登录名 = 姓名拼音首字母；重名追加工号后六位（详细设计第 10 节）。 */
    private int importEmployees(JsonNode table, Map<String, Long> departments) {
        // 未初始化的账号不能登录；这里只需一个无人知道的随机哈希占位，
        // 共用一份可避免 1 万次 scrypt（每次约 0.3 秒）。
        String random = (UUID.randomUUID() + "" + UUID.randomUUID()).replace("-", "");
        String unusableHash = passwords.hash(random);
        Set<String> usernames = new HashSet<>();
        int count = 0;
        for (JsonNode row : table.path("records")) {
            String name = row.path("姓名").asText().strip();
            String sgNo = row.path("用户名").asText().strip();
            String branch = row.path("分公司").asText();
            String dept = row.path("部门").asText();
            // 总公司员工直接用部门名；分公司员工的部门名为“分公司-部门”。
            String deptName =
                    departments.containsKey(branch + "-" + dept) ? branch + "-" + dept : dept;
            long deptId = required(departments, deptName);
            String phone = SensitiveDataCodec.normalizePhone(row.path("电话号码").asText());
            String username = HrLoginName.initials(name);
            if (!usernames.add(username)) {
                username = username + sgNo.substring(Math.max(0, sgNo.length() - 6));
                if (!usernames.add(username))
                    throw new IllegalStateException("登录名重复：第 " + row.path("sourceRow") + " 行");
            }
            db.update(
                    "INSERT INTO"
                        + " sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,"
                        + "employee_no,dept_id,source,employment_type,status,password_initialized)"
                        + " VALUES(?,?,?,?,?,?,?,1,1,1,0)",
                    username,
                    codec.encrypt(name),
                    codec.encrypt(phone),
                    codec.phoneHash(phone),
                    unusableHash,
                    sgNo,
                    deptId);
            count++;
        }
        return count;
    }

    // ---------- 2. 应用、菜单、API ----------

    private long createApplication(String code, String name, List<MenuSpec> menus) {
        long appId =
                insert("INSERT INTO sys_application(app_code,app_name) VALUES(?,?)", code, name);
        int sort = 1;
        for (MenuSpec spec : menus) {
            long menuId =
                    insert(
                            "INSERT INTO"
                                + " sys_menu(app_id,menu_code,menu_name,menu_type,path,sort_order)"
                                + " VALUES(?,?,?,2,?,?)",
                            appId,
                            spec.code(),
                            spec.name(),
                            spec.path(),
                            sort++);
            for (String api : spec.apis()) {
                String method = api.substring(0, api.indexOf(' '));
                String path =
                        RouteTemplateUtils.normalizeTemplate(api.substring(method.length() + 1));
                String apiCode = method + "_" + path.replaceAll("[^A-Za-z0-9]+", "_");
                long apiId =
                        insert(
                                "INSERT INTO"
                                    + " sys_api(app_id,api_code,api_name,api_path,canonical_path,http_method)"
                                    + " VALUES(?,?,?,?,?,?)",
                                appId,
                                apiCode,
                                spec.name() + " " + method,
                                path,
                                RouteTemplateUtils.canonicalTemplate(path),
                                method);
                db.update("INSERT INTO sys_menu_api(menu_id,api_id) VALUES(?,?)", menuId, apiId);
            }
        }
        return appId;
    }

    private List<Long> menuIds(long appId) {
        return db.queryForList(
                "SELECT id FROM sys_menu WHERE app_id=? ORDER BY id", Long.class, appId);
    }

    private long menuId(long appId, String code) {
        return db.queryForObject(
                "SELECT id FROM sys_menu WHERE app_id=? AND menu_code=?", Long.class, appId, code);
    }

    // ---------- 3. 角色与超级用户 ----------

    private long createRole(String code, String name, int type, List<Long> menus) {
        long roleId =
                insert(
                        "INSERT INTO sys_role(role_code,role_name,role_type) VALUES(?,?,?)",
                        code,
                        name,
                        type);
        for (long menu : menus)
            db.update("INSERT INTO sys_role_permission(role_id,menu_id) VALUES(?,?)", roleId, menu);
        return roleId;
    }

    /** 超级用户：受控初始化账号（source=3），密码由运维在初始化时提供，不内置默认密码。 */
    private long createSuperUser(SuperUser s, long rootDeptId, long superRole) {
        String phone = SensitiveDataCodec.normalizePhone(s.phone());
        var check =
                policy.validate(
                        s.password(),
                        new PasswordPolicy.IdentityContext(
                                s.username(), NamePinyin.variants(s.realName()).getFirst(), phone),
                        List.of());
        if (check != PasswordPolicy.ValidationResult.VALID)
            throw new IllegalArgumentException("超级用户密码不符合密码策略：" + check);
        String hash = passwords.hash(s.password());
        long userId =
                insert(
                        "INSERT INTO"
                            + " sys_user(username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status,password_initialized)"
                            + " VALUES(?,?,?,?,?,?,3,1,1)",
                        s.username(),
                        codec.encrypt(s.realName()),
                        codec.encrypt(phone),
                        codec.phoneHash(phone),
                        hash,
                        rootDeptId);
        db.update(
                "INSERT INTO sys_password_history(user_id,password_hash) VALUES(?,?)",
                userId,
                hash);
        db.update("INSERT INTO sys_user_role(user_id,role_id) VALUES(?,?)", userId, superRole);
        db.update(
                "INSERT INTO sys_user_role_source(user_id,role_id,source_type,source_ref)"
                        + " VALUES(?,?,'MANUAL','INIT')",
                userId,
                superRole);
        db.update(
                "INSERT INTO sys_admin(user_id,admin_level,dept_id) VALUES(?,1,?)",
                userId,
                rootDeptId);
        return userId;
    }

    // ---------- 4. 签名 ----------

    /** 每类事实各签一次；签名内容由 DataSignatureService 从数据库读取完整记录生成。 */
    private void signAll() {
        signEach(DEPARTMENT, "SELECT id FROM sys_dept");
        signatures.sign(ORGANIZATION, 0);
        signEach(USER_IDENTITY, "SELECT id FROM sys_user");
        signEach(USER_GRANTS, "SELECT id FROM sys_user");
        signEach(RESOURCE_APPLICATION, "SELECT id FROM sys_application");
        signEach(RESOURCE_MENU, "SELECT id FROM sys_menu");
        signEach(MENU_APIS, "SELECT id FROM sys_menu");
        signEach(RESOURCE_API, "SELECT id FROM sys_api");
        signEach(ROLE_MENUS, "SELECT id FROM sys_role");
        signEach(ADMIN, "SELECT user_id FROM sys_admin");
        signEach(ADMIN_DELEGABLE_ROLES, "SELECT user_id FROM sys_admin");
    }

    private void signEach(com.whatname.iam.service.permission.SignedDataType type, String sql) {
        for (long id : db.queryForList(sql, Long.class)) signatures.sign(type, id);
    }

    // ---------- 工具 ----------

    private long insert(String sql, Object... args) {
        var keys = new GeneratedKeyHolder();
        db.update(
                connection -> {
                    var statement =
                            connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
                    for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
                    return statement;
                },
                keys);
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    private static long required(Map<String, Long> ids, String name) {
        Long id = ids.get(name);
        if (id == null) throw new IllegalStateException("找不到部门：" + name);
        return id;
    }

    private static String rootName(JsonNode table) {
        for (JsonNode row : table.path("records"))
            if (row.path("上级部门").asText().isEmpty()) return row.path("部门名称").asText();
        throw new IllegalStateException("部门表没有根部门");
    }

    private static MenuSpec menu(String code, String name, String path, String... apis) {
        return new MenuSpec(code, name, path, List.of(apis));
    }
}
