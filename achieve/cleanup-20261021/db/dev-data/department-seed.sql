-- LOCAL DEVELOPMENT DATA ONLY. Not HR codes, not a migration or startup initializer.
-- Run against your personal MySQL 8 database in ONE fresh connection, stop on error.
-- mysql batch mode WITHOUT --force: a failing assertion disconnects and rolls back.
-- Existing name/parent/path conflicts are errors; existing business fields are untouched.
-- Path convention: /<root internal id>/<child internal id>/ (includes self, trailing slash).
SET NAMES utf8mb4;
SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ENGINE_SUBSTITUTION';
CREATE TEMPORARY TABLE department_seed_guard (
  ok INT NOT NULL, CONSTRAINT department_seed_structure_conflict CHECK (ok = 1)
);
INSERT INTO department_seed_guard VALUES (GET_LOCK('whatname.dev.department-seed', 10));
START TRANSACTION;
-- Source row 2: DEV-DEPT-R0002
SET @seed_parent = 0, @seed_parent_path = '/';
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303032' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E680BBE585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303032' USING utf8mb4), CONVERT(X'E680BBE585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 2, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 3: DEV-DEPT-R0003
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303033' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303033' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303033' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303033' USING utf8mb4), CONVERT(X'E7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 3, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 4: DEV-DEPT-R0004
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303034' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5859AE5A794E5B7A5E4BD9CE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303034' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303034' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303034' USING utf8mb4), CONVERT(X'E5859AE5A794E5B7A5E4BD9CE983A8' USING utf8mb4), @seed_parent_path, NULL, 4, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 5: DEV-DEPT-R0005
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303035' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E4BABAE58A9BE8B584E6BA90E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303035' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303035' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303035' USING utf8mb4), CONVERT(X'E4BABAE58A9BE8B584E6BA90E983A8' USING utf8mb4), @seed_parent_path, NULL, 5, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 6: DEV-DEPT-R0006
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303036' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303036' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303036' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303036' USING utf8mb4), CONVERT(X'E8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 6, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 7: DEV-DEPT-R0007
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303037' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303037' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303037' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303037' USING utf8mb4), CONVERT(X'E5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 7, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 8: DEV-DEPT-R0008
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303038' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303038' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303038' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303038' USING utf8mb4), CONVERT(X'E5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 8, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 9: DEV-DEPT-R0009
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303039' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303039' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303039' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303039' USING utf8mb4), CONVERT(X'E5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 9, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 10: DEV-DEPT-R0010
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303130' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303130' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303130' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303130' USING utf8mb4), CONVERT(X'E789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 10, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 11: DEV-DEPT-R0011
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303131' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5AEA1E8AEA1E79B91E5AF9FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303131' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303131' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303131' USING utf8mb4), CONVERT(X'E5AEA1E8AEA1E79B91E5AF9FE983A8' USING utf8mb4), @seed_parent_path, NULL, 11, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 12: DEV-DEPT-R0012
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303132' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B7A5E4BC9AE58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303132' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303132' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303132' USING utf8mb4), CONVERT(X'E5B7A5E4BC9AE58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 12, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 13: DEV-DEPT-R0013
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303133' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E585ACE58FB8E9A286E5AFBC' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303133' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303133' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303133' USING utf8mb4), CONVERT(X'E585ACE58FB8E9A286E5AFBC' USING utf8mb4), @seed_parent_path, NULL, 13, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 14: DEV-DEPT-R0014
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303134' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303134' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 14, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 15: DEV-DEPT-R0015
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303135' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303135' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303135' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303135' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 15, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 16: DEV-DEPT-R0016
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303136' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303136' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303136' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303136' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 16, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 17: DEV-DEPT-R0017
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303137' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303137' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303137' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303137' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 17, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 18: DEV-DEPT-R0018
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303138' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303138' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303138' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303138' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 18, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 19: DEV-DEPT-R0019
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303139' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303139' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303139' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303139' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 19, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 20: DEV-DEPT-R0020
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303230' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303230' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303230' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303230' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 20, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 21: DEV-DEPT-R0021
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303231' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303231' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303231' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303231' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 21, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 22: DEV-DEPT-R0022
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303232' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303232' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303232' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303232' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 22, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 23: DEV-DEPT-R0023
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303233' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303233' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303233' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303233' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 23, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 24: DEV-DEPT-R0024
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303134' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303234' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303234' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303234' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303234' USING utf8mb4), CONVERT(X'E98193E8B7AFE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 24, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 25: DEV-DEPT-R0025
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303235' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303235' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 25, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 26: DEV-DEPT-R0026
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303236' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303236' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303236' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303236' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 26, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 27: DEV-DEPT-R0027
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303237' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303237' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303237' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303237' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 27, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 28: DEV-DEPT-R0028
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303238' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303238' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303238' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303238' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 28, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 29: DEV-DEPT-R0029
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303239' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303239' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303239' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303239' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 29, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 30: DEV-DEPT-R0030
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303330' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303330' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303330' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303330' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 30, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 31: DEV-DEPT-R0031
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303331' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303331' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303331' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303331' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 31, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 32: DEV-DEPT-R0032
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303332' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303332' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303332' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303332' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 32, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 33: DEV-DEPT-R0033
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303333' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303333' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303333' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303333' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 33, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 34: DEV-DEPT-R0034
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303334' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303334' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303334' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303334' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 34, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 35: DEV-DEPT-R0035
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303235' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303335' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303335' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303335' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303335' USING utf8mb4), CONVERT(X'E6A1A5E6A281E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 35, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 36: DEV-DEPT-R0036
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303336' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303336' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 36, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 37: DEV-DEPT-R0037
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303337' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303337' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303337' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303337' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 37, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 38: DEV-DEPT-R0038
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303338' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303338' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303338' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303338' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 38, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 39: DEV-DEPT-R0039
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303339' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303339' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303339' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303339' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 39, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 40: DEV-DEPT-R0040
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303430' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303430' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303430' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303430' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 40, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 41: DEV-DEPT-R0041
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303431' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303431' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303431' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303431' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 41, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 42: DEV-DEPT-R0042
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303432' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303432' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303432' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303432' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 42, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 43: DEV-DEPT-R0043
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303433' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303433' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303433' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303433' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 43, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 44: DEV-DEPT-R0044
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303434' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303434' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303434' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303434' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 44, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 45: DEV-DEPT-R0045
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303435' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303435' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303435' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303435' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 45, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 46: DEV-DEPT-R0046
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303336' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303436' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303436' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303436' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303436' USING utf8mb4), CONVERT(X'E99AA7E98193E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 46, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 47: DEV-DEPT-R0047
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303437' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303437' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 47, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 48: DEV-DEPT-R0048
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303438' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303438' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303438' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303438' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 48, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 49: DEV-DEPT-R0049
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303439' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303439' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303439' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303439' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 49, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 50: DEV-DEPT-R0050
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303530' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303530' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303530' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303530' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 50, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 51: DEV-DEPT-R0051
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303531' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303531' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303531' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303531' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 51, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 52: DEV-DEPT-R0052
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303532' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303532' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303532' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303532' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 52, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 53: DEV-DEPT-R0053
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303533' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303533' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303533' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303533' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 53, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 54: DEV-DEPT-R0054
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303534' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303534' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303534' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303534' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 54, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 55: DEV-DEPT-R0055
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303535' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303535' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303535' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303535' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 55, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 56: DEV-DEPT-R0056
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303536' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303536' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303536' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303536' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 56, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 57: DEV-DEPT-R0057
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303437' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303537' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303537' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303537' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303537' USING utf8mb4), CONVERT(X'E8BDA8E98193E4BAA4E9809AE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 57, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 58: DEV-DEPT-R0058
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303538' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303538' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 58, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 59: DEV-DEPT-R0059
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303539' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303539' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303539' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303539' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 59, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 60: DEV-DEPT-R0060
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303630' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303630' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303630' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303630' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 60, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 61: DEV-DEPT-R0061
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303631' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303631' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303631' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303631' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 61, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 62: DEV-DEPT-R0062
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303632' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303632' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303632' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303632' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 62, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 63: DEV-DEPT-R0063
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303633' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303633' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303633' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303633' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 63, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 64: DEV-DEPT-R0064
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303634' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303634' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303634' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303634' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 64, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 65: DEV-DEPT-R0065
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303635' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303635' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303635' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303635' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 65, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 66: DEV-DEPT-R0066
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303636' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303636' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303636' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303636' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 66, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 67: DEV-DEPT-R0067
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303637' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303637' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303637' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303637' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 67, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 68: DEV-DEPT-R0068
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303538' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303638' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303638' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303638' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303638' USING utf8mb4), CONVERT(X'E7AEA1E7BD91E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 68, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 69: DEV-DEPT-R0069
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303639' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303639' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 69, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 70: DEV-DEPT-R0070
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303730' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303730' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303730' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303730' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 70, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 71: DEV-DEPT-R0071
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303731' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303731' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303731' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303731' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 71, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 72: DEV-DEPT-R0072
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303732' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303732' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303732' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303732' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 72, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 73: DEV-DEPT-R0073
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303733' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303733' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303733' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303733' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 73, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 74: DEV-DEPT-R0074
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303734' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303734' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303734' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303734' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 74, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 75: DEV-DEPT-R0075
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303735' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303735' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303735' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303735' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 75, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 76: DEV-DEPT-R0076
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303736' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303736' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303736' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303736' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 76, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 77: DEV-DEPT-R0077
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303737' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303737' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303737' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303737' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 77, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 78: DEV-DEPT-R0078
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303738' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303738' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303738' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303738' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 78, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 79: DEV-DEPT-R0079
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303639' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303739' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303739' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303739' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303739' USING utf8mb4), CONVERT(X'E6B0B4E58AA1E5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 79, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 80: DEV-DEPT-R0080
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303830' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303830' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 80, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 81: DEV-DEPT-R0081
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303831' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303831' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303831' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303831' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 81, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 82: DEV-DEPT-R0082
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303832' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303832' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303832' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303832' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 82, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 83: DEV-DEPT-R0083
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303833' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303833' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303833' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303833' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 83, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 84: DEV-DEPT-R0084
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303834' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303834' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303834' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303834' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 84, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 85: DEV-DEPT-R0085
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303835' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303835' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303835' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303835' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 85, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 86: DEV-DEPT-R0086
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303836' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303836' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303836' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303836' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 86, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 87: DEV-DEPT-R0087
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303837' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303837' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303837' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303837' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 87, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 88: DEV-DEPT-R0088
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303838' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303838' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303838' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303838' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 88, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 89: DEV-DEPT-R0089
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303839' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303839' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303839' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303839' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 89, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 90: DEV-DEPT-R0090
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303830' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303930' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303930' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303930' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303930' USING utf8mb4), CONVERT(X'E59BADE69E97E7BBBFE58C96E58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 90, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 91: DEV-DEPT-R0091
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303931' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303931' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 91, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 92: DEV-DEPT-R0092
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303932' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303932' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303932' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303932' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 92, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 93: DEV-DEPT-R0093
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303933' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303933' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303933' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303933' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 93, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 94: DEV-DEPT-R0094
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303934' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303934' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303934' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303934' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 94, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 95: DEV-DEPT-R0095
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303935' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303935' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303935' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303935' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 95, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 96: DEV-DEPT-R0096
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303936' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303936' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303936' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303936' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 96, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 97: DEV-DEPT-R0097
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303937' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303937' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303937' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303937' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 97, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 98: DEV-DEPT-R0098
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303938' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303938' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303938' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303938' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 98, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 99: DEV-DEPT-R0099
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230303939' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303939' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230303939' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230303939' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 99, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 100: DEV-DEPT-R0100
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313030' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313030' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313030' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313030' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 100, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 101: DEV-DEPT-R0101
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303931' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313031' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313031' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313031' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313031' USING utf8mb4), CONVERT(X'E785A7E6988EE5B7A5E7A88BE58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 101, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 102: DEV-DEPT-R0102
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230303032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313032' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313032' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB8' USING utf8mb4), @seed_parent_path, NULL, 102, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 103: DEV-DEPT-R0103
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313033' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313033' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313033' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313033' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7BBBCE59088E58A9EE585ACE5AEA4' USING utf8mb4), @seed_parent_path, NULL, 103, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 104: DEV-DEPT-R0104
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313034' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313034' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313034' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313034' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE8B4A2E58AA1E983A8' USING utf8mb4), @seed_parent_path, NULL, 104, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 105: DEV-DEPT-R0105
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313035' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313035' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313035' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313035' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE5B7A5E7A88BE68A80E69CAFE983A8' USING utf8mb4), @seed_parent_path, NULL, 105, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 106: DEV-DEPT-R0106
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313036' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313036' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313036' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313036' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE5AE89E585A8E8B4A8E9878FE983A8' USING utf8mb4), @seed_parent_path, NULL, 106, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 107: DEV-DEPT-R0107
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313037' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313037' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313037' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313037' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE789A9E8B584E8AEBEE5A487E983A8' USING utf8mb4), @seed_parent_path, NULL, 107, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 108: DEV-DEPT-R0108
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313038' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313038' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313038' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313038' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE5B882E59CBAE7BB8FE890A5E983A8' USING utf8mb4), @seed_parent_path, NULL, 108, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 109: DEV-DEPT-R0109
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313039' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313039' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313039' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313039' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE4B880E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 109, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 110: DEV-DEPT-R0110
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313130' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313130' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313130' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313130' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE4BA8CE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 110, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 111: DEV-DEPT-R0111
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313131' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313131' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313131' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313131' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE4B889E9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 111, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
-- Source row 112: DEV-DEPT-R0112
SET @seed_parent = NULL, @seed_parent_path = NULL;
SELECT id, dept_path INTO @seed_parent, @seed_parent_path
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313032' USING utf8mb4) FOR UPDATE;
INSERT INTO department_seed_guard VALUES (IF(@seed_parent IS NOT NULL, 1, 0));
SET @seed_id = NULL, @seed_matches = 0;
SELECT id, IF(BINARY dept_code = BINARY CONVERT(X'4445562D444550542D5230313132' USING utf8mb4)
  AND BINARY dept_name = BINARY CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4) AND parent_id = @seed_parent
  AND BINARY dept_path = BINARY CONCAT(@seed_parent_path, id, '/'), 1, 0)
INTO @seed_id, @seed_matches
FROM sys_dept WHERE dept_code = CONVERT(X'4445562D444550542D5230313132' USING utf8mb4) FOR UPDATE;
SELECT CONVERT(X'4445562D444550542D5230313132' USING utf8mb4) AS conflicting_dept_code, 'name/parent/path differs; no overwrite' AS reason
WHERE @seed_id IS NOT NULL AND @seed_matches = 0;
INSERT INTO department_seed_guard VALUES (IF(@seed_id IS NULL OR @seed_matches = 1, 1, 0));
SET @seed_new = (@seed_id IS NULL);
INSERT INTO sys_dept (parent_id, dept_code, dept_name, dept_path, manager_id, sort_order, status, hr_version)
SELECT @seed_parent, CONVERT(X'4445562D444550542D5230313132' USING utf8mb4), CONVERT(X'E5B882E694BFE585BBE68AA4E58886E585ACE58FB82DE7ACACE59B9BE9A1B9E79BAEE7BB8FE79086E983A8' USING utf8mb4), @seed_parent_path, NULL, 112, 1, 0
WHERE @seed_new;
SET @seed_id = IF(@seed_new, LAST_INSERT_ID(), @seed_id);
UPDATE sys_dept SET dept_path = CONCAT(@seed_parent_path, @seed_id, '/')
WHERE id = @seed_id AND @seed_new;
COMMIT;
DO RELEASE_LOCK('whatname.dev.department-seed');
DROP TEMPORARY TABLE department_seed_guard;
