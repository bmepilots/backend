CREATE TABLE mail_accounts (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 email_address VARCHAR(254) NOT NULL UNIQUE,
 provider_type VARCHAR(30) NOT NULL,
 last_attempt_at DATETIME(6) NULL,
 last_success_at DATETIME(6) NULL,
 last_error VARCHAR(80) NULL
);
CREATE TABLE mail_folders (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 external_key VARCHAR(255) NOT NULL,
 uid_validity BIGINT NOT NULL DEFAULT 0,
 last_uid BIGINT NOT NULL DEFAULT 0,
 UNIQUE(account_id, external_key),
 FOREIGN KEY(account_id) REFERENCES mail_accounts(id)
);
CREATE TABLE mail_messages (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 account_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 provider_message_id VARCHAR(128) NULL,
 message_id_header VARCHAR(998) NULL,
 subject VARCHAR(998) NOT NULL,
 sender_address VARCHAR(254) NOT NULL,
 sender_name VARCHAR(254) NOT NULL,
 received_at DATETIME(6) NOT NULL,
 body_text MEDIUMTEXT NOT NULL,
 body_html MEDIUMTEXT NOT NULL,
 imported_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 UNIQUE(account_id, provider_message_id),
 FOREIGN KEY(account_id) REFERENCES mail_accounts(id),
 INDEX ix_mail_date(received_at, id)
);
CREATE TABLE mail_message_locations (
 folder_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 uid_validity BIGINT NOT NULL,
 imap_uid BIGINT NOT NULL,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 PRIMARY KEY(folder_id, uid_validity, imap_uid),
 FOREIGN KEY(folder_id) REFERENCES mail_folders(id),
 FOREIGN KEY(message_id) REFERENCES mail_messages(id)
);
CREATE TABLE mail_message_addresses (
 id BIGINT AUTO_INCREMENT PRIMARY KEY,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 address_type VARCHAR(12) NOT NULL,
 address VARCHAR(254) NOT NULL,
 display_name VARCHAR(254) NOT NULL,
 FOREIGN KEY(message_id) REFERENCES mail_messages(id)
);
CREATE TABLE mail_attachments (
 id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 filename VARCHAR(255) NOT NULL,
 content_type VARCHAR(150) NOT NULL,
 size_bytes BIGINT NOT NULL,
 storage_key CHAR(36) NOT NULL,
 FOREIGN KEY(message_id) REFERENCES mail_messages(id)
);
CREATE TABLE user_mail_state (
 user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 message_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 is_read BOOLEAN NOT NULL DEFAULT FALSE,
 is_important BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(user_id, message_id),
 FOREIGN KEY(user_id) REFERENCES users(id),
 FOREIGN KEY(message_id) REFERENCES mail_messages(id)
);
