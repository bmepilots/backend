ALTER TABLE audit_log
  ADD COLUMN request_method VARCHAR(10) NULL,
  ADD COLUMN request_path VARCHAR(255) NULL,
  ADD COLUMN response_status INT NULL,
  ADD COLUMN duration_ms BIGINT NULL;
CREATE INDEX ix_audit_action ON audit_log(action, id);
