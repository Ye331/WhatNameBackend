-- Only upgrades the previous EMPTY demo schema. Never decrypts or discards data.
-- Run once with the backend stopped. New databases use db/schema.sql instead.
USE unified_permission;
DROP PROCEDURE IF EXISTS upgrade_empty_demo_identity_20261023;
DELIMITER //
CREATE PROCEDURE upgrade_empty_demo_identity_20261023()
BEGIN
  IF EXISTS(SELECT 1 FROM sys_user LIMIT 1)
     OR EXISTS(SELECT 1 FROM sys_sms_code LIMIT 1)
     OR EXISTS(SELECT 1 FROM sys_external_event LIMIT 1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Business data exists: separate decryption and re-signing migration required';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_user' AND column_name='phone_hash') THEN
    ALTER TABLE sys_user
      CHANGE COLUMN real_name_ciphertext real_name VARCHAR(64) NOT NULL COMMENT 'Plain demo name',
      MODIFY COLUMN phone VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Normalized demo phone',
      DROP INDEX uk_phone_hash,
      DROP COLUMN phone_hash,
      ADD UNIQUE KEY uk_phone (phone);
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_sms_code' AND column_name='phone_hash') THEN
    ALTER TABLE sys_sms_code
      CHANGE COLUMN phone_hash phone VARCHAR(11) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'Normalized demo phone';
  END IF;
  IF EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='sys_external_event' AND column_name='payload_ciphertext') THEN
    ALTER TABLE sys_external_event
      CHANGE COLUMN payload_ciphertext payload_json MEDIUMTEXT NOT NULL COMMENT 'Canonical demo JSON';
  END IF;
END//
DELIMITER ;
CALL upgrade_empty_demo_identity_20261023();
DROP PROCEDURE upgrade_empty_demo_identity_20261023;
