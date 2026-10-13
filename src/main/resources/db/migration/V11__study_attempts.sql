CREATE TABLE study_attempts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  request_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  subject_id VARCHAR(80) NOT NULL,
  catalog_version VARCHAR(40) NOT NULL,
  mode VARCHAR(10) NOT NULL,
  request_json MEDIUMTEXT NOT NULL,
  result_json MEDIUMTEXT NOT NULL,
  earned INT NOT NULL,
  possible INT NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE(user_id, request_id),
  FOREIGN KEY(user_id) REFERENCES users(id),
  CHECK(mode IN ('PRACTICE', 'MOCK')),
  INDEX ix_study_user_subject(user_id, subject_id, created_at)
);
