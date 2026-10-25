-- 课程简化版：11 张表。仅在新空库执行，不用于升级或覆盖旧库。
-- 在已选中的新数据库执行。Docker 自动选择 MYSQL_DATABASE。
SET NAMES utf8mb4;
CREATE TABLE sys_dept (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, parent_id BIGINT NOT NULL DEFAULT 0,
 dept_code VARCHAR(64) NOT NULL UNIQUE, dept_name VARCHAR(128) NOT NULL,
 status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1)), merged_into_dept_id BIGINT NULL,
 INDEX idx_dept_parent(parent_id), FOREIGN KEY(merged_into_dept_id) REFERENCES sys_dept(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_user (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, username VARCHAR(64) NOT NULL UNIQUE,
 real_name VARCHAR(64) NOT NULL, phone VARCHAR(11) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL,
 employee_no VARCHAR(64) NULL UNIQUE, dept_id BIGINT NOT NULL,
 status INT NOT NULL DEFAULT 1 CHECK(status IN (1,2,3)), source INT NOT NULL DEFAULT 1 CHECK(source IN (1,2,3)),
 employment_type INT NULL CHECK(employment_type IN (1,2)),
 management_scope VARCHAR(16) NOT NULL DEFAULT 'DEPT_TREE' CHECK(management_scope IN ('ALL','DEPT_TREE')),
 session_version BIGINT NOT NULL DEFAULT 1 CHECK(session_version>0), created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 FOREIGN KEY(dept_id) REFERENCES sys_dept(id),
 CHECK((source=1 AND employee_no IS NOT NULL AND employment_type IS NOT NULL) OR (source IN (2,3) AND employee_no IS NULL AND employment_type IS NULL))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_application (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, app_code VARCHAR(64) NOT NULL UNIQUE,
 app_name VARCHAR(128) NOT NULL, app_desc VARCHAR(512), status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_menu (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, app_id BIGINT NOT NULL, parent_id BIGINT NOT NULL DEFAULT 0,
 menu_code VARCHAR(64) NOT NULL, menu_name VARCHAR(128) NOT NULL,
 menu_type INT NOT NULL DEFAULT 2 CHECK(menu_type IN (1,2)), path VARCHAR(256), sort_order INT NOT NULL DEFAULT 0,
 status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1)), UNIQUE(app_id,menu_code),
 FOREIGN KEY(app_id) REFERENCES sys_application(id), INDEX idx_menu_parent(parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_api (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, app_id BIGINT NOT NULL, api_code VARCHAR(128) NOT NULL, api_name VARCHAR(128) NOT NULL,
 api_path VARCHAR(256) COLLATE utf8mb4_bin NOT NULL, canonical_path VARCHAR(256) COLLATE utf8mb4_bin NOT NULL,
 http_method VARCHAR(16) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1)), UNIQUE(app_id,api_code), UNIQUE(app_id,http_method,canonical_path),
 FOREIGN KEY(app_id) REFERENCES sys_application(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_role (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, role_code VARCHAR(64) NOT NULL UNIQUE,
 role_name VARCHAR(128) NOT NULL, role_desc VARCHAR(512), status INT NOT NULL DEFAULT 1 CHECK(status IN (0,1))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_menu_api (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, menu_id BIGINT NOT NULL, api_id BIGINT NOT NULL,
 UNIQUE(menu_id,api_id), FOREIGN KEY(menu_id) REFERENCES sys_menu(id), FOREIGN KEY(api_id) REFERENCES sys_api(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_user_role (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NOT NULL, role_id BIGINT NOT NULL,
 UNIQUE(user_id,role_id), FOREIGN KEY(user_id) REFERENCES sys_user(id), FOREIGN KEY(role_id) REFERENCES sys_role(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_role_permission (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, role_id BIGINT NOT NULL, menu_id BIGINT NOT NULL,
 UNIQUE(role_id,menu_id), FOREIGN KEY(role_id) REFERENCES sys_role(id), FOREIGN KEY(menu_id) REFERENCES sys_menu(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_data_signature (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, data_type VARCHAR(32) NOT NULL, data_id BIGINT NOT NULL,
 signature VARCHAR(128) NOT NULL, UNIQUE(data_type,data_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE sys_operation_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT, user_id BIGINT NULL, target_user_id BIGINT NULL, dept_id BIGINT NULL,
 operation_type VARCHAR(64) NOT NULL, target_type VARCHAR(32) NOT NULL, target_id BIGINT NULL,
 detail TEXT, success BOOLEAN NOT NULL, created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
 INDEX idx_log_user(user_id), INDEX idx_log_dept(dept_id), INDEX idx_log_time(created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
