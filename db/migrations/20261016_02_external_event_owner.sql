-- Existing retained events are left unchanged. Legacy PROCESSING events require controlled recovery.
-- Token fencing prevents delayed former workers from applying after explicit operator recovery.
ALTER TABLE sys_external_event ADD COLUMN claim_token CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL AFTER payload_digest;
