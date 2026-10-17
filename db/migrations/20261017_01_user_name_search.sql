-- Explicit, additive migration for an existing MySQL 8 database. Never auto-run.
-- Existing names are NOT decrypted here. Missing state fails closed on keyword searches.
CREATE TABLE sys_user_name_state (
  user_id BIGINT NOT NULL,
  generation CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  cipher_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  token_count SMALLINT UNSIGNED NOT NULL,
  PRIMARY KEY (user_id),
  CONSTRAINT fk_name_state_user FOREIGN KEY (user_id) REFERENCES sys_user(id),
  CONSTRAINT ck_name_token_count CHECK (token_count BETWEEN 1 AND 2080)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Versioned encrypted-name index readiness; no plaintext';

CREATE TABLE sys_user_name_token (
  user_id BIGINT NOT NULL,
  token CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  PRIMARY KEY (user_id,token),
  KEY idx_name_token_user (token,user_id),
  CONSTRAINT fk_name_token_user FOREIGN KEY (user_id) REFERENCES sys_user(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Dedicated-key HMAC-SHA256 name substring index';
