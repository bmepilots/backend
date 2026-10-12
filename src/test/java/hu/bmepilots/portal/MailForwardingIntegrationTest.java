package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import hu.bmepilots.portal.mail.application.MailForwardingService;
import hu.bmepilots.portal.mail.application.MailSyncService;
import hu.bmepilots.portal.mail.application.port.MailDelivery;
import hu.bmepilots.portal.mail.application.port.MailProvider;
import hu.bmepilots.portal.mail.infrastructure.AttachmentStorage;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real MariaDB/HTTP verification; every external mail boundary is a local mock. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:mariadb://127.0.0.1:3308/bmepilots_test",
      "spring.datasource.username=bmepilots_test",
      "spring.datasource.password=${TEST_DB_PASSWORD}",
      "portal.bootstrap.email=integration-admin@example.test",
      "portal.bootstrap.password=Integration-admin-password-2026",
      "portal.mail.enabled=true",
      "portal.mail.username=forwarding-shared@example.test",
      "portal.mail.password-file=",
      "portal.mail.interval-ms=3600000",
      "portal.mail.forwarding.initial-delay-ms=3600000",
      "portal.mail.storage=./target/forwarding-test-attachments"
    })
class MailForwardingIntegrationTest {
  private static final String PASSWORD = "Forwarding-fixture-password-2026";
  private static final String SHARED = "forwarding-shared@example.test";
  private static final String PATH = "/admin/mail/forwarding";
  private static String passwordHash;

  @Value("${local.server.port}")
  int port;

  @Autowired JdbcClient db;
  @Autowired ObjectMapper json;
  @Autowired PasswordEncoder encoder;
  @Autowired MailForwardingService forwarding;
  @Autowired PlatformTransactionManager manager;
  @Autowired AttachmentStorage storage;
  @Autowired hu.bmepilots.portal.audit.application.AuditService audit;
  @Autowired hu.bmepilots.portal.mail.infrastructure.EmailSanitizer sanitizer;
  @MockitoBean MailDelivery delivery;
  @MockitoBean MailProvider provider;

  private final List<String> users = new ArrayList<>();
  private final List<String> messages = new ArrayList<>();
  private final List<String> storedFiles = new ArrayList<>();
  private String accountId;
  private String memberId;
  private Client admin;
  private TransactionTemplate tx;

  class Client {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;

    HttpResponse<String> send(String method, String path, Object body, boolean csrf)
        throws Exception {
      if (csrf && token == null) refresh();
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
      if (csrf) request.header("X-CSRF-TOKEN", token);
      if (body != null) request.header("Content-Type", "application/json");
      return http.send(
          request
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String method, String path, Object body, int expected) throws Exception {
      var response = send(method, path, body, !method.equals("GET"));
      assertThat(response.statusCode()).as(response.body()).isEqualTo(expected);
      return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    void refresh() throws Exception {
      token = ok("GET", "/auth/csrf", null, 200).get("token").asText();
    }

    void login(String id) throws Exception {
      ok("POST", "/auth/login", Map.of("email", email(id), "password", PASSWORD), 200);
      refresh();
    }
  }

  @BeforeEach
  void setup() throws Exception {
    when(delivery.available()).thenReturn(true);
    when(delivery.deliver(any()))
        .thenReturn(new MailDelivery.Result(MailDelivery.Outcome.SENT, null));
    when(provider.fetch(anyLong(), anyLong(), anyList()))
        .thenReturn(new MailProvider.Batch(1, 0, List.of(), List.of()));
    db.sql("UPDATE mail_forwarding_settings SET enabled=FALSE,enabled_at=NULL WHERE id=1").update();
    if (passwordHash == null) passwordHash = encoder.encode(PASSWORD);
    tx = new TransactionTemplate(manager);
    String adminId = user("ACTIVE", "ADMIN", null);
    memberId = user("ACTIVE", "USER", null);
    admin = new Client();
    admin.login(adminId);
    accountId = UUID.randomUUID().toString();
    db.sql("INSERT INTO mail_accounts(id,email_address,provider_type) VALUES (?,?,'FIXTURE')")
        .params(accountId, accountId + "@example.test")
        .update();
  }

  @AfterEach
  void cleanup() {
    db.sql("UPDATE mail_forwarding_settings SET enabled=FALSE,enabled_at=NULL WHERE id=1").update();
    for (String id : messages) {
      db.sql("DELETE FROM mail_forwarding_outbox WHERE message_id=?").param(id).update();
      db.sql("DELETE FROM mail_attachments WHERE message_id=?").param(id).update();
      db.sql("DELETE FROM mail_message_addresses WHERE message_id=?").param(id).update();
      db.sql("DELETE FROM mail_message_locations WHERE message_id=?").param(id).update();
      db.sql("DELETE FROM mail_messages WHERE id=?").param(id).update();
    }
    for (String key : storedFiles) storage.discard(key);
    db.sql(
            "DELETE FROM mail_sync_failures WHERE folder_id IN (SELECT id FROM mail_folders WHERE account_id=?)")
        .param(accountId)
        .update();
    db.sql("DELETE FROM mail_folders WHERE account_id=?").param(accountId).update();
    db.sql("DELETE FROM mail_accounts WHERE id=?").param(accountId).update();
    // Keep audit actor references, but do not interfere with other suites' last-admin guard.
    for (String id : users) {
      db.sql(
              "UPDATE users SET status='DISABLED',auth_version=auth_version+1,email=?,email_normalized=? WHERE id=?")
          .params(id + "@retired.example.test", id + "@retired.example.test", id)
          .update();
    }
  }

  private String user(String status, String role, String address) {
    String id = UUID.randomUUID().toString();
    String email = address == null ? email(id) : address;
    db.sql(
            "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,?)")
        .params(
            id, email, email.toLowerCase(Locale.ROOT), "Forwarding fixture", passwordHash, status)
        .update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,?)").params(id, role).update();
    users.add(id);
    return id;
  }

  private static String email(String id) {
    return id + "@example.test";
  }

  private JsonNode toggle(boolean enabled) throws Exception {
    long version = admin.ok("GET", PATH, null, 200).get("version").asLong();
    return admin.ok("PATCH", PATH, Map.of("enabled", enabled, "version", version), 200);
  }

  private MailProvider.Message incoming(Instant receivedAt, String sender, boolean forwardable) {
    return new MailProvider.Message(
        1,
        UUID.randomUUID().toString(),
        "<original@example.test>",
        "Private fixture briefing",
        sender,
        "Original sender",
        receivedAt,
        "Private original content",
        "<p>Private original content</p>",
        List.of(),
        List.of(),
        forwardable);
  }

  private String importMessage(MailProvider.Message message) {
    return tx.execute(
        status -> {
          String id = UUID.randomUUID().toString();
          messages.add(id);
          db.sql(
                  "INSERT INTO mail_messages(id,account_id,provider_message_id,subject,sender_address,sender_name,received_at,body_text,body_html) VALUES (?,?,?,?,?,?,?,?,?)")
              .params(
                  id,
                  accountId,
                  message.providerId(),
                  message.subject(),
                  message.senderAddress(),
                  message.senderName(),
                  LocalDateTime.ofInstant(message.receivedAt(), ZoneOffset.UTC),
                  message.text(),
                  message.html())
              .update();
          forwarding.enqueue(id, message);
          return id;
        });
  }

  private List<String> recipients(String messageId) {
    return db.sql("SELECT user_id FROM mail_forwarding_outbox WHERE message_id=?")
        .param(messageId)
        .query(String.class)
        .list();
  }

  private String onlyDelivery(String messageId, String userId) {
    db.sql("UPDATE mail_forwarding_outbox SET state='CANCELLED' WHERE message_id=? AND user_id<>?")
        .params(messageId, userId)
        .update();
    return db.sql("SELECT id FROM mail_forwarding_outbox WHERE message_id=? AND user_id=?")
        .params(messageId, userId)
        .query(String.class)
        .single();
  }

  private String state(String id) {
    return db.sql("SELECT state FROM mail_forwarding_outbox WHERE id=?")
        .param(id)
        .query(String.class)
        .single();
  }

  @Test
  void administratorToggleRequiresRoleCsrfValidVersionAndAvailableTransport() throws Exception {
    JsonNode initial = admin.ok("GET", PATH, null, 200);
    assertThat(initial.get("enabled").asBoolean()).isFalse();
    assertThat(initial.get("available").asBoolean()).isTrue();
    assertThat(initial.get("enabledAt").isNull()).isTrue();
    assertThat(
            db.sql(
                    "SELECT COLUMN_DEFAULT FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='mail_forwarding_settings' AND COLUMN_NAME='enabled'")
                .query(String.class)
                .single())
        .isIn("0", "FALSE", "false");
    Map<String, Object> change =
        Map.of("enabled", true, "version", initial.get("version").asLong());
    new Client().ok("GET", PATH, null, 401);
    new Client().ok("PATCH", PATH, change, 401);
    Client member = new Client();
    member.login(memberId);
    member.ok("GET", PATH, null, 403);
    member.ok("PATCH", PATH, change, 403);
    assertThat(admin.send("PATCH", PATH, change, false).statusCode()).isEqualTo(403);
    for (var invalid :
        List.of(
            Map.of("enabled", true),
            Map.of("version", 0),
            Map.of("enabled", true, "version", -1))) {
      admin.ok("PATCH", PATH, invalid, 400);
    }
    when(delivery.available()).thenReturn(false);
    assertThat(admin.ok("PATCH", PATH, change, 409).get("code").asText())
        .isEqualTo("MAIL_FORWARDING_UNAVAILABLE");
    when(delivery.available()).thenReturn(true);
    var enabled = admin.ok("PATCH", PATH, change, 200);
    assertThat(enabled.get("enabled").asBoolean()).isTrue();
    assertThat(enabled.get("enabledAt").isTextual()).isTrue();
    assertThat(enabled.get("version").asLong()).isEqualTo(initial.get("version").asLong() + 1);
    admin.ok("PATCH", PATH, change, 409);
    assertThat(toggle(false).get("enabled").asBoolean()).isFalse();
    assertThat(admin.ok("GET", "/admin/audit-log", null, 200).toString())
        .contains("MAIL_FORWARDING_ENABLED", "MAIL_FORWARDING_DISABLED")
        .doesNotContain(PASSWORD);
    verify(delivery, never()).deliver(any());
  }

  @Test
  void onlyFutureIncomingMailTargetsCurrentActiveMembersIncludingNewApprovals() throws Exception {
    String pending = user("PENDING_APPROVAL", "USER", null);
    String suspended = user("SUSPENDED", "USER", null);
    String disabled = user("DISABLED", "USER", null);
    String rejected = user("REJECTED", "USER", null);
    String shared = user("ACTIVE", "USER", SHARED);
    assertThat(recipients(importMessage(incoming(Instant.now(), "sender@example.test", true))))
        .isEmpty();
    toggle(true);
    assertThat(
            recipients(
                importMessage(
                    incoming(Instant.now().minusSeconds(600), "sender@example.test", true))))
        .isEmpty();
    assertThat(
            recipients(
                importMessage(incoming(Instant.now(), SHARED.toUpperCase(Locale.ROOT), true))))
        .isEmpty();
    assertThat(recipients(importMessage(incoming(Instant.now(), "sender@example.test", false))))
        .isEmpty();
    String first = importMessage(incoming(Instant.now(), "sender@example.test", true));
    assertThat(recipients(first))
        .contains(memberId)
        .doesNotContain(pending, suspended, disabled, rejected, shared);
    admin.ok("POST", "/admin/registrations/" + pending + "/approve", Map.of("version", 0), 200);
    String newcomer = user("ACTIVE", "USER", null);
    String second = importMessage(incoming(Instant.now(), "sender@example.test", true));
    assertThat(recipients(second))
        .contains(memberId, pending, newcomer)
        .doesNotContain(suspended, disabled, rejected, shared);
    assertThat(recipients(first)).doesNotContain(pending, newcomer);
    toggle(false);
    Instant whileDisabled = Instant.now();
    toggle(true);
    assertThat(recipients(importMessage(incoming(whileDisabled, "sender@example.test", true))))
        .isEmpty();
  }

  @Test
  void outboxAndImportCommitTogetherAndRepeatedEnqueueDoesNotDuplicateRecipients()
      throws Exception {
    toggle(true);
    var message = incoming(Instant.now(), "sender@example.test", true);
    String id = importMessage(message);
    int count = recipients(id).size();
    tx.executeWithoutResult(status -> forwarding.enqueue(id, message));
    assertThat(recipients(id)).hasSize(count);
    long queuedAudits =
        db.sql(
                "SELECT COUNT(*) FROM audit_log WHERE action='MAIL_FORWARDING_QUEUED' AND entity_id=?")
            .param(id)
            .query(Long.class)
            .single();
    assertThat(queuedAudits).isEqualTo(1);
    long before = db.sql("SELECT COUNT(*) FROM mail_forwarding_outbox").query(Long.class).single();
    assertThatThrownBy(
            () ->
                tx.executeWithoutResult(
                    status -> {
                      importMessage(incoming(Instant.now(), "sender@example.test", true));
                      throw new IllegalStateException("Simulated import rollback");
                    }))
        .isInstanceOf(IllegalStateException.class);
    assertThat(db.sql("SELECT COUNT(*) FROM mail_forwarding_outbox").query(Long.class).single())
        .isEqualTo(before);
    assertThatThrownBy(() -> forwarding.enqueue(id, message))
        .isInstanceOf(org.springframework.transaction.IllegalTransactionStateException.class);
  }

  @Test
  void syncImportQueuesOnceAndProviderReplayDoesNotBackfillNewMembers() throws Exception {
    toggle(true);
    var original = incoming(Instant.now(), "sender@example.test", true);
    class FixtureProvider implements MailProvider {
      Batch next = new Batch(101, 1, List.of(original), List.of());

      public void testConnection() {}

      public Batch fetch(long validity, long cursor, List<Long> retries) {
        return next;
      }
    }
    var fixture = new FixtureProvider();
    var sync =
        new MailSyncService(
            db,
            fixture,
            storage,
            sanitizer,
            audit,
            forwarding,
            manager,
            true,
            accountId + "@example.test");
    try {
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      String id =
          db.sql("SELECT id FROM mail_messages WHERE account_id=?")
              .param(accountId)
              .query(String.class)
              .single();
      messages.add(id);
      assertThat(recipients(id)).contains(memberId);
      int count = recipients(id).size();
      String newcomer = user("ACTIVE", "USER", null);
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      fixture.next = new MailProvider.Batch(202, 1, List.of(original), List.of());
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      assertThat(recipients(id)).hasSize(count).doesNotContain(newcomer);
      assertThat(
              db.sql("SELECT COUNT(*) FROM mail_messages WHERE account_id=?")
                  .param(accountId)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(
              db.sql("SELECT COUNT(*) FROM mail_message_locations WHERE message_id=?")
                  .param(id)
                  .query(Long.class)
                  .single())
          .isEqualTo(2);
    } finally {
      sync.close();
    }
  }

  @Test
  void deliveryContainsOnlyOneCurrentRecipientOriginalContentAndExactStoredAttachment()
      throws Exception {
    toggle(true);
    String id = importMessage(incoming(Instant.now(), "sender@example.test", true));
    String outboxId = onlyDelivery(id, memberId);
    String key = storage.save("Checklist bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    storedFiles.add(key);
    db.sql(
            "INSERT INTO mail_attachments(id,message_id,filename,content_type,size_bytes,storage_key) VALUES (?,?,?,?,?,?)")
        .params(UUID.randomUUID().toString(), id, "checklist.txt", "text/plain", 15, key)
        .update();
    forwarding.dispatch();
    assertThat(state(outboxId)).isEqualTo("SENT");
    ArgumentCaptor<MailDelivery.Delivery> sent =
        ArgumentCaptor.forClass(MailDelivery.Delivery.class);
    verify(delivery).deliver(sent.capture());
    assertThat(sent.getValue().recipient()).isEqualTo(email(memberId));
    assertThat(sent.getValue().subject()).isEqualTo("Private fixture briefing");
    assertThat(sent.getValue().senderAddress()).isEqualTo("sender@example.test");
    assertThat(sent.getValue().text()).isEqualTo("Private original content");
    assertThat(sent.getValue().html()).isEqualTo("<p>Private original content</p>");
    assertThat(sent.getValue().attachments()).hasSize(1);
    assertThat(java.nio.file.Files.readString(sent.getValue().attachments().getFirst().path()))
        .isEqualTo("Checklist bytes");
    forwarding.dispatch();
    verify(delivery, times(1)).deliver(any());
    assertThat(admin.ok("GET", PATH, null, 200).get("counts").get("sent").asLong())
        .isGreaterThanOrEqualTo(1);
    assertThat(admin.ok("GET", "/admin/audit-log", null, 200).toString())
        .contains("MAIL_FORWARDING_SENT")
        .doesNotContain("Private original content", "Private fixture briefing", email(memberId));
  }

  @Test
  void dispatchRechecksActivationAndDisableCancelsQueuedWorkAcrossReenable() throws Exception {
    toggle(true);
    String id = importMessage(incoming(Instant.now(), "sender@example.test", true));
    String outboxId = onlyDelivery(id, memberId);
    admin.ok("POST", "/admin/users/" + memberId + "/suspend", Map.of("version", 0), 200);
    forwarding.dispatch();
    assertThat(state(outboxId)).isEqualTo("CANCELLED");
    verify(delivery, never()).deliver(any());
    admin.ok("POST", "/admin/users/" + memberId + "/activate", Map.of("version", 1), 200);
    String pending =
        onlyDelivery(importMessage(incoming(Instant.now(), "sender@example.test", true)), memberId);
    toggle(false);
    assertThat(state(pending)).isEqualTo("CANCELLED");
    toggle(true);
    forwarding.dispatch();
    verify(delivery, never()).deliver(any());
  }

  @Test
  void knownPreSendFailuresRetryWithBackoffAndStopAfterThreeAttempts() throws Exception {
    toggle(true);
    String outboxId =
        onlyDelivery(importMessage(incoming(Instant.now(), "sender@example.test", true)), memberId);
    when(delivery.deliver(any()))
        .thenReturn(
            new MailDelivery.Result(MailDelivery.Outcome.RETRYABLE, "SMTP_CONNECTION_FAILED"));
    forwarding.dispatch();
    assertThat(state(outboxId)).isEqualTo("PENDING");
    assertThat(
            db.sql(
                    "SELECT TIMESTAMPDIFF(SECOND,CURRENT_TIMESTAMP(6),next_attempt_at) FROM mail_forwarding_outbox WHERE id=?")
                .param(outboxId)
                .query(Long.class)
                .single())
        .isBetween(50L, 60L);
    forwarding.dispatch();
    verify(delivery, times(1)).deliver(any());
    for (int attempt = 2; attempt <= 3; attempt++) {
      db.sql(
              "UPDATE mail_forwarding_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?")
          .param(outboxId)
          .update();
      forwarding.dispatch();
    }
    assertThat(state(outboxId)).isEqualTo("FAILED");
    assertThat(
            db.sql("SELECT attempts FROM mail_forwarding_outbox WHERE id=?")
                .param(outboxId)
                .query(Integer.class)
                .single())
        .isEqualTo(3);
    forwarding.dispatch();
    verify(delivery, times(3)).deliver(any());
  }

  @Test
  void disableAndReenableDuringAnAttemptCannotResurrectOldQueuedMail() throws Exception {
    toggle(true);
    String outboxId =
        onlyDelivery(importMessage(incoming(Instant.now(), "sender@example.test", true)), memberId);
    when(delivery.deliver(any()))
        .thenAnswer(
            invocation -> {
              toggle(false);
              toggle(true);
              return new MailDelivery.Result(
                  MailDelivery.Outcome.RETRYABLE, "SMTP_CONNECTION_FAILED");
            });
    forwarding.dispatch();
    assertThat(state(outboxId)).isEqualTo("CANCELLED");
    assertThat(forwarding.status().enabled()).isTrue();
    db.sql(
            "UPDATE mail_forwarding_outbox SET next_attempt_at=TIMESTAMPADD(SECOND,-1,CURRENT_TIMESTAMP(6)) WHERE id=?")
        .param(outboxId)
        .update();
    forwarding.dispatch();
    verify(delivery, times(1)).deliver(any());
  }

  @Test
  void uncertainOrInterruptedDeliveriesNeverAutomaticallyResend() throws Exception {
    toggle(true);
    String uncertain =
        onlyDelivery(importMessage(incoming(Instant.now(), "sender@example.test", true)), memberId);
    when(delivery.deliver(any()))
        .thenReturn(
            new MailDelivery.Result(MailDelivery.Outcome.UNCERTAIN, "SMTP_OUTCOME_UNKNOWN"));
    forwarding.dispatch();
    assertThat(state(uncertain)).isEqualTo("UNCERTAIN");
    forwarding.dispatch();
    verify(delivery, times(1)).deliver(any());
    String interrupted =
        onlyDelivery(importMessage(incoming(Instant.now(), "sender@example.test", true)), memberId);
    db.sql("UPDATE mail_forwarding_outbox SET state='SENDING',attempts=1 WHERE id=?")
        .param(interrupted)
        .update();
    forwarding.recoverInterrupted();
    assertThat(state(interrupted)).isEqualTo("UNCERTAIN");
    forwarding.dispatch();
    verify(delivery, times(1)).deliver(any());
    assertThat(admin.ok("GET", PATH, null, 200).get("lastError").asText())
        .isEqualTo("DELIVERY_INTERRUPTED");
  }

  @Test
  void unexpectedSenderExceptionIsContainedWithoutLeakingDetailsOrRetrying() throws Exception {
    toggle(true);
    String outboxId =
        onlyDelivery(importMessage(incoming(Instant.now(), "sender@example.test", true)), memberId);
    when(delivery.deliver(any()))
        .thenThrow(new IllegalStateException("SECRET fixture credential or message"));
    forwarding.dispatch();
    assertThat(state(outboxId)).isEqualTo("UNCERTAIN");
    String status = admin.ok("GET", PATH, null, 200).toString();
    assertThat(status)
        .contains("DELIVERY_OUTCOME_UNKNOWN")
        .doesNotContain("SECRET", "credential or message");
    forwarding.dispatch();
    verify(delivery, times(1)).deliver(any());
  }
}
