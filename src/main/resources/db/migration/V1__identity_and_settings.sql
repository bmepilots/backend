CREATE TABLE users (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  email VARCHAR(254) NOT NULL,
  email_normalized VARCHAR(254) NOT NULL UNIQUE,
  display_name VARCHAR(100) NOT NULL,
  password_hash VARCHAR(255) NOT NULL,
  status VARCHAR(30) NOT NULL,
  auth_version BIGINT NOT NULL DEFAULT 0,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  CONSTRAINT ck_user_status CHECK (status IN ('PENDING_APPROVAL','ACTIVE','REJECTED','SUSPENDED','DISABLED')),
  INDEX ix_users_status (status, created_at)
);
CREATE TABLE roles (code VARCHAR(20) PRIMARY KEY);
INSERT INTO roles(code) VALUES ('USER'),('ADMIN');
CREATE TABLE user_roles (
  user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  role_code VARCHAR(20) NOT NULL,
  PRIMARY KEY(user_id, role_code),
  FOREIGN KEY (user_id) REFERENCES users(id),
  FOREIGN KEY (role_code) REFERENCES roles(code)
);
CREATE TABLE app_settings (
  id INT PRIMARY KEY,
  registration_enabled BOOLEAN NOT NULL DEFAULT FALSE,
  portal_name VARCHAR(100) NOT NULL DEFAULT 'BME Pilots 2026',
  version BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT ck_settings_singleton CHECK (id = 1)
);
INSERT INTO app_settings(id) VALUES (1);
CREATE TABLE audit_log (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  actor_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  action VARCHAR(80) NOT NULL,
  entity_type VARCHAR(50) NOT NULL,
  entity_id VARCHAR(80) NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  FOREIGN KEY (actor_user_id) REFERENCES users(id),
  INDEX ix_audit_date (created_at, id)
);
