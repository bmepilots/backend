ALTER TABLE useful_links
  ADD COLUMN created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NULL,
  ADD COLUMN created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  ADD CONSTRAINT fk_links_author FOREIGN KEY (created_by) REFERENCES users(id);

-- Existing links have no recorded author. Preserve them without inventing one.
-- A first member can contribute immediately on a fresh installation.
INSERT INTO link_categories(id,name,sort_order)
SELECT UUID(),'General',0
WHERE NOT EXISTS (SELECT 1 FROM link_categories);

CREATE TABLE calendar_events (
  id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY,
  title VARCHAR(180) NOT NULL,
  description TEXT NOT NULL,
  event_type VARCHAR(16) NOT NULL,
  starts_at DATETIME(6) NOT NULL,
  ends_at DATETIME(6) NULL,
  all_day BOOLEAN NOT NULL DEFAULT FALSE,
  location VARCHAR(200) NOT NULL DEFAULT '',
  created_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  updated_by CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  version BIGINT NOT NULL DEFAULT 0,
  FOREIGN KEY (created_by) REFERENCES users(id),
  FOREIGN KEY (updated_by) REFERENCES users(id),
  CONSTRAINT ck_calendar_type CHECK (event_type IN ('EXAM','EVENT','DEADLINE')),
  CONSTRAINT ck_calendar_range CHECK (ends_at IS NULL OR ends_at > starts_at),
  INDEX ix_calendar_start (starts_at),
  INDEX ix_calendar_end (ends_at)
);
