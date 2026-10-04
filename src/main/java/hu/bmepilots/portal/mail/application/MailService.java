package hu.bmepilots.portal.mail.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.mail.infrastructure.AttachmentStorage;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MailService {
  private final JdbcClient db;
  private final AttachmentStorage storage;
  private final AuditService audit;

  public MailService(JdbcClient db, AttachmentStorage storage, AuditService audit) {
    this.db = db;
    this.storage = storage;
    this.audit = audit;
  }

  public record Summary(
      String id,
      String subject,
      String senderAddress,
      String senderName,
      LocalDateTime receivedAt,
      boolean isRead,
      boolean isImportant,
      long attachmentCount) {}

  public record Attachment(String id, String filename, String contentType, long sizeBytes) {}

  public record Address(String addressType, String address, String displayName) {}

  public record Body(
      String id,
      String subject,
      String senderAddress,
      String senderName,
      LocalDateTime receivedAt,
      String bodyText,
      String bodyHtml) {}

  public record Detail(Body message, List<Address> addresses, List<Attachment> attachments) {}

  public record State(Boolean isRead, Boolean isImportant) {}

  public record Download(Path path, String filename) {}

  public List<Summary> list(String user, String query, boolean unread, int page) {
    String q = query == null ? "" : query.trim();
    if (q.length() > 100) throw new IllegalArgumentException();
    // '=' is the LIKE escape; '%' and '_' in user input remain literal.
    String search = "%" + q.replace("=", "==").replace("%", "=%").replace("_", "=_") + "%";
    return db.sql(
            "SELECT m.id,m.subject,m.sender_address,m.sender_name,m.received_at,COALESCE(s.is_read,FALSE) is_read,COALESCE(s.is_important,FALSE) is_important,(SELECT COUNT(*) FROM mail_attachments a WHERE a.message_id=m.id) attachment_count FROM mail_messages m LEFT JOIN user_mail_state s ON s.message_id=m.id AND s.user_id=:user WHERE (:unread=FALSE OR COALESCE(s.is_read,FALSE)=FALSE) AND (m.subject LIKE :q ESCAPE '=' OR m.sender_address LIKE :q ESCAPE '=' OR m.body_text LIKE :q ESCAPE '=') ORDER BY m.received_at DESC,m.id DESC LIMIT 30 OFFSET :offset")
        .param("user", user)
        .param("unread", unread)
        .param("q", search)
        .param("offset", Math.max(0, Math.min(page, 10000)) * 30)
        .query(Summary.class)
        .list();
  }

  public long unread(String user) {
    return db.sql(
            "SELECT COUNT(*) FROM mail_messages m LEFT JOIN user_mail_state s ON s.message_id=m.id AND s.user_id=? WHERE COALESCE(s.is_read,FALSE)=FALSE")
        .param(user)
        .query(Long.class)
        .single();
  }

  public Detail get(String id) {
    var body =
        db.sql(
                "SELECT id,subject,sender_address,sender_name,received_at,body_text,body_html FROM mail_messages WHERE id=?")
            .param(id)
            .query(Body.class)
            .optional()
            .orElseThrow(ApiException::missing);
    var addresses =
        db.sql(
                "SELECT address_type,address,display_name FROM mail_message_addresses WHERE message_id=? ORDER BY id")
            .param(id)
            .query(Address.class)
            .list();
    var attachments =
        db.sql(
                "SELECT id,filename,content_type,size_bytes FROM mail_attachments WHERE message_id=?")
            .param(id)
            .query(Attachment.class)
            .list();
    return new Detail(body, addresses, attachments);
  }

  @Transactional
  public void state(String user, String id, State state) {
    if (state.isRead() == null && state.isImportant() == null) throw new IllegalArgumentException();
    if (db.sql("SELECT COUNT(*) FROM mail_messages WHERE id=?").param(id).query(Long.class).single()
        == 0) throw ApiException.missing();
    db.sql(
            "INSERT INTO user_mail_state(user_id,message_id,is_read,is_important) VALUES (?,?,COALESCE(?,FALSE),COALESCE(?,FALSE)) ON DUPLICATE KEY UPDATE is_read=COALESCE(?,is_read),is_important=COALESCE(?,is_important)")
        .params(user, id, state.isRead(), state.isImportant(), state.isRead(), state.isImportant())
        .update();
    audit.record("MAIL_STATE_CHANGED", "MAIL", id);
  }

  private record Stored(String storageKey, String filename) {}

  public Download download(String messageId, String attachmentId) {
    var file =
        db.sql("SELECT storage_key,filename FROM mail_attachments WHERE id=? AND message_id=?")
            .params(attachmentId, messageId)
            .query(Stored.class)
            .optional()
            .orElseThrow(ApiException::missing);
    Path p = storage.resolve(file.storageKey());
    if (!Files.isRegularFile(p)) throw ApiException.missing();
    audit.record("MAIL_ATTACHMENT_DOWNLOADED", "MAIL_ATTACHMENT", attachmentId);
    return new Download(p, file.filename());
  }
}
