-- Apply to an existing demonstration database after a manual backup.
-- Existing decision logs are accepted; new decisions retain only a compact signed log.
ALTER TABLE sys_operation_log DROP CHECK ck_auth_allowed_path;
ALTER TABLE sys_operation_log ADD CONSTRAINT ck_auth_allowed_path CHECK (
  operation_type <> 'AUTH_CHECK' OR auth_decision = 0 OR
  (user_id IS NOT NULL AND app_id IS NOT NULL AND api_id IS NOT NULL AND
   JSON_CONTAINS_PATH(evidence, 'all', '$.decisionId', '$.metadata', '$.seal') = 1)
);
-- No history, batch records, event records or signature keys are deleted.
