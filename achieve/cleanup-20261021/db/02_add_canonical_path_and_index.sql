-- Add canonical_path column and hash-based unique index to prevent equivalent route templates
-- WARNING: run in maintenance window. Backup before executing.

ALTER TABLE sys_api
  ADD COLUMN canonical_path VARCHAR(1024);

-- Backfill canonical_path by replacing {param} with {}
-- Requires MySQL 8+ for REGEXP_REPLACE.
UPDATE sys_api
SET canonical_path = REGEXP_REPLACE(api_path, '\\{[^}]+\\}', '{}');

-- Add a stored binary hash (MD5) of canonical_path and create a unique index on it
ALTER TABLE sys_api
  ADD COLUMN canonical_hash VARBINARY(16) GENERATED ALWAYS AS (UNHEX(MD5(COALESCE(canonical_path, '')))) STORED;

CREATE UNIQUE INDEX ux_sys_api_app_method_canonical
  ON sys_api(app_id, http_method, canonical_hash);

-- Rollback:
-- DROP INDEX ux_sys_api_app_method_canonical ON sys_api;
-- ALTER TABLE sys_api DROP COLUMN canonical_hash;
-- ALTER TABLE sys_api DROP COLUMN canonical_path;
