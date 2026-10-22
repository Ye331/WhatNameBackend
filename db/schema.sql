-- ============================================================
-- 统一权限管理系统 数据库建表脚本
-- 数据库：MySQL 8.0.16 及以上（已在Docker MySQL 8.0.46执行建表验证）
-- v3.1：23张表（13张业务表 + 10张支撑表），Controller-Service-Mapper三层，当前不使用Redis。
-- 权限直接读MySQL，移除权限发布版本/拒绝栅栏表；补齐会话版本、事件、批量任务和请求nonce。
-- 仅首次空库建表；不是旧库迁移脚本，不删除现有数据。
-- 现有表时 CREATE TABLE 将报错，必须停止，不要使用 mysql --force。
-- 字符集：utf8mb4
-- 排序规则：utf8mb4_general_ci
-- ============================================================

CREATE DATABASE IF NOT EXISTS `unified_permission`
  DEFAULT CHARACTER SET utf8mb4
  DEFAULT COLLATE utf8mb4_general_ci;

USE `unified_permission`;
SET NAMES utf8mb4;
SET time_zone = '+08:00';

-- ============================================================
-- 1. 应用表
-- ============================================================
CREATE TABLE `sys_application` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '应用ID',
  `app_code` VARCHAR(64) NOT NULL COMMENT '应用编码',
  `app_name` VARCHAR(128) NOT NULL COMMENT '应用名称',
  `app_desc` VARCHAR(512) DEFAULT NULL COMMENT '应用描述',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_code` (`app_code`),
  CONSTRAINT `ck_app_status` CHECK (`status` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用表';

-- ============================================================
-- 2. 菜单表
-- ============================================================
CREATE TABLE `sys_menu` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '菜单ID',
  `app_id` BIGINT NOT NULL COMMENT '所属应用ID',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '父菜单ID，0为顶级',
  `menu_code` VARCHAR(64) NOT NULL COMMENT '菜单编码',
  `menu_name` VARCHAR(128) NOT NULL COMMENT '菜单名称',
  `menu_type` TINYINT NOT NULL DEFAULT 2 COMMENT '类型：1目录（不可授权） 2菜单（权限单元）',
  `path` VARCHAR(256) DEFAULT NULL COMMENT '前端路由路径',
  `icon` VARCHAR(64) DEFAULT NULL COMMENT '图标',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `api_revision` INT NOT NULL DEFAULT 1 COMMENT '菜单-API映射修订号，全量替换并发控制（RE10）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_app_menu_code` (`app_id`, `menu_code`),
  KEY `idx_app_id` (`app_id`),
  KEY `idx_parent_id` (`parent_id`),
  CONSTRAINT `ck_menu_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_menu_type` CHECK (`menu_type` IN (1,2)),
  CONSTRAINT `ck_menu_parent` CHECK (`parent_id` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单表';

-- ============================================================
-- 3. API表
-- ============================================================
CREATE TABLE `sys_api` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT 'API ID',
  `app_id` BIGINT NOT NULL COMMENT '所属应用ID',
  `api_code` VARCHAR(128) NOT NULL COMMENT 'API编码',
  `api_name` VARCHAR(128) NOT NULL COMMENT 'API名称',
  `api_path` VARCHAR(256) COLLATE utf8mb4_bin NOT NULL COMMENT 'API路径',
  `canonical_path` VARCHAR(1024),
  `canonical_hash` VARBINARY(16) GENERATED ALWAYS AS (UNHEX(MD5(COALESCE(canonical_path, '')))) STORED,
  `http_method` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '请求方法',
  `api_desc` VARCHAR(512) DEFAULT NULL COMMENT '描述',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `ux_sys_api_app_method_canonical` (`app_id`, `http_method`, `canonical_hash`),
  UNIQUE KEY `uk_app_api` (`app_id`, `api_path`, `http_method`),
  KEY `idx_app_id` (`app_id`),
  CONSTRAINT `ck_api_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_api_method` CHECK (`http_method` IN ('GET','POST','PUT','PATCH','DELETE','HEAD','OPTIONS'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='API表';

-- ============================================================
-- 4. 菜单-API关联表
-- ============================================================
CREATE TABLE `sys_menu_api` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `menu_id` BIGINT NOT NULL COMMENT '菜单ID',
  `api_id` BIGINT NOT NULL COMMENT 'API ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_menu_api` (`menu_id`, `api_id`),
  KEY `idx_api_id` (`api_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='菜单-API关联表';

-- ============================================================
-- 5. 用户表
-- ============================================================
CREATE TABLE `sys_user` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '用户ID',
  `username` VARCHAR(64) NOT NULL COMMENT '用户名',
  `real_name_ciphertext` VARCHAR(512) NOT NULL COMMENT '姓名AES-GCM密文，界面授权解密展示',
  `phone` VARCHAR(256) NOT NULL COMMENT '规范化手机号AES-GCM密文，界面脱敏',
  `phone_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化手机号HMAC-SHA256查询索引',
  `password_hash` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '带盐scrypt密码哈希编码，不可逆',
  `employee_no` VARCHAR(64) DEFAULT NULL COMMENT '工号',
  `dept_id` BIGINT NOT NULL COMMENT '所属部门ID',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1可用 2暂停 3离职',
  `source` TINYINT NOT NULL DEFAULT 1 COMMENT '账号来源（唯一甄别字段）：1 HR同步 2管理员手动创建 3受控初始化',
  `employment_type` TINYINT DEFAULT NULL COMMENT '人员类型：1正式 2外包；仅source=1的HR同步账号有值',
  `password_initialized` TINYINT NOT NULL DEFAULT 0 COMMENT '密码初始化标识：0未设密不能登录 1已设密',
  `session_version` BIGINT NOT NULL DEFAULT 1 COMMENT '会话版本；JWT携带sv，重置/暂停/离职时递增',
  `grant_revision` BIGINT NOT NULL DEFAULT 1 COMMENT '用户角色来源集合修订号，接口以v加数字表示',
  `hr_version` BIGINT NOT NULL DEFAULT 0 COMMENT '最后成功应用的HR人员事件版本',
  `pwd_failed_count` INT NOT NULL DEFAULT 0 COMMENT '连续密码验证失败次数（9.1锁定策略：5次锁15分钟）',
  `pwd_locked_until` DATETIME DEFAULT NULL COMMENT '密码锁定截止时间；NULL未锁定',
  `created_by` BIGINT DEFAULT NULL COMMENT '手动创建（source=2）账号的创建人用户ID',
  `temp_reason` VARCHAR(512) DEFAULT NULL COMMENT '手动创建（source=2）账号的用途',
  `last_login_at` DATETIME DEFAULT NULL COMMENT '最后登录时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_username` (`username`),
  UNIQUE KEY `uk_employee_no` (`employee_no`),
  UNIQUE KEY `uk_phone_hash` (`phone_hash`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_status` (`status`),
  CONSTRAINT `ck_user_status` CHECK (`status` IN (1,2,3)),
  CONSTRAINT `ck_user_origin` CHECK ((`source`=1 AND `employee_no` IS NOT NULL AND CHAR_LENGTH(TRIM(`employee_no`))>0 AND `employment_type` IS NOT NULL AND `employment_type` IN (1,2) AND `created_by` IS NULL AND `temp_reason` IS NULL) OR (`source`=2 AND `employee_no` IS NULL AND `employment_type` IS NULL AND `created_by` IS NOT NULL AND `temp_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`temp_reason`))>0) OR (`source`=3 AND `employee_no` IS NULL AND `employment_type` IS NULL AND `created_by` IS NULL AND `temp_reason` IS NULL)),
  CONSTRAINT `ck_user_versions` CHECK (`session_version`>0 AND `grant_revision`>0 AND `hr_version`>=0),
  CONSTRAINT `ck_user_pwdinit` CHECK (`password_initialized` IN (0,1)),
  CONSTRAINT `ck_password_nonempty` CHECK (CHAR_LENGTH(`password_hash`) > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';

-- ============================================================
-- 6. 部门表
-- ============================================================
CREATE TABLE `sys_dept` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '部门ID',
  `parent_id` BIGINT NOT NULL DEFAULT 0 COMMENT '上级部门ID',
  `dept_code` VARCHAR(64) NOT NULL COMMENT '部门编码',
  `dept_name` VARCHAR(128) NOT NULL COMMENT '部门名称',
  `dept_path` VARCHAR(512) NOT NULL COMMENT '部门路径',
  `manager_id` BIGINT DEFAULT NULL COMMENT '部门经理用户ID',
  `sort_order` INT NOT NULL DEFAULT 0 COMMENT '排序',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1正常 0冻结',
  `merged_into_dept_id` BIGINT DEFAULT NULL COMMENT '已合并去向，NULL未合并；status独立表示冻结',
  `hr_version` BIGINT NOT NULL DEFAULT 0 COMMENT '最后成功应用的HR部门事件版本',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_dept_code` (`dept_code`),
  KEY `idx_parent_id` (`parent_id`),
  KEY `idx_dept_path` (`dept_path`),
  CONSTRAINT `ck_dept_hrversion` CHECK (`hr_version`>=0),
  CONSTRAINT `ck_dept_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_dept_parent` CHECK (`parent_id` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='部门表';

-- ============================================================
-- 7. 角色表
-- ============================================================
CREATE TABLE `sys_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '角色ID',
  `role_code` VARCHAR(64) NOT NULL COMMENT '角色编码',
  `role_name` VARCHAR(128) NOT NULL COMMENT '角色名称',
  `role_desc` VARCHAR(512) DEFAULT NULL COMMENT '角色描述',
  `role_type` TINYINT NOT NULL DEFAULT 1 COMMENT '类型：1业务 2管理 3超级',
  `menu_revision` INT NOT NULL DEFAULT 1 COMMENT '角色-菜单集合修订号，全量替换并发控制（RL06）',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1启用 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_code` (`role_code`),
  KEY `idx_role_type` (`role_type`),
  CONSTRAINT `ck_role_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_role_type` CHECK (`role_type` IN (1,2,3))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色表';

-- ============================================================
-- 8. 用户-角色关联表
-- ============================================================
CREATE TABLE `sys_user_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  `granted_by` BIGINT DEFAULT NULL COMMENT '授权人用户ID',
  `granted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色关联表';

-- ============================================================
-- 9. 角色-权限关联表
-- ============================================================
CREATE TABLE `sys_role_permission` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  `menu_id` BIGINT NOT NULL COMMENT '菜单ID',
  `granted_by` BIGINT DEFAULT NULL COMMENT '授权人用户ID',
  `granted_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_role_menu` (`role_id`, `menu_id`),
  KEY `idx_menu_id` (`menu_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='角色-权限关联表';

-- ============================================================
-- 10. 管理员表
-- ============================================================
CREATE TABLE `sys_admin` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `admin_level` SMALLINT UNSIGNED NOT NULL COMMENT '1总公司；2及以上逐级降低，非角色继承',
  `dept_id` BIGINT NOT NULL COMMENT '管理范围部门ID',
  `appointed_by` BIGINT DEFAULT NULL COMMENT '任命人用户ID',
  `appointed_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '任命时间',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1有效 0撤销',
  `delegable_revision` INT NOT NULL DEFAULT 1 COMMENT '可授权角色清单修订号，全量替换并发控制（AD06）',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_id` (`user_id`),
  KEY `idx_dept_id` (`dept_id`),
  KEY `idx_admin_level` (`admin_level`),
  CONSTRAINT `ck_admin_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_admin_level` CHECK (`admin_level` >= 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员表';

-- ============================================================
-- 11. 操作日志表
-- ============================================================
CREATE TABLE `sys_operation_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '操作人用户ID',
  `username` VARCHAR(64) DEFAULT NULL COMMENT '操作人用户名',
  `request_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '全链路请求标识，同请求可有多个事件',
  `app_id` BIGINT DEFAULT NULL COMMENT '应用历史ID，不设外键以保留证据',
  `api_id` BIGINT DEFAULT NULL COMMENT 'API历史ID，路径和方法另存快照',
  `auth_at` DATETIME(3) DEFAULT NULL COMMENT '鉴权发生时间',
  `auth_decision` TINYINT DEFAULT NULL COMMENT '1允许 0拒绝 NULL不适用',
  `auth_reason` VARCHAR(256) DEFAULT NULL COMMENT '鉴权依据或拒绝原因',
  `evidence` JSON DEFAULT NULL COMMENT '不可变授权快照或变更前后证据，不能只存当前ID',
  `source_system` VARCHAR(64) DEFAULT NULL COMMENT 'ADMIN、HR、OA或接入应用编码',
  `external_event_id` VARCHAR(128) DEFAULT NULL COMMENT 'HR/OA请求标识，用于追溯与幂等校验',
  `operation_type` VARCHAR(64) NOT NULL COMMENT '操作类型',
  `operation_desc` VARCHAR(1024) DEFAULT NULL COMMENT '操作描述',
  `target_type` VARCHAR(64) DEFAULT NULL COMMENT '目标类型',
  `target_id` BIGINT DEFAULT NULL COMMENT '目标ID',
  `request_ip` VARCHAR(64) DEFAULT NULL COMMENT '请求IP',
  `request_method` VARCHAR(16) DEFAULT NULL COMMENT '请求方法',
  `request_url` VARCHAR(512) DEFAULT NULL COMMENT '请求URL',
  `request_params` TEXT DEFAULT NULL COMMENT '请求参数',
  `response_result` TEXT DEFAULT NULL COMMENT '响应结果',
  `status` TINYINT DEFAULT NULL COMMENT '业务结果：1成功 0失败 NULL未收到结果或不适用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '操作时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_operation_type` (`operation_type`),
  KEY `idx_request_id` (`request_id`),
  KEY `idx_external_event` (`source_system`, `external_event_id`),
  CONSTRAINT `ck_log_status` CHECK (`status` IS NULL OR `status` IN (0,1)),
  CONSTRAINT `ck_auth_decision` CHECK (`auth_decision` IS NULL OR `auth_decision` IN (0,1)),
  CONSTRAINT `ck_log_request_id` CHECK (CHAR_LENGTH(TRIM(`request_id`))>0),
  CONSTRAINT `ck_log_evidence_object` CHECK (`evidence` IS NULL OR JSON_TYPE(`evidence`)='OBJECT'),
  CONSTRAINT `ck_auth_evidence` CHECK (`operation_type` <> 'AUTH_CHECK' OR (`auth_at` IS NOT NULL AND `auth_decision` IS NOT NULL AND `auth_reason` IS NOT NULL AND CHAR_LENGTH(TRIM(`auth_reason`))>0 AND `request_method` IS NOT NULL AND `request_url` IS NOT NULL AND `evidence` IS NOT NULL AND `status` IS NULL)),
  CONSTRAINT `ck_auth_allowed_path` CHECK (`operation_type` <> 'AUTH_CHECK' OR `auth_decision`=0 OR (`user_id` IS NOT NULL AND `app_id` IS NOT NULL AND `api_id` IS NOT NULL AND JSON_CONTAINS_PATH(`evidence`,'all','$.user_role','$.role_permission','$.menu_api','$.state','$.signatures')=1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='操作日志表';

-- ============================================================
-- 12. 登录日志表
-- ============================================================
CREATE TABLE `sys_login_log` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '日志ID',
  `user_id` BIGINT DEFAULT NULL COMMENT '用户ID',
  `username` VARCHAR(64) DEFAULT NULL COMMENT '用户名',
  `login_type` TINYINT DEFAULT NULL COMMENT '登录方式：1账号密码 2手机号密码',
  `login_ip` VARCHAR(64) DEFAULT NULL COMMENT '登录IP',
  `login_device` VARCHAR(256) DEFAULT NULL COMMENT '登录设备',
  `login_status` TINYINT DEFAULT NULL COMMENT '状态：1成功 0失败',
  `fail_reason` VARCHAR(256) DEFAULT NULL COMMENT '失败原因',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '登录时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_id` (`user_id`),
  KEY `idx_created_at` (`created_at`),
  KEY `idx_login_status` (`login_status`),
  CONSTRAINT `ck_login_type` CHECK (`login_type` IS NULL OR `login_type` IN (1,2)),
  CONSTRAINT `ck_login_status` CHECK (`login_status` IS NULL OR `login_status` IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表';

-- ============================================================
-- 13. 数据签名表
-- ============================================================
CREATE TABLE `sys_data_signature` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `data_type` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '签名对象类型',
  `data_id` BIGINT NOT NULL COMMENT '数据ID',
  `signature` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Ed25519签名Base64，私钥不入库',
  `sign_version` INT NOT NULL DEFAULT 1 COMMENT '签名序列化格式版本，非密钥标识',
  `key_id` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL DEFAULT 'k1' COMMENT '签名密钥标识，支持密钥轮换',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_data_type_id` (`data_type`, `data_id`),
  CONSTRAINT `ck_sign_version` CHECK (`sign_version`=1),
  CONSTRAINT `ck_sign_key` CHECK (CHAR_LENGTH(TRIM(`key_id`))>0),
  CONSTRAINT `ck_sign_nonempty` CHECK (CHAR_LENGTH(`signature`)=88)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='数据签名表';

-- ============================================================
-- 14. 用户-角色授权依据表
-- ============================================================
CREATE TABLE `sys_user_role_source` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '授权依据ID',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `role_id` BIGINT NOT NULL COMMENT '角色ID',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '来源类型：MANUAL/DEPT_POSITION/OA_APPROVAL',
  `source_ref` VARCHAR(128) NOT NULL COMMENT '来源业务标识：岗位号、OA审批单号或工单号',
  `source_dept_id` BIGINT DEFAULT NULL COMMENT '来源部门ID；岗位类授权记录，调岗回收依据',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1有效 0已回收',
  `granted_by` BIGINT DEFAULT NULL COMMENT '授权操作人用户ID；OA/HR事件来源为空',
  `revoked_by` BIGINT DEFAULT NULL COMMENT '回收操作人用户ID',
  `revoked_at` DATETIME DEFAULT NULL COMMENT '回收时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '授权时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_role_ref` (`user_id`, `role_id`, `source_type`, `source_ref`),
  KEY `idx_user_role` (`user_id`, `role_id`),
  KEY `idx_role_id` (`role_id`),
  KEY `idx_source_dept` (`source_dept_id`),
  CONSTRAINT `ck_urs_type` CHECK (`source_type` IN ('MANUAL','DEPT_POSITION','OA_APPROVAL')),
  CONSTRAINT `ck_urs_dept` CHECK (`source_type`<>'DEPT_POSITION' OR `source_dept_id` IS NOT NULL),
  CONSTRAINT `ck_urs_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_urs_revoke` CHECK ((`status`=1 AND `revoked_at` IS NULL AND `revoked_by` IS NULL) OR (`status`=0 AND `revoked_at` IS NOT NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户-角色授权依据表（同一角色多来源并存，按依据精确回收）';

-- ============================================================
-- 15. 管理员可授权角色清单表
-- ============================================================
CREATE TABLE `sys_admin_delegable_role` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `admin_user_id` BIGINT NOT NULL COMMENT '管理员用户ID',
  `role_id` BIGINT NOT NULL COMMENT '可授予角色ID；超级角色禁止进入清单',
  `configured_by` BIGINT DEFAULT NULL COMMENT '配置操作人用户ID',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '配置时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_admin_role` (`admin_user_id`, `role_id`),
  KEY `idx_role_id` (`role_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='管理员可授权角色清单表（上级配置，全量替换）';

-- ============================================================
-- 16. HR/OA外部事件表
-- ============================================================
CREATE TABLE `sys_external_event` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `source_system` VARCHAR(64) NOT NULL COMMENT '来源系统：HR / OA',
  `event_id` VARCHAR(128) NOT NULL COMMENT '外部唯一事件ID',
  `event_type` VARCHAR(64) NOT NULL COMMENT '事件类型：入职/资料/调岗/离职/部门/合并/OA决定',
  `event_version` BIGINT DEFAULT NULL COMMENT 'HR事件必须有正版本；OA无顺序版本时为空',
  `payload_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '事件载荷SHA-256摘要，同号重发内容比对',
  `target_type` VARCHAR(16) DEFAULT NULL COMMENT '目标类型：USER / DEPT',
  `target_ref` VARCHAR(64) DEFAULT NULL COMMENT '目标外部标识：SG号或部门编码',
  `processing_status` VARCHAR(20) NOT NULL DEFAULT 'PROCESSING' COMMENT '处理阶段：APPLIED/PROCESSING/PENDING_RECOVERY/REJECTED',
  `processing_stage` VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' COMMENT 'RECEIVED/APPLYING/COMPLETED，业务事实在单个事务应用',
  `payload_ciphertext` MEDIUMTEXT NOT NULL COMMENT '规范化输入加密保存，恢复重放使用，禁止保存明文手机号',
  `result_data` JSON DEFAULT NULL COMMENT '可重放的业务结果；脱敏，不含密码、验证码或完整JWT',
  `result_summary` VARCHAR(512) DEFAULT NULL COMMENT '执行结果或失败原因摘要',
  `occurred_at` DATETIME DEFAULT NULL COMMENT '业务事件时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_source_event` (`source_system`, `event_id`),
  KEY `idx_target` (`target_type`, `target_ref`),
  KEY `idx_status` (`processing_status`),
  CONSTRAINT `ck_ee_status` CHECK (`processing_status` IN ('APPLIED','PROCESSING','PENDING_RECOVERY','REJECTED')),
  CONSTRAINT `ck_ee_version` CHECK ((`source_system`='HR' AND `event_version` IS NOT NULL AND `event_version`>0) OR (`source_system`='OA' AND (`event_version` IS NULL OR `event_version`>0))),
  CONSTRAINT `ck_ee_stage` CHECK (`processing_stage` IN ('RECEIVED','APPLYING','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='HR/OA外部事件幂等与阶段记录表';

-- ============================================================
-- 19. 短信验证码表
-- ============================================================
CREATE TABLE `sys_sms_code` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `phone_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '手机号HMAC-SHA256索引',
  `purpose` VARCHAR(32) NOT NULL COMMENT '用途：SET_PASSWORD首次设密 / RESET_PASSWORD重置；不设LOGIN',
  `code_hash` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '验证码HMAC-SHA256，不保存明文',
  `expires_at` DATETIME NOT NULL COMMENT '过期时间',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '验证尝试次数',
  `consumed_at` DATETIME DEFAULT NULL COMMENT '一次性核销时间；NULL未使用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '发送时间',
  PRIMARY KEY (`id`),
  KEY `idx_phone_purpose` (`phone_hash`, `purpose`),
  CONSTRAINT `ck_sms_purpose` CHECK (`purpose` IN ('SET_PASSWORD','RESET_PASSWORD')),
  CONSTRAINT `ck_sms_attempts` CHECK (`attempts` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='短信验证码表（仅身份验证用途，非登录方式）';

-- ============================================================
-- 20. 会话失效表
-- ============================================================
CREATE TABLE `sys_session_invalidation` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `jti` VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '注销的指定JWT标识；全部旧会话失效使用sys_user.session_version',
  `reason` VARCHAR(32) NOT NULL COMMENT '指定令牌失效原因：LOGOUT',
  `expires_at` DATETIME NOT NULL COMMENT '覆盖JWT的原过期时间，过期后可清理',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '失效时间',
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  UNIQUE KEY `uk_jti` (`jti`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='单令牌注销记录表；密码重置/暂停/离职使用账号会话版本';

-- ============================================================
-- 21. 密码历史表
-- ============================================================
CREATE TABLE `sys_password_history` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `user_id` BIGINT NOT NULL COMMENT '用户ID',
  `password_hash` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '历史密码scrypt哈希',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '使用时间',
  PRIMARY KEY (`id`),
  KEY `idx_user_time` (`user_id`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='密码历史表（新密码不得与最近5次相同，服务负责裁剪）';

-- ============================================================
-- 22. 可信服务身份表
-- ============================================================
CREATE TABLE `sys_service_identity` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键',
  `app_id` BIGINT DEFAULT NULL COMMENT '绑定应用ID；HR/OA内部服务为空',
  `client_id` VARCHAR(64) NOT NULL COMMENT '服务客户端标识，对应X-Client-Id',
  `credential_hash` VARCHAR(255) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Ed25519公钥SHA-256指纹；实际验签使用public_key',
  `public_key` VARCHAR(256) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Ed25519公钥Base64；私钥不入库',
  `key_id` VARCHAR(64) NOT NULL COMMENT '服务请求签名密钥标识',
  `allowed_role_ids` JSON DEFAULT NULL COMMENT 'OA允许的角色ID字符串数组；不允许超级角色',
  `allowed_dept_ids` JSON DEFAULT NULL COMMENT 'OA允许的部门根ID字符串数组，按当前组织子树检查',
  `allowed_operations` JSON DEFAULT NULL COMMENT 'OA允许的GRANT/REVOKE操作数组',
  `allowed_scope` VARCHAR(64) NOT NULL COMMENT '受控操作范围：HR_SYNC/OA_DECISION/API_AUTH',
  `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态：1有效 0停用',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_id` (`client_id`),
  KEY `idx_app_id` (`app_id`),
  CONSTRAINT `ck_sid_status` CHECK (`status` IN (0,1)),
  CONSTRAINT `ck_sid_scope` CHECK (`allowed_scope` IN ('HR_SYNC','OA_DECISION','API_AUTH')),
  CONSTRAINT `ck_sid_binding` CHECK ((`allowed_scope`='API_AUTH' AND `app_id` IS NOT NULL) OR (`allowed_scope` IN ('HR_SYNC','OA_DECISION') AND `app_id` IS NULL)),
  CONSTRAINT `ck_sid_oa` CHECK (`allowed_scope`<>'OA_DECISION' OR (`allowed_role_ids` IS NOT NULL AND JSON_TYPE(`allowed_role_ids`)='ARRAY' AND `allowed_dept_ids` IS NOT NULL AND JSON_TYPE(`allowed_dept_ids`)='ARRAY' AND `allowed_operations` IS NOT NULL AND JSON_TYPE(`allowed_operations`)='ARRAY'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可信服务身份表（服务凭据绑定应用与受控操作范围）';

-- ============================================================
-- 批量授权任务、逐项结果及服务请求防重放
-- ============================================================
CREATE TABLE `sys_grant_batch` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '批量任务ID，JSON表示十进制字符串',
  `actor_user_id` BIGINT NOT NULL COMMENT '任务发起人；每项执行时复核当前权限',
  `idempotency_key` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '发起人范围内的幂等键',
  `payload_digest` CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '完整规范化请求摘要，同键不同内容冲突',
  `operation` VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'GRANT或REVOKE',
  `role_id` BIGINT NOT NULL COMMENT '本批次目标角色',
  `reason` VARCHAR(512) NOT NULL COMMENT '授权或回收原因',
  `processing_status` VARCHAR(24) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/COMPLETED/PARTIAL_SUCCESS/FAILED',
  `total_count` INT NOT NULL COMMENT '去重后的目标总数，计数从逐项结果汇总',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_idempotency` (`actor_user_id`,`idempotency_key`),
  KEY `idx_batch_status` (`processing_status`),
  CONSTRAINT `ck_batch_operation` CHECK (`operation` IN ('GRANT','REVOKE')),
  CONSTRAINT `ck_batch_status` CHECK (`processing_status` IN ('PENDING','PROCESSING','COMPLETED','PARTIAL_SUCCESS','FAILED')),
  CONSTRAINT `ck_batch_count` CHECK (`total_count`>0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量授权任务表（数据库工作线程，无消息队列）';

CREATE TABLE `sys_grant_batch_item` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '任务项ID',
  `batch_id` BIGINT NOT NULL COMMENT '任务ID',
  `user_id` BIGINT NOT NULL COMMENT '目标用户ID；不存在也保留失败结果，不设用户外键',
  `source_type` VARCHAR(32) CHARACTER SET ascii COLLATE ascii_bin DEFAULT NULL COMMENT 'GRANT来源：MANUAL或DEPT_POSITION',
  `source_ref` VARCHAR(128) DEFAULT NULL COMMENT 'GRANT稳定来源标识',
  `source_dept_id` BIGINT DEFAULT NULL COMMENT '岗位授权来源部门',
  `grant_source_id` BIGINT DEFAULT NULL COMMENT 'REVOKE输入来源ID；GRANT成功后记录生成来源ID',
  `processing_status` VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/APPLIED/FAILED',
  `result_code` VARCHAR(64) DEFAULT NULL COMMENT '结果业务码',
  `result_data` JSON DEFAULT NULL COMMENT '逐项结果及修订号，不能含敏感凭据',
  `attempts` INT NOT NULL DEFAULT 0 COMMENT '处理尝试次数',
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_batch_user` (`batch_id`,`user_id`),
  KEY `idx_item_status` (`batch_id`,`processing_status`),
  CONSTRAINT `ck_item_status` CHECK (`processing_status` IN ('PENDING','APPLIED','FAILED')),
  CONSTRAINT `ck_item_attempts` CHECK (`attempts`>=0),
  CONSTRAINT `ck_item_source` CHECK (`source_type` IS NULL OR `source_type` IN ('MANUAL','DEPT_POSITION')),
  CONSTRAINT `ck_item_dept` CHECK (`source_type` IS NULL OR `source_type`<>'DEPT_POSITION' OR `source_dept_id` IS NOT NULL)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='批量授权逐项结果表（项目业务变更与APPLIED状态同事务提交）';

CREATE TABLE `sys_service_nonce` (
  `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '请求防重放记录ID',
  `client_id` VARCHAR(64) NOT NULL COMMENT '已验签的服务客户端标识',
  `nonce` VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '服务请求随机值；唯一插入防止重放',
  `expires_at` DATETIME NOT NULL COMMENT '防重放保留截止时间',
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '接收时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_client_nonce` (`client_id`,`nonce`),
  KEY `idx_nonce_expiry` (`expires_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务请求nonce表（业务重试必须使用新nonce和相同事件ID）';

-- ============================================================
-- 核心外键约束（RESTRICT，不级联删除用户或历史日志）
-- ============================================================
ALTER TABLE `sys_menu` ADD CONSTRAINT `fk_menu_app` FOREIGN KEY (`app_id`) REFERENCES `sys_application`(`id`);
ALTER TABLE `sys_api` ADD CONSTRAINT `fk_api_app` FOREIGN KEY (`app_id`) REFERENCES `sys_application`(`id`);
ALTER TABLE `sys_menu_api` ADD CONSTRAINT `fk_ma_menu` FOREIGN KEY (`menu_id`) REFERENCES `sys_menu`(`id`);
ALTER TABLE `sys_menu_api` ADD CONSTRAINT `fk_ma_api` FOREIGN KEY (`api_id`) REFERENCES `sys_api`(`id`);
ALTER TABLE `sys_user` ADD CONSTRAINT `fk_user_dept` FOREIGN KEY (`dept_id`) REFERENCES `sys_dept`(`id`);
ALTER TABLE `sys_user_role` ADD CONSTRAINT `fk_ur_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_user_role` ADD CONSTRAINT `fk_ur_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role`(`id`);
ALTER TABLE `sys_role_permission` ADD CONSTRAINT `fk_rp_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role`(`id`);
ALTER TABLE `sys_role_permission` ADD CONSTRAINT `fk_rp_menu` FOREIGN KEY (`menu_id`) REFERENCES `sys_menu`(`id`);
ALTER TABLE `sys_admin` ADD CONSTRAINT `fk_admin_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_admin` ADD CONSTRAINT `fk_admin_dept` FOREIGN KEY (`dept_id`) REFERENCES `sys_dept`(`id`);

ALTER TABLE `sys_dept` ADD CONSTRAINT `fk_dept_manager` FOREIGN KEY (`manager_id`) REFERENCES `sys_user`(`id`);

ALTER TABLE `sys_user_role_source` ADD CONSTRAINT `fk_urs_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_user_role_source` ADD CONSTRAINT `fk_urs_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role`(`id`);
ALTER TABLE `sys_admin_delegable_role` ADD CONSTRAINT `fk_adr_user` FOREIGN KEY (`admin_user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_admin_delegable_role` ADD CONSTRAINT `fk_adr_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role`(`id`);
ALTER TABLE `sys_session_invalidation` ADD CONSTRAINT `fk_sinv_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_password_history` ADD CONSTRAINT `fk_ph_user` FOREIGN KEY (`user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_service_identity` ADD CONSTRAINT `fk_sid_app` FOREIGN KEY (`app_id`) REFERENCES `sys_application`(`id`);

ALTER TABLE `sys_user_role_source` ADD CONSTRAINT `fk_urs_dept` FOREIGN KEY (`source_dept_id`) REFERENCES `sys_dept`(`id`);
ALTER TABLE `sys_grant_batch` ADD CONSTRAINT `fk_batch_actor` FOREIGN KEY (`actor_user_id`) REFERENCES `sys_user`(`id`);
ALTER TABLE `sys_grant_batch` ADD CONSTRAINT `fk_batch_role` FOREIGN KEY (`role_id`) REFERENCES `sys_role`(`id`);
ALTER TABLE `sys_grant_batch_item` ADD CONSTRAINT `fk_item_batch` FOREIGN KEY (`batch_id`) REFERENCES `sys_grant_batch`(`id`);

-- 不可物理删除账号；历史日志只追加。后续业务结果另写事件，用request_id关联。
CREATE TRIGGER `tr_user_no_delete` BEFORE DELETE ON `sys_user` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Accounts must be retained; change status instead';
CREATE TRIGGER `tr_operation_no_delete` BEFORE DELETE ON `sys_operation_log` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Audit logs are permanent';
CREATE TRIGGER `tr_operation_no_update` BEFORE UPDATE ON `sys_operation_log` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Audit logs are append-only';
CREATE TRIGGER `tr_login_no_delete` BEFORE DELETE ON `sys_login_log` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Login logs are permanent';
CREATE TRIGGER `tr_login_no_update` BEFORE UPDATE ON `sys_login_log` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Login logs are append-only';

CREATE TRIGGER `tr_urs_no_delete` BEFORE DELETE ON `sys_user_role_source` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Grant source history is permanent';
CREATE TRIGGER `tr_batch_no_delete` BEFORE DELETE ON `sys_grant_batch` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch history is retained';
CREATE TRIGGER `tr_item_no_delete` BEFORE DELETE ON `sys_grant_batch_item` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Batch item history is retained';
CREATE TRIGGER `tr_ee_no_delete` BEFORE DELETE ON `sys_external_event` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='External events are retained for idempotency';

-- 跨表完整性：只允许菜单权限单元、菜单与API必须同应用。
DELIMITER $$
CREATE TRIGGER `tr_menu_api_insert` BEFORE INSERT ON `sys_menu_api` FOR EACH ROW
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sys_menu m JOIN sys_api a ON a.app_id=m.app_id WHERE m.id=NEW.menu_id AND a.id=NEW.api_id AND m.menu_type=2) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Menu/API must belong to the same application and menu must be a permission unit';
  END IF;
END$$
CREATE TRIGGER `tr_role_menu_insert` BEFORE INSERT ON `sys_role_permission` FOR EACH ROW
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id=NEW.menu_id AND m.menu_type=2) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Roles can only receive menu permission units';
  END IF;
END$$
CREATE TRIGGER `tr_menu_api_update` BEFORE UPDATE ON `sys_menu_api` FOR EACH ROW
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sys_menu m JOIN sys_api a ON a.app_id=m.app_id WHERE m.id=NEW.menu_id AND a.id=NEW.api_id AND m.menu_type=2) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Menu/API must belong to the same application and menu must be a permission unit';
  END IF;
END$$
CREATE TRIGGER `tr_role_menu_update` BEFORE UPDATE ON `sys_role_permission` FOR EACH ROW
BEGIN
  IF NOT EXISTS (SELECT 1 FROM sys_menu m WHERE m.id=NEW.menu_id AND m.menu_type=2) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Roles can only receive menu permission units';
  END IF;
END$$
CREATE TRIGGER `tr_menu_parent_insert` BEFORE INSERT ON `sys_menu` FOR EACH ROW
BEGIN
  IF NEW.parent_id<>0 AND (NEW.parent_id=NEW.id OR NOT EXISTS (SELECT 1 FROM sys_menu p WHERE p.id=NEW.parent_id AND p.app_id=NEW.app_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Menu parent must be a different menu in the same application';
  END IF;
END$$
CREATE TRIGGER `tr_menu_relationship_update` BEFORE UPDATE ON `sys_menu` FOR EACH ROW
BEGIN
  IF NEW.parent_id<>0 AND (NEW.parent_id=NEW.id OR NOT EXISTS (SELECT 1 FROM sys_menu p WHERE p.id=NEW.parent_id AND p.app_id=NEW.app_id)) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Menu parent must be a different menu in the same application';
  END IF;
  IF EXISTS (SELECT 1 FROM sys_menu c WHERE c.parent_id=OLD.id AND c.app_id<>NEW.app_id)
     OR EXISTS (SELECT 1 FROM sys_menu_api ma JOIN sys_api a ON a.id=ma.api_id WHERE ma.menu_id=OLD.id AND a.app_id<>NEW.app_id)
     OR (NEW.menu_type<>2 AND (EXISTS (SELECT 1 FROM sys_role_permission rp WHERE rp.menu_id=OLD.id) OR EXISTS (SELECT 1 FROM sys_menu_api ma WHERE ma.menu_id=OLD.id))) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Menu update would invalidate existing authorization relationships';
  END IF;
END$$
CREATE TRIGGER `tr_api_relationship_update` BEFORE UPDATE ON `sys_api` FOR EACH ROW
BEGIN
  IF EXISTS (SELECT 1 FROM sys_menu_api ma JOIN sys_menu m ON m.id=ma.menu_id WHERE ma.api_id=OLD.id AND m.app_id<>NEW.app_id) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='API update would break application boundary';
  END IF;
END$$
DELIMITER ;

ALTER TABLE sys_dept
 ADD KEY idx_dept_merged_into (merged_into_dept_id),
 ADD CONSTRAINT fk_dept_merged_into FOREIGN KEY (merged_into_dept_id) REFERENCES sys_dept(id);
DELIMITER $$
CREATE TRIGGER tr_dept_merge_self BEFORE UPDATE ON sys_dept FOR EACH ROW
BEGIN
 IF NEW.merged_into_dept_id = NEW.id THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Department cannot merge into itself';
 END IF;
END$$
DELIMITER ;

DELIMITER $$
CREATE TRIGGER tr_dept_merge_insert BEFORE INSERT ON sys_dept FOR EACH ROW
BEGIN
 IF NEW.merged_into_dept_id IS NOT NULL THEN
  SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='New department cannot start merged';
 END IF;
END$$
DELIMITER ;
