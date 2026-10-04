package hu.bmepilots.portal.announcement.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnnouncementService {
  private final JdbcClient db;
  private final AuditService audit;

  public AnnouncementService(JdbcClient db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  public record Item(
      String id,
      String title,
      String bodyMarkdown,
      boolean important,
      String authorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long version) {}

  public record Change(
      @NotBlank @Size(max = 180) String title,
      @NotBlank @Size(max = 30000) String bodyMarkdown,
      boolean important,
      @Min(0) long version) {}

  private static final String SELECT =
      "SELECT a.id,a.title,a.body_markdown,a.important,u.display_name author_name,a.created_at,a.updated_at,a.version FROM announcements a JOIN users u ON u.id=a.created_by";

  public List<Item> list(int page) {
    return db.sql(SELECT + " ORDER BY a.important DESC,a.created_at DESC,a.id LIMIT 20 OFFSET ?")
        .param(Math.max(0, Math.min(page, 10000)) * 20)
        .query(Item.class)
        .list();
  }

  public Item get(String id) {
    return db.sql(SELECT + " WHERE a.id=?")
        .param(id)
        .query(Item.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional
  public Item create(Change c) {
    String id = UUID.randomUUID().toString();
    db.sql(
            "INSERT INTO announcements(id,title,body_markdown,important,created_by,updated_by) VALUES (?,?,?,?,?,?)")
        .params(
            id,
            c.title().trim(),
            c.bodyMarkdown(),
            c.important(),
            CurrentUser.id(),
            CurrentUser.id())
        .update();
    audit.record("ANNOUNCEMENT_CREATED", "ANNOUNCEMENT", id);
    return get(id);
  }

  @Transactional
  public Item update(String id, Change c) {
    if (db.sql(
                "UPDATE announcements SET title=?,body_markdown=?,important=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND version=?")
            .params(
                c.title().trim(),
                c.bodyMarkdown(),
                c.important(),
                CurrentUser.id(),
                id,
                c.version())
            .update()
        != 1) throw ApiException.conflict();
    audit.record("ANNOUNCEMENT_UPDATED", "ANNOUNCEMENT", id);
    return get(id);
  }

  @Transactional
  public void delete(String id, long version) {
    if (db.sql("DELETE FROM announcements WHERE id=? AND version=?").params(id, version).update()
        != 1) throw ApiException.conflict();
    audit.record("ANNOUNCEMENT_DELETED", "ANNOUNCEMENT", id);
  }
}
