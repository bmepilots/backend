CREATE TABLE mail_sync_failures (
 folder_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
 uid_validity BIGINT NOT NULL,
 imap_uid BIGINT NOT NULL,
 error_code VARCHAR(80) NOT NULL,
 attempts INT NOT NULL DEFAULT 1,
 PRIMARY KEY(folder_id,uid_validity,imap_uid),
 FOREIGN KEY(folder_id) REFERENCES mail_folders(id)
);
