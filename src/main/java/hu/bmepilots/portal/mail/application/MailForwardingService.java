package hu.bmepilots.portal.mail.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.mail.application.port.MailDelivery;
import hu.bmepilots.portal.mail.application.port.MailProvider;
import hu.bmepilots.portal.mail.infrastructure.AttachmentStorage;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MailForwardingService {
  private final JdbcClient db;
  private final AuditService audit;
  private final MailDelivery sender;
  private final AttachmentStorage storage;
  private final TransactionTemplate tx;
  private final boolean mailEnabled;
  private final String username;
  private final AtomicBoolean running = new AtomicBoolean();

  public MailForwardingService(
      JdbcClient db,
      AuditService audit,
      MailDelivery sender,
      AttachmentStorage storage,
      PlatformTransactionManager manager,
      @Value("${portal.mail.enabled}") boolean mailEnabled,
      @Value("${portal.mail.username}") String username) {
    this.db = db;
    this.audit = audit;
    this.sender = sender;
    this.storage = storage;
    this.tx = new TransactionTemplate(manager);
    this.mailEnabled = mailEnabled;
    this.username = username;
  }

  public record Change(@NotNull Boolean enabled, @NotNull @Min(0) Long version) {}

  public record Counts(
      long pending, long sending, long sent, long failed, long uncertain, long cancelled) {}

  public record Status(
      boolean enabled,
      boolean available,
      LocalDateTime enabledAt,
      long version,
      Counts counts,
      String lastError) {}

  private record Settings(boolean enabled, LocalDateTime enabledAt, long version) {}

  private record Recipient(String id, String email) {}

  private record Pending(
      String id, String messageId, String userId, long enableVersion, int attempts) {}

  private record Member(String email, String status) {}

  private record Content(
      String subject,
      String senderAddress,
      String senderName,
      LocalDateTime receivedAt,
      String bodyText,
      String bodyHtml) {}

  private record StoredAttachment(String filename, String contentType, String storageKey) {}

  private record Claimed(Pending pending, String email) {}

  private Settings settings(boolean lock) {
    return db.sql(
            "SELECT enabled,enabled_at,version FROM mail_forwarding_settings WHERE id=1"
                + (lock ? " FOR UPDATE" : ""))
        .query(Settings.class)
        .single();
  }

  public Status status() {
    var s = settings(false);
    Map<String, Long> counts = new HashMap<>();
    db.sql("SELECT state,COUNT(*) total FROM mail_forwarding_outbox GROUP BY state")
        .query(
            (rs, n) -> {
              counts.put(rs.getString("state"), rs.getLong("total"));
              return 0;
            })
        .list();
    String error =
        db.sql(
                "SELECT error_code FROM mail_forwarding_outbox WHERE error_code IS NOT NULL ORDER BY updated_at DESC,id DESC LIMIT 1")
            .query(String.class)
            .optional()
            .orElse(null);
    return new Status(
        s.enabled(),
        available(),
        s.enabledAt(),
        s.version(),
        new Counts(
            counts.getOrDefault("PENDING", 0L),
            counts.getOrDefault("SENDING", 0L),
            counts.getOrDefault("SENT", 0L),
            counts.getOrDefault("FAILED", 0L),
            counts.getOrDefault("UNCERTAIN", 0L),
            counts.getOrDefault("CANCELLED", 0L)),
        error);
  }

  private boolean available() {
    return mailEnabled && sender.available();
  }

  @Transactional
  public Status update(Change change) {
    var s = settings(true);
    if (s.version() != change.version()) throw ApiException.conflict();
    if (change.enabled() && !available())
      throw new ApiException(
          HttpStatus.CONFLICT,
          "MAIL_FORWARDING_UNAVAILABLE",
          "Email forwarding requires the configured Gmail connection.");
    if (s.enabled() != change.enabled()) {
      db.sql(
              "UPDATE mail_forwarding_settings SET enabled=?,enabled_at=CASE WHEN ? THEN CURRENT_TIMESTAMP(6) ELSE NULL END,version=version+1 WHERE id=1")
          .params(change.enabled(), change.enabled())
          .update();
      if (!change.enabled()) {
        db.sql(
                "UPDATE mail_forwarding_outbox SET state='CANCELLED',error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE state='PENDING'")
            .update();
      }
      audit.record(
          change.enabled() ? "MAIL_FORWARDING_ENABLED" : "MAIL_FORWARDING_DISABLED",
          "MAIL_FORWARDING",
          "1");
    }
    return status();
  }

  /** Called within the transaction which imports a brand-new message. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void enqueue(String messageId, MailProvider.Message message) {
    if (!mailEnabled || !message.forwardable()) return;
    var s = settings(true);
    if (!s.enabled()
        || s.enabledAt() == null
        || LocalDateTime.ofInstant(message.receivedAt(), ZoneOffset.UTC).isBefore(s.enabledAt()))
      return;
    var recipients =
        db.sql("SELECT id,email FROM users WHERE status='ACTIVE'").query(Recipient.class).list();
    int queued = 0;
    for (var recipient : recipients) {
      if (sameMailbox(recipient.email(), username)) continue;
      queued +=
          db.sql(
                  "INSERT IGNORE INTO mail_forwarding_outbox(id,message_id,user_id,enable_version) VALUES (?,?,?,?)")
              .params(UUID.randomUUID().toString(), messageId, recipient.id(), s.version())
              .update();
    }
    if (queued > 0) audit.recordAs(null, "MAIL_FORWARDING_QUEUED", "MAIL", messageId);
  }

  /** Gmail aliases of the configured mailbox must never become forwarding recipients. */
  public static boolean sameMailbox(String first, String second) {
    return canonicalMailbox(first).equals(canonicalMailbox(second));
  }

  private static String canonicalMailbox(String value) {
    String email = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    int at = email.lastIndexOf('@');
    if (at > 0
        && (email.substring(at + 1).equals("gmail.com")
            || email.substring(at + 1).equals("googlemail.com"))) {
      String local = email.substring(0, at).split("\\+", 2)[0].replace(".", "");
      return local + "@gmail.com";
    }
    return email;
  }

  @PostConstruct
  public void recoverInterrupted() {
    tx.executeWithoutResult(
        s -> {
          int recovered =
              db.sql(
                      "UPDATE mail_forwarding_outbox SET state='UNCERTAIN',error_code='DELIVERY_INTERRUPTED',updated_at=CURRENT_TIMESTAMP(6) WHERE state='SENDING'")
                  .update();
          if (recovered > 0)
            audit.recordAs(null, "MAIL_FORWARDING_INTERRUPTED", "MAIL_FORWARDING", "1");
        });
  }

  /** Single backend instance: at most one SMTP attempt every five seconds, including retries. */
  @Scheduled(
      fixedDelay = 5000,
      initialDelayString = "${portal.mail.forwarding.initial-delay-ms:20000}")
  public void dispatch() {
    if (!available() || !running.compareAndSet(false, true)) return;
    try {
      Claimed claimed = tx.execute(s -> claim());
      if (claimed == null) return;
      MailDelivery.Result result;
      try {
        var p = claimed.pending();
        var content =
            db.sql(
                    "SELECT subject,sender_address,sender_name,received_at,body_text,body_html FROM mail_messages WHERE id=?")
                .param(p.messageId())
                .query(Content.class)
                .single();
        var attachments =
            db
                .sql(
                    "SELECT filename,content_type,storage_key FROM mail_attachments WHERE message_id=? ORDER BY id")
                .param(p.messageId())
                .query(StoredAttachment.class)
                .list()
                .stream()
                .map(
                    a ->
                        new MailDelivery.Attachment(
                            a.filename(), a.contentType(), storage.resolve(a.storageKey())))
                .toList();
        String replyTo =
            db.sql(
                    "SELECT address FROM mail_message_addresses WHERE message_id=? AND address_type='REPLY_TO' ORDER BY id LIMIT 1")
                .param(p.messageId())
                .query(String.class)
                .optional()
                .orElse(content.senderAddress());
        result =
            sender.deliver(
                new MailDelivery.Delivery(
                    p.id(),
                    claimed.email(),
                    content.subject(),
                    content.senderAddress(),
                    content.senderName(),
                    content.receivedAt(),
                    content.bodyText(),
                    content.bodyHtml(),
                    attachments,
                    replyTo));
      } catch (Exception e) {
        // The adapter contract handles pre-send errors. Unexpected failures may follow acceptance.
        result =
            new MailDelivery.Result(MailDelivery.Outcome.UNCERTAIN, "DELIVERY_OUTCOME_UNKNOWN");
      }
      var completed = result;
      tx.executeWithoutResult(s -> complete(claimed.pending(), completed));
    } finally {
      running.set(false);
    }
  }

  private Claimed claim() {
    if (!settings(true).enabled()) return null;
    var pending =
        db.sql(
                "SELECT id,message_id,user_id,enable_version,attempts FROM mail_forwarding_outbox WHERE state='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(6) ORDER BY next_attempt_at,created_at,id LIMIT 1 FOR UPDATE")
            .query(Pending.class)
            .optional();
    if (pending.isEmpty()) return null;
    var p = pending.get();
    var member =
        db.sql("SELECT email,status FROM users WHERE id=?")
            .param(p.userId())
            .query(Member.class)
            .single();
    if (!member.status().equals("ACTIVE") || sameMailbox(member.email(), username)) {
      db.sql(
              "UPDATE mail_forwarding_outbox SET state='CANCELLED',updated_at=CURRENT_TIMESTAMP(6) WHERE id=?")
          .param(p.id())
          .update();
      audit.recordAs(null, "MAIL_FORWARDING_CANCELLED", "MAIL_DELIVERY", p.id());
      return null;
    }
    db.sql(
            "UPDATE mail_forwarding_outbox SET state='SENDING',attempts=attempts+1,error_code=NULL,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?")
        .param(p.id())
        .update();
    audit.recordAs(null, "MAIL_FORWARDING_ATTEMPTED", "MAIL_DELIVERY", p.id());
    return new Claimed(p, member.email());
  }

  private void complete(Pending pending, MailDelivery.Result result) {
    // Serialize with disable so a known pre-send failure cannot recreate a cancelled backlog.
    var settings = settings(true);
    boolean enabled = settings.enabled() && settings.version() == pending.enableVersion();
    String state = result.outcome().name();
    if (result.outcome() == MailDelivery.Outcome.RETRYABLE)
      state = !enabled ? "CANCELLED" : pending.attempts() >= 2 ? "FAILED" : "PENDING";
    long delay = pending.attempts() == 0 ? 60 : 300;
    db.sql(
            "UPDATE mail_forwarding_outbox SET state=?,error_code=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(6)),updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND state='SENDING'")
        .params(state, result.errorCode(), delay, pending.id())
        .update();
    audit.recordAs(
        null,
        "MAIL_FORWARDING_" + (state.equals("PENDING") ? "RETRY_SCHEDULED" : state),
        "MAIL_DELIVERY",
        pending.id());
  }
}
