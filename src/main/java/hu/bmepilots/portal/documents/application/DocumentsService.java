package hu.bmepilots.portal.documents.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.CurrentUser;
import hu.bmepilots.portal.documents.infrastructure.DocumentStorage;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class DocumentsService {
  private final JdbcClient db;
  private final AuditService audit;
  private final DocumentStorage storage;

  public DocumentsService(JdbcClient db, AuditService audit, DocumentStorage storage) {
    this.db = db;
    this.audit = audit;
    this.storage = storage;
  }

  public record Post(
      String id,
      String title,
      String description,
      String authorId,
      String authorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long version,
      int fileCount,
      int commentCount) {}

  public record FileInfo(String id, String filename, long sizeBytes, String contentType) {}

  public record Comment(
      String id,
      String body,
      String authorId,
      String authorName,
      LocalDateTime createdAt,
      LocalDateTime updatedAt,
      long version) {}

  public record Detail(Post post, List<FileInfo> files, List<Comment> comments) {}

  public record Change(
      @NotBlank @Size(max = 180) String title,
      @NotNull @Size(max = 100000) String description,
      @NotNull @Min(0) Long version) {}

  public record NewComment(@NotBlank @Size(max = 5000) String body) {}

  public record CommentChange(
      @NotBlank @Size(max = 5000) String body, @NotNull @Min(0) Long version) {}

  public record Download(Path path, String filename, long sizeBytes) {}

  private static final String SELECT =
      """
      SELECT p.id,p.title,p.description,p.created_by author_id,u.display_name author_name,
      p.created_at,p.updated_at,p.version,
      (SELECT COUNT(*) FROM document_files f WHERE f.post_id=p.id) file_count,
      (SELECT COUNT(*) FROM document_comments c WHERE c.post_id=p.id) comment_count
      FROM document_posts p JOIN users u ON u.id=p.created_by
      """;

  private static final String COMMENT_SELECT =
      """
      SELECT c.id,c.body,c.created_by author_id,u.display_name author_name,
      c.created_at,c.updated_at,c.version
      FROM document_comments c JOIN users u ON u.id=c.created_by
      """;

  public List<Post> list(String query, int page) {
    String escaped = query.trim().replace("!", "!!").replace("%", "!%").replace("_", "!_");
    return db.sql(
            SELECT
                + " WHERE (p.title LIKE :q ESCAPE '!' OR p.description LIKE :q ESCAPE '!') ORDER BY p.updated_at DESC,p.id LIMIT 20 OFFSET :offset")
        .param("q", "%" + escaped + "%")
        .param("offset", Math.max(0, Math.min(page, 10000)) * 20)
        .query(Post.class)
        .list();
  }

  public Post get(String id) {
    return db.sql(SELECT + " WHERE p.id=?")
        .param(id)
        .query(Post.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional(readOnly = true)
  public Detail detail(String id) {
    return new Detail(
        get(id),
        db.sql(
                "SELECT id,filename,size_bytes,content_type FROM document_files WHERE post_id=? ORDER BY position")
            .param(id)
            .query(FileInfo.class)
            .list(),
        db.sql(COMMENT_SELECT + " WHERE c.post_id=? ORDER BY c.created_at,c.id")
            .param(id)
            .query(Comment.class)
            .list());
  }

  @Transactional
  public Post create(String title, String description, List<MultipartFile> files) {
    if (title == null
        || title.isBlank()
        || title.length() > 180
        || description == null
        || description.length() > 100000) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST,
          "VALIDATION",
          "Enter a title of up to 180 characters and a description of up to 100,000 characters.");
    }
    if (files == null || files.isEmpty() || files.size() > 5) {
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "VALIDATION", "Attach between 1 and 5 files to a post.");
    }
    for (var file : files) {
      if (file.getSize() > DocumentStorage.MAX_FILE_BYTES) throw DocumentStorage.tooLarge();
      if (file.isEmpty())
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "VALIDATION", "Empty files cannot be uploaded.");
    }
    String id = UUID.randomUUID().toString();
    List<String> saved = new ArrayList<>();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) saved.forEach(storage::delete);
          }
        });
    db.sql(
            "INSERT INTO document_posts(id,title,description,created_by,updated_by) VALUES (?,?,?,?,?)")
        .params(id, title.trim(), description, CurrentUser.id(), CurrentUser.id())
        .update();
    for (int position = 0; position < files.size(); position++) {
      var file = files.get(position);
      var stored = storage.save(file);
      saved.add(stored.key());
      String fileId = UUID.randomUUID().toString();
      db.sql(
              "INSERT INTO document_files(id,post_id,filename,content_type,size_bytes,storage_key,position) VALUES (?,?,?,?,?,?,?)")
          .params(
              fileId,
              id,
              filename(file.getOriginalFilename()),
              contentType(file.getContentType()),
              stored.sizeBytes(),
              stored.key(),
              position)
          .update();
      audit.record("DOCUMENT_FILE_UPLOADED", "DOCUMENT_FILE", fileId);
    }
    audit.record("DOCUMENT_CREATED", "DOCUMENT", id);
    return get(id);
  }

  @Transactional
  public Post update(String id, Change change) {
    requireOwner(get(id).authorId());
    if (db.sql(
                "UPDATE document_posts SET title=?,description=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND version=?")
            .params(
                change.title().trim(), change.description(), CurrentUser.id(), id, change.version())
            .update()
        != 1) throw ApiException.conflict();
    audit.record("DOCUMENT_UPDATED", "DOCUMENT", id);
    return get(id);
  }

  @Transactional
  public void delete(String id, long version) {
    requireOwner(get(id).authorId());
    var keys =
        db.sql("SELECT storage_key FROM document_files WHERE post_id=?")
            .param(id)
            .query(String.class)
            .list();
    if (db.sql("DELETE FROM document_posts WHERE id=? AND version=?").params(id, version).update()
        != 1) throw ApiException.conflict();
    audit.record("DOCUMENT_DELETED", "DOCUMENT", id);
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            keys.forEach(storage::delete);
          }
        });
  }

  @Transactional
  public Comment comment(String postId, NewComment change) {
    get(postId);
    String id = UUID.randomUUID().toString();
    db.sql(
            "INSERT INTO document_comments(id,post_id,body,created_by,updated_by) VALUES (?,?,?,?,?)")
        .params(id, postId, change.body().trim(), CurrentUser.id(), CurrentUser.id())
        .update();
    audit.record("DOCUMENT_COMMENT_CREATED", "DOCUMENT_COMMENT", id);
    return getComment(postId, id);
  }

  @Transactional
  public Comment updateComment(String postId, String id, CommentChange change) {
    requireOwner(getComment(postId, id).authorId());
    if (db.sql(
                "UPDATE document_comments SET body=?,updated_by=?,updated_at=CURRENT_TIMESTAMP(6),version=version+1 WHERE id=? AND post_id=? AND version=?")
            .params(change.body().trim(), CurrentUser.id(), id, postId, change.version())
            .update()
        != 1) throw ApiException.conflict();
    audit.record("DOCUMENT_COMMENT_UPDATED", "DOCUMENT_COMMENT", id);
    return getComment(postId, id);
  }

  @Transactional
  public void deleteComment(String postId, String id, long version) {
    requireOwner(getComment(postId, id).authorId());
    if (db.sql("DELETE FROM document_comments WHERE id=? AND post_id=? AND version=?")
            .params(id, postId, version)
            .update()
        != 1) throw ApiException.conflict();
    audit.record("DOCUMENT_COMMENT_DELETED", "DOCUMENT_COMMENT", id);
  }

  private Comment getComment(String postId, String id) {
    return db.sql(COMMENT_SELECT + " WHERE c.id=? AND c.post_id=?")
        .params(id, postId)
        .query(Comment.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  @Transactional
  public Download download(String postId, String fileId) {
    record Row(String filename, String storageKey, long sizeBytes) {}
    var file =
        db.sql(
                "SELECT filename,storage_key,size_bytes FROM document_files WHERE id=? AND post_id=?")
            .params(fileId, postId)
            .query(Row.class)
            .optional()
            .orElseThrow(ApiException::missing);
    Path path = storage.path(file.storageKey());
    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw ApiException.missing();
    audit.record("DOCUMENT_FILE_DOWNLOADED", "DOCUMENT_FILE", fileId);
    return new Download(path, file.filename(), file.sizeBytes());
  }

  private static void requireOwner(String authorId) {
    if (!CurrentUser.id().equals(authorId) && !CurrentUser.principal().role().equals("ADMIN")) {
      throw new AccessDeniedException("Only the author or an administrator can change this item.");
    }
  }

  private static String filename(String original) {
    if (original == null) return "document";
    String name = original.replace('\\', '/');
    name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
    if (name.isBlank() || name.equals(".") || name.equals("..")) return "document";
    return name.substring(0, Math.min(200, name.length()));
  }

  private static String contentType(String value) {
    return value != null
            && value.length() <= 255
            && value.matches("[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+")
        ? value
        : "application/octet-stream";
  }
}
