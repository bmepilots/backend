CREATE TABLE mail_forwarding_settings (
 id INT PRIMARY KEY,
 enabled BOOLEAN NOT NULL DEFAULT FALSE,
 enabled_at DATETIME(6) NULL,
 version BIGINT NOT NULL DEFAULT 0,
 CHECK (id = 1)
);
INSERT INTO mail_forwarding_settings(id) VALUES (1);

CREATE TABLE mail_forwarding_outbox (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 enable_version BIGINT NOT NULL,
 state VARCHAR(12) NOT NULL DEFAULT 'PENDING',
 attempts INT NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 error_code VARCHAR(80) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(message_id, user_id),
 FOREIGN KEY(message_id) REFERENCES mail_messages(id),
 FOREIGN KEY(user_id) REFERENCES users(id),
 CHECK (state IN ('PENDING','SENDING','SENT','FAILED','UNCERTAIN','CANCELLED')),
 INDEX ix_forwarding_ready(state, next_attempt_at, created_at)
);
