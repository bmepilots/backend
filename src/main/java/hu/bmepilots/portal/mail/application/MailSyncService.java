package hu.bmepilots.portal.mail.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.mail.application.port.MailProvider;
import hu.bmepilots.portal.mail.infrastructure.*;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MailSyncService {
  private final JdbcClient db;
  private final MailProvider provider;
  private final AttachmentStorage storage;
  private final EmailSanitizer sanitizer;
  private final AuditService audit;
  private final TransactionTemplate tx;
  private final boolean enabled;
  private final String username;
  private final AtomicBoolean running = new AtomicBoolean();
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  public MailSyncService(
      JdbcClient db,
      MailProvider provider,
      AttachmentStorage storage,
      EmailSanitizer sanitizer,
      AuditService audit,
      PlatformTransactionManager manager,
      @Value("${portal.mail.enabled}") boolean enabled,
      @Value("${portal.mail.username}") String username) {
    this.db = db;
    this.provider = provider;
    this.storage = storage;
    this.sanitizer = sanitizer;
    this.audit = audit;
    this.tx = new TransactionTemplate(manager);
    this.enabled = enabled;
    this.username = username;
  }

  public record Account(
      String id,
      String emailAddress,
      LocalDateTime lastAttemptAt,
      LocalDateTime lastSuccessAt,
      String lastError) {}

  public record Status(
      boolean enabled, boolean running, List<Account> accounts, long failedMessages) {}

  private record Folder(String id, long uidValidity, long lastUid) {}

  public Status status() {
    return new Status(
        enabled,
        running.get(),
        db.sql(
                "SELECT id,email_address,last_attempt_at,last_success_at,last_error FROM mail_accounts")
            .query(Account.class)
            .list(),
        db.sql("SELECT COUNT(*) FROM mail_sync_failures").query(Long.class).single());
  }

  public void requestSync() {
    if (!enabled)
      throw new ApiException(
          HttpStatus.CONFLICT,
          "MAIL_DISABLED",
          "The Gmail connection has not been configured yet.");
    if (!running.compareAndSet(false, true))
      throw new ApiException(
          HttpStatus.CONFLICT, "SYNC_RUNNING", "A synchronization is already running.");
    audit.record("MAIL_SYNC_REQUESTED", "MAIL", "INBOX");
    executor.submit(this::run);
  }

  public void test() {
    if (!enabled)
      throw new ApiException(
          HttpStatus.CONFLICT,
          "MAIL_DISABLED",
          "The Gmail connection has not been configured yet.");
    try {
      provider.testConnection();
    } catch (Exception e) {
      throw new ApiException(
          HttpStatus.BAD_GATEWAY,
          "MAIL_CONNECTION_FAILED",
          "Could not connect to Gmail. Please check the server configuration.");
    }
  }

  @Scheduled(fixedDelayString = "${portal.mail.interval-ms}", initialDelay = 15000)
  public void scheduled() {
    if (enabled && running.compareAndSet(false, true)) executor.submit(this::run);
  }

  private void run() {
    String account = null;
    try {
      String candidate = UUID.randomUUID().toString();
      db.sql(
              "INSERT IGNORE INTO mail_accounts(id,email_address,provider_type) VALUES (?,?,'GMAIL_IMAP')")
          .params(candidate, username)
          .update();
      account =
          db.sql("SELECT id FROM mail_accounts WHERE email_address=?")
              .param(username)
              .query(String.class)
              .single();
      db.sql("UPDATE mail_accounts SET last_attempt_at=CURRENT_TIMESTAMP(6) WHERE id=?")
          .param(account)
          .update();
      db.sql("INSERT IGNORE INTO mail_folders(id,account_id,external_key) VALUES (?,?,'INBOX')")
          .params(UUID.randomUUID().toString(), account)
          .update();
      var folder =
          db.sql(
                  "SELECT id,uid_validity,last_uid FROM mail_folders WHERE account_id=? AND external_key='INBOX'")
              .param(account)
              .query(Folder.class)
              .single();
      var retry =
          db.sql(
                  "SELECT imap_uid FROM mail_sync_failures WHERE folder_id=? AND uid_validity=? AND attempts<3 ORDER BY imap_uid LIMIT 2")
              .params(folder.id(), folder.uidValidity())
              .query(Long.class)
              .list();
      var batch = provider.fetch(folder.uidValidity(), folder.lastUid(), retry);
      for (var message : batch.messages()) save(account, folder.id(), batch.uidValidity(), message);
      tx.executeWithoutResult(
          s -> {
            for (var failure : batch.failures())
              db.sql(
                      "INSERT INTO mail_sync_failures(folder_id,uid_validity,imap_uid,error_code,attempts) VALUES (?,?,?,?,1) ON DUPLICATE KEY UPDATE error_code=VALUES(error_code),attempts=attempts+1")
                  .params(folder.id(), batch.uidValidity(), failure.uid(), failure.code())
                  .update();
            db.sql("UPDATE mail_folders SET uid_validity=?,last_uid=? WHERE id=?")
                .params(batch.uidValidity(), batch.cursor(), folder.id())
                .update();
          });
      db.sql(
              "UPDATE mail_accounts SET last_success_at=CURRENT_TIMESTAMP(6),last_error=NULL WHERE id=?")
          .param(account)
          .update();
    } catch (Exception e) {
      if (account != null)
        db.sql("UPDATE mail_accounts SET last_error='SYNC_FAILED' WHERE id=?")
            .param(account)
            .update();
    } finally {
      running.set(false);
    }
  }

  private void save(String account, String folder, long validity, MailProvider.Message m)
      throws Exception {
    if (db.sql(
                "SELECT COUNT(*) FROM mail_message_locations WHERE folder_id=? AND uid_validity=? AND imap_uid=?")
            .params(folder, validity, m.uid())
            .query(Long.class)
            .single()
        > 0) return;
    Optional<String> existing =
        m.providerId() == null
            ? Optional.empty()
            : db.sql("SELECT id FROM mail_messages WHERE account_id=? AND provider_message_id=?")
                .params(account, m.providerId())
                .query(String.class)
                .optional();
    String id = existing.orElseGet(() -> UUID.randomUUID().toString());
    var keys = new ArrayList<String>();
    try {
      if (existing.isEmpty()) for (var a : m.attachments()) keys.add(storage.save(a.bytes()));
      tx.executeWithoutResult(
          s -> {
            if (existing.isEmpty()) {
              String html = sanitizer.sanitize(m.html());
              String text = m.text().isBlank() ? org.jsoup.Jsoup.parse(html).text() : m.text();
              db.sql(
                      "INSERT INTO mail_messages(id,account_id,provider_message_id,message_id_header,subject,sender_address,sender_name,received_at,body_text,body_html) VALUES (?,?,?,?,?,?,?,?,?,?)")
                  .params(
                      id,
                      account,
                      m.providerId(),
                      m.messageId(),
                      m.subject(),
                      m.senderAddress(),
                      m.senderName(),
                      LocalDateTime.ofInstant(m.receivedAt(), ZoneOffset.UTC),
                      text,
                      html)
                  .update();
              for (var a : m.addresses())
                db.sql(
                        "INSERT INTO mail_message_addresses(message_id,address_type,address,display_name) VALUES (?,?,?,?)")
                    .params(id, a.type(), a.address(), a.name())
                    .update();
              for (int i = 0; i < m.attachments().size(); i++) {
                var a = m.attachments().get(i);
                db.sql(
                        "INSERT INTO mail_attachments(id,message_id,filename,content_type,size_bytes,storage_key) VALUES (?,?,?,?,?,?)")
                    .params(
                        UUID.randomUUID().toString(),
                        id,
                        a.filename(),
                        a.contentType(),
                        a.bytes().length,
                        keys.get(i))
                    .update();
              }
            }
            db.sql(
                    "INSERT INTO mail_message_locations(folder_id,uid_validity,imap_uid,message_id) VALUES (?,?,?,?)")
                .params(folder, validity, m.uid(), id)
                .update();
            db.sql(
                    "DELETE FROM mail_sync_failures WHERE folder_id=? AND uid_validity=? AND imap_uid=?")
                .params(folder, validity, m.uid())
                .update();
          });
    } catch (Exception e) {
      keys.forEach(storage::discard);
      throw e;
    }
  }

  public void retryFailures() {
    db.sql("UPDATE mail_sync_failures SET attempts=0").update();
    audit.record("MAIL_RETRY_REQUESTED", "MAIL", "INBOX");
  }

  @PreDestroy
  public void close() {
    executor.shutdownNow();
  }
}
