-- Existing databases only. Preflight information_schema first; execute once, no --force.
ALTER TABLE sys_dept
 ADD COLUMN merged_into_dept_id BIGINT NULL COMMENT 'NULL active lifecycle; nonNULL merged destination' AFTER status,
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
