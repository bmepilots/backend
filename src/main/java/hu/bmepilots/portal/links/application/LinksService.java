package hu.bmepilots.portal.links.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LinksService {
  private final JdbcClient db;
  private final AuditService audit;

  public LinksService(JdbcClient db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  public record Category(String id, String name, int sortOrder) {}

  public record CategoryChange(@NotBlank @Size(max = 100) String name, @Min(0) int sortOrder) {}

  public record Item(
      String id,
      String categoryId,
      String categoryName,
      String name,
      String url,
      String description,
      int sortOrder,
      long version,
      String authorId,
      String authorName,
      LocalDateTime createdAt) {}

  public record Change(
      @NotBlank String categoryId,
      @NotBlank @Size(max = 120) String name,
      @NotBlank @Size(max = 2048) String url,
      @NotNull @Size(max = 500) String description,
      @Min(0) int sortOrder,
      @NotNull @Min(0) Long version) {}

  private static final String VIEW =
      "SELECT l.id,l.category_id,c.name category_name,l.name,l.url,l.description,l.sort_order,l.version,l.created_by author_id,COALESCE(u.display_name,'Original content') author_name,l.created_at FROM useful_links l JOIN link_categories c ON c.id=l.category_id LEFT JOIN users u ON u.id=l.created_by";

  public List<Category> categories() {
    return db.sql("SELECT id,name,sort_order FROM link_categories ORDER BY sort_order,name")
        .query(Category.class)
        .list();
  }

  @Transactional
  public Category category(String id, CategoryChange c) {
    requireAdmin();
    if (id == null) {
      id = UUID.randomUUID().toString();
      db.sql("INSERT INTO link_categories(id,name,sort_order) VALUES (?,?,?)")
          .params(id, c.name().trim(), c.sortOrder())
          .update();
    } else if (db.sql("UPDATE link_categories SET name=?,sort_order=? WHERE id=?")
            .params(c.name().trim(), c.sortOrder(), id)
            .update()
        != 1) throw ApiException.missing();
    audit.record("LINK_CATEGORY_SAVED", "LINK_CATEGORY", id);
    return new Category(id, c.name().trim(), c.sortOrder());
  }

  @Transactional
  public void deleteCategory(String id) {
    requireAdmin();
    if (db.sql("DELETE FROM link_categories WHERE id=?").param(id).update() != 1)
      throw ApiException.missing();
    audit.record("LINK_CATEGORY_DELETED", "LINK_CATEGORY", id);
  }

  public List<Item> list() {
    return db.sql(VIEW + " ORDER BY c.sort_order,c.name,l.sort_order,l.name,l.id LIMIT 500")
        .query(Item.class)
        .list();
  }

  public Item get(String id) {
    return db.sql(VIEW + " WHERE l.id=?")
        .param(id)
        .query(Item.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  private void requireAdmin() {
    if (!CurrentUser.principal().role().equals("ADMIN"))
      throw new AccessDeniedException("Administrator access required.");
  }

  private void requireOwnerOrAdmin(Item item) {
    var actor = CurrentUser.principal();
    if (!actor.role().equals("ADMIN") && !actor.id().equals(item.authorId()))
      throw new AccessDeniedException("Only the author or an administrator can manage this link.");
  }

  public static void validateUrl(String value) {
    URI uri = URI.create(value);
    if (!Set.of("https", "http")
            .contains(Objects.toString(uri.getScheme(), "").toLowerCase(Locale.ROOT))
        || uri.getHost() == null
        || uri.getUserInfo() != null) throw new IllegalArgumentException("Invalid link");
  }

  @Transactional
  public Item save(String id, Change c) {
    validateUrl(c.url());
    boolean creating = id == null;
    if (id == null) {
      id = UUID.randomUUID().toString();
      db.sql(
              "INSERT INTO useful_links(id,category_id,name,url,description,sort_order,created_by) VALUES (?,?,?,?,?,?,?)")
          .params(
              id,
              c.categoryId(),
              c.name().trim(),
              c.url(),
              c.description(),
              c.sortOrder(),
              CurrentUser.id())
          .update();
    } else {
      requireOwnerOrAdmin(get(id));
      if (db.sql(
                  "UPDATE useful_links SET category_id=?,name=?,url=?,description=?,sort_order=?,version=version+1 WHERE id=? AND version=?")
              .params(
                  c.categoryId(),
                  c.name().trim(),
                  c.url(),
                  c.description(),
                  c.sortOrder(),
                  id,
                  c.version())
              .update()
          != 1) throw ApiException.conflict();
    }
    audit.record(creating ? "LINK_CREATED" : "LINK_UPDATED", "LINK", id);
    return get(id);
  }

  @Transactional
  public void delete(String id, long version) {
    requireOwnerOrAdmin(get(id));
    if (db.sql("DELETE FROM useful_links WHERE id=? AND version=?").params(id, version).update()
        != 1) throw ApiException.conflict();
    audit.record("LINK_DELETED", "LINK", id);
  }
}
