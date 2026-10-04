package hu.bmepilots.portal.knowledge.application;

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
public class KnowledgeService {
  private final JdbcClient db;
  private final AuditService audit;

  public KnowledgeService(JdbcClient db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  public record Category(String id, String name, int sortOrder) {}

  public record CategoryChange(@NotBlank @Size(max = 100) String name, @Min(0) int sortOrder) {}

  public record Article(
      String id,
      String categoryId,
      String categoryName,
      String title,
      String bodyMarkdown,
      String authorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long version) {}

  public record Change(
      @NotBlank String categoryId,
      @NotBlank @Size(max = 180) String title,
      @NotBlank @Size(max = 100000) String bodyMarkdown,
      @Min(0) long version) {}

  private static final String SELECT =
      "SELECT a.id,a.category_id,c.name category_name,a.title,a.body_markdown,u.display_name author_name,a.created_at,a.updated_at,a.version FROM knowledge_articles a JOIN knowledge_categories c ON c.id=a.category_id JOIN users u ON u.id=a.created_by";

  public List<Category> categories() {
    return db.sql("SELECT id,name,sort_order FROM knowledge_categories ORDER BY sort_order,name")
        .query(Category.class)
        .list();
  }

  @Transactional
  public Category category(String id, CategoryChange c) {
    if (id == null) {
      id = UUID.randomUUID().toString();
      db.sql("INSERT INTO knowledge_categories(id,name,sort_order) VALUES (?,?,?)")
          .params(id, c.name().trim(), c.sortOrder())
          .update();
    } else if (db.sql("UPDATE knowledge_categories SET name=?,sort_order=? WHERE id=?")
            .params(c.name().trim(), c.sortOrder(), id)
            .update()
        != 1) throw ApiException.missing();
    audit.record("KNOWLEDGE_CATEGORY_SAVED", "KNOWLEDGE_CATEGORY", id);
    return new Category(id, c.name().trim(), c.sortOrder());
  }

  @Transactional
  public void deleteCategory(String id) {
    if (db.sql("DELETE FROM knowledge_categories WHERE id=?").param(id).update() != 1)
      throw ApiException.missing();
    audit.record("KNOWLEDGE_CATEGORY_DELETED", "KNOWLEDGE_CATEGORY", id);
  }

  public List<Article> list(String category, int page) {
    return db.sql(
            SELECT
                + (category == null ? "" : " WHERE a.category_id=:category")
                + " ORDER BY a.updated_at DESC,a.id LIMIT 20 OFFSET :offset")
        .param("category", category)
        .param("offset", Math.max(0, Math.min(page, 10000)) * 20)
        .query(Article.class)
        .list();
  }

  public Article get(String id) {
    return db.sql(SELECT + " WHERE a.id=?")
        .param(id)
        .query(Article.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional
  public Article create(Change c) {
    String id = UUID.randomUUID().toString();
    db.sql(
            "INSERT INTO knowledge_articles(id,category_id,title,body_markdown,created_by,updated_by) VALUES (?,?,?,?,?,?)")
        .params(
            id,
            c.categoryId(),
            c.title().trim(),
            c.bodyMarkdown(),
            CurrentUser.id(),
            CurrentUser.id())
        .update();
    audit.record("ARTICLE_CREATED", "ARTICLE", id);
    return get(id);
  }

  @Transactional
  public Article update(String id, Change c) {
    if (db.sql(
                "UPDATE knowledge_articles SET category_id=?,title=?,body_markdown=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND version=?")
            .params(
                c.categoryId(),
                c.title().trim(),
                c.bodyMarkdown(),
                CurrentUser.id(),
                id,
                c.version())
            .update()
        != 1) throw ApiException.conflict();
    audit.record("ARTICLE_UPDATED", "ARTICLE", id);
    return get(id);
  }

  @Transactional
  public void delete(String id, long version) {
    if (db.sql("DELETE FROM knowledge_articles WHERE id=? AND version=?")
            .params(id, version)
            .update()
        != 1) throw ApiException.conflict();
    audit.record("ARTICLE_DELETED", "ARTICLE", id);
  }
}
