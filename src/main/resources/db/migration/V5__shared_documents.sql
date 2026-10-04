CREATE TABLE document_posts (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  description MEDIUMTEXT NOT NULL,
  created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  legacy_article_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL UNIQUE,
  legacy_category_name VARCHAR(100) NULL,
  FOREIGN KEY (created_by) REFERENCES users(id),
  FOREIGN KEY (updated_by) REFERENCES users(id),
  INDEX ix_document_date (updated_at, id)
);

CREATE TABLE document_files (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  post_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  filename VARCHAR(200) NOT NULL,
  content_type VARCHAR(255) NOT NULL,
  size_bytes BIGINT NOT NULL,
  storage_key CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL UNIQUE,
  position INT NOT NULL,
  FOREIGN KEY (post_id) REFERENCES document_posts(id) ON DELETE CASCADE,
  UNIQUE KEY uq_document_file_position (post_id, position)
);

CREATE TABLE document_comments (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  post_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  body TEXT NOT NULL,
  created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY (post_id) REFERENCES document_posts(id) ON DELETE CASCADE,
  FOREIGN KEY (created_by) REFERENCES users(id),
  FOREIGN KEY (updated_by) REFERENCES users(id),
  INDEX ix_document_comment_date (post_id, created_at, id)
);

-- Preserve existing knowledge article identity, complete body, authorship and history.
-- Original tables remain intact as a legacy archive; no file is invented for old text.
INSERT INTO document_posts
  (id,title,description,created_by,updated_by,created_at,updated_at,version,legacy_article_id,legacy_category_name)
SELECT a.id,a.title,a.body_markdown,a.created_by,a.updated_by,a.created_at,a.updated_at,a.version,a.id,c.name
FROM knowledge_articles a JOIN knowledge_categories c ON c.id=a.category_id;
