package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.*;

import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.datasource.url=jdbc:mariadb://127.0.0.1:3308/bmepilots_test",
      "spring.datasource.username=bmepilots_test",
      "spring.datasource.password=${TEST_DB_PASSWORD}",
      "portal.bootstrap.email=integration-admin@example.test",
      "portal.bootstrap.password=Integration-admin-password-2026",
      "portal.mail.enabled=false",
      "portal.mail.storage=./target/test-attachments"
    })
class SecurityContentIntegrationTest {
  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired hu.bmepilots.portal.audit.application.AuditService audit;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
  @Autowired hu.bmepilots.portal.mail.infrastructure.AttachmentStorage storage;
  @Autowired hu.bmepilots.portal.mail.infrastructure.EmailSanitizer sanitizer;
  @Autowired hu.bmepilots.portal.mail.application.MailService mail;

  class Client {
    HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;

    HttpResponse<String> send(String method, String path, Object body, boolean csrf)
        throws Exception {
      var builder =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
      if (csrf) {
        if (token == null) refresh();
        builder.header("X-CSRF-TOKEN", token);
      }
      if (body != null) builder.header("Content-Type", "application/json");
      return http.send(
          builder
              .method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode ok(String method, String path, Object body, int status) throws Exception {
      var r = send(method, path, body, !method.equals("GET"));
      assertThat(r.statusCode()).as(r.body()).isEqualTo(status);
      return r.body().isBlank() ? json.createObjectNode() : json.readTree(r.body());
    }

    void refresh() throws Exception {
      token = json.readTree(send("GET", "/auth/csrf", null, false).body()).get("token").asText();
    }

    JsonNode login(String email, String password) throws Exception {
      var result = ok("POST", "/auth/login", Map.of("email", email, "password", password), 200);
      refresh();
      return result;
    }
  }

  @Test
  void mailImportsAreIdempotentAndPreservePrivateState() throws Exception {
    String email = "fixture-" + UUID.randomUUID() + "@example.test";
    var instant = java.time.Instant.parse("2026-10-02T10:00:00Z");
    var attachment =
        new hu.bmepilots.portal.mail.application.port.MailProvider.Attachment(
            "checklist.txt", "text/plain", "Checklist fixture".getBytes());
    var message =
        new hu.bmepilots.portal.mail.application.port.MailProvider.Message(
            10,
            "gmail-fixture-id",
            null,
            "Fixture mail",
            "sender@example.test",
            "Sender",
            instant,
            "Checklist",
            "<p>Checklist</p><script>bad()</script>",
            List.of(
                new hu.bmepilots.portal.mail.application.port.MailProvider.Address(
                    "TO", "crew@example.test", "Crew")),
            List.of(attachment));
    class Provider implements hu.bmepilots.portal.mail.application.port.MailProvider {
      Batch next = new Batch(100, 10, List.of(message), List.of());
      boolean fail = false;

      public void testConnection() {}

      public Batch fetch(long validity, long cursor, List<Long> retry) {
        if (fail) throw new IllegalStateException("Connection fixture failure");
        return next;
      }
    }
    Provider provider = new Provider();
    var sync =
        new hu.bmepilots.portal.mail.application.MailSyncService(
            db, provider, storage, sanitizer, audit, transactions, true, email);
    try {
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      String account =
          db.sql("SELECT id FROM mail_accounts WHERE email_address=?")
              .param(email)
              .query(String.class)
              .single();
      String id =
          db.sql("SELECT id FROM mail_messages WHERE account_id=?")
              .param(account)
              .query(String.class)
              .single();
      assertThat(mail.get(id).message().bodyHtml()).doesNotContain("<script");
      assertThat(mail.get(id).addresses()).hasSize(1);
      String attachmentId = mail.get(id).attachments().getFirst().id();
      Client admin = new Client();
      String adminId =
          admin
              .login("integration-admin@example.test", "Integration-admin-password-2026")
              .get("id")
              .asText();
      admin.ok(
          "PUT",
          "/mail/messages/" + id + "/state",
          Map.of("isRead", true, "isImportant", true),
          204);
      var listed = admin.ok("GET", "/mail/messages?q=Fixture", null, 200);
      JsonNode imported = null;
      for (var entry : listed) if (entry.get("id").asText().equals(id)) imported = entry;
      assertThat(imported).isNotNull();
      assertThat(imported.get("isRead").asBoolean()).isTrue();
      admin.ok("PATCH", "/mail/messages/" + id + "/state", Map.of("isRead", false), 204);
      var unreadAgain =
          mail.list(adminId, "Fixture", true, 0).stream()
              .filter(m -> m.id().equals(id))
              .findFirst()
              .orElseThrow();
      assertThat(unreadAgain.isRead()).isFalse();
      assertThat(unreadAgain.isImportant()).isTrue();
      admin.ok("PATCH", "/mail/messages/" + id + "/state", Map.of("isImportant", false), 204);
      assertThat(mail.list(adminId, "Fixture", true, 0).stream().anyMatch(m -> m.id().equals(id)))
          .isTrue();
      admin.ok("PATCH", "/mail/messages/" + id + "/state", Map.of("isRead", true), 204);
      assertThat(mail.list(adminId, "Fixture", true, 0).stream().anyMatch(m -> m.id().equals(id)))
          .isFalse();
      Client reloaded = new Client();
      reloaded.login("integration-admin@example.test", "Integration-admin-password-2026");
      var persisted = reloaded.ok("GET", "/mail/messages?q=Fixture", null, 200);
      for (var entry : persisted) {
        if (entry.get("id").asText().equals(id)) {
          assertThat(entry.get("isRead").asBoolean()).isTrue();
          assertThat(entry.get("isImportant").asBoolean()).isFalse();
        }
      }
      String other = UUID.randomUUID().toString();
      db.sql(
              "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,'ACTIVE')")
          .params(
              other,
              other + "@example.test",
              other + "@example.test",
              "Other fixture",
              "not-a-login-hash")
          .update();
      assertThat(
              mail.list(other, "Fixture", false, 0).stream()
                  .filter(m -> m.id().equals(id))
                  .findFirst()
                  .orElseThrow()
                  .isRead())
          .isFalse();
      var download =
          admin.send(
              "GET",
              "/mail/messages/" + id + "/attachments/" + attachmentId + "/download",
              null,
              false);
      assertThat(download.statusCode()).isEqualTo(200);
      assertThat(download.body()).isEqualTo("Checklist fixture");
      assertThat(download.headers().firstValue("Content-Disposition").orElse(""))
          .startsWith("attachment;");
      admin.ok(
          "GET",
          "/mail/messages/" + UUID.randomUUID() + "/attachments/" + attachmentId + "/download",
          null,
          404);
      assertThat(
              new Client()
                  .send(
                      "GET",
                      "/mail/messages/" + id + "/attachments/" + attachmentId + "/download",
                      null,
                      false)
                  .statusCode())
          .isEqualTo(401);
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      assertThat(
              db.sql("SELECT COUNT(*) FROM mail_messages WHERE account_id=?")
                  .param(account)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      var moved =
          new hu.bmepilots.portal.mail.application.port.MailProvider.Message(
              3,
              message.providerId(),
              message.messageId(),
              message.subject(),
              message.senderAddress(),
              message.senderName(),
              instant,
              message.text(),
              message.html(),
              message.addresses(),
              message.attachments());
      provider.next =
          new hu.bmepilots.portal.mail.application.port.MailProvider.Batch(
              200, 3, List.of(moved), List.of());
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      assertThat(
              db.sql("SELECT COUNT(*) FROM mail_messages WHERE account_id=?")
                  .param(account)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
      assertThat(
              mail.list(adminId, "Fixture", false, 0).stream()
                  .filter(m -> m.id().equals(id))
                  .findFirst()
                  .orElseThrow()
                  .isRead())
          .isTrue();
      provider.fail = true;
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      assertThat(
              db.sql("SELECT last_uid FROM mail_folders WHERE account_id=?")
                  .param(account)
                  .query(Long.class)
                  .single())
          .isEqualTo(3);
      assertThat(
              db.sql("SELECT last_error FROM mail_accounts WHERE id=?")
                  .param(account)
                  .query(String.class)
                  .single())
          .isEqualTo("SYNC_FAILED");
      provider.fail = false;
      provider.next =
          new hu.bmepilots.portal.mail.application.port.MailProvider.Batch(
              200,
              4,
              List.of(),
              List.of(
                  new hu.bmepilots.portal.mail.application.port.MailProvider.Failure(
                      4, "MIME_LIMIT")));
      sync.requestSync();
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(10))
          .until(() -> !sync.status().running());
      assertThat(
              db.sql(
                      "SELECT COUNT(*) FROM mail_sync_failures f JOIN mail_folders d ON d.id=f.folder_id WHERE d.account_id=?")
                  .param(account)
                  .query(Long.class)
                  .single())
          .isEqualTo(1);
    } finally {
      sync.close();
    }
  }

  @Test
  void completeMembershipSecurityAndContentFlow() throws Exception {
    Client anonymous = new Client();
    assertThat(anonymous.send("GET", "/users/me", null, false).statusCode()).isEqualTo(401);
    assertThat(
            anonymous
                .send(
                    "POST",
                    "/auth/login",
                    Map.of("email", "x@example.test", "password", "irrelevant"),
                    false)
                .statusCode())
        .isEqualTo(403);
    Client admin = new Client();
    var adminUser =
        admin.login("integration-admin@example.test", "Integration-admin-password-2026");
    String me = adminUser.get("id").asText();
    admin.ok(
        "POST",
        "/admin/users/" + me + "/disable",
        Map.of("version", adminUser.get("version").asLong()),
        409);
    var settings = admin.ok("GET", "/admin/settings", null, 200);
    admin.ok(
        "PATCH",
        "/admin/settings",
        Map.of(
            "registrationEnabled",
            true,
            "portalName",
            "BME Pilots 2026",
            "version",
            settings.get("version").asLong()),
        200);
    String email = "member-" + UUID.randomUUID() + "@example.test";
    String password = "Member-password-2026";
    var registration =
        Map.of("email", email, "displayName", "Integration Member", "password", password);
    anonymous.ok("POST", "/auth/register", registration, 202);
    anonymous.ok("POST", "/auth/register", registration, 202);
    anonymous.ok("POST", "/auth/login", Map.of("email", email, "password", password), 401);
    String memberId =
        db.sql("SELECT id FROM users WHERE email_normalized=?")
            .param(email)
            .query(String.class)
            .single();
    assertThat(
            db.sql("SELECT password_hash FROM users WHERE id=?")
                .param(memberId)
                .query(String.class)
                .single())
        .startsWith("{argon2}")
        .doesNotContain(password);
    admin.ok("POST", "/admin/registrations/" + memberId + "/approve", Map.of("version", 0), 200);
    Client member = new Client();
    member.login(email, password);
    Client revoked = new Client();
    revoked.login(email, password);
    member.ok("GET", "/admin/users", null, 403);
    member.ok(
        "POST",
        "/admin/announcements",
        Map.of("title", "Forbidden", "bodyMarkdown", "No", "important", false, "version", 0),
        403);
    var announcement =
        admin.ok(
            "POST",
            "/admin/announcements",
            Map.of(
                "title",
                "Integration briefing",
                "bodyMarkdown",
                "Welcome **crew**",
                "important",
                true,
                "version",
                0),
            201);
    String announcementId = announcement.get("id").asText();
    assertThat(
            member.ok("GET", "/announcements/" + announcementId, null, 200).get("title").asText())
        .isEqualTo("Integration briefing");
    var updated =
        admin.ok(
            "PATCH",
            "/admin/announcements/" + announcementId,
            Map.of(
                "title",
                "Updated",
                "bodyMarkdown",
                "Updated body",
                "important",
                false,
                "version",
                0),
            200);
    assertThat(updated.get("version").asInt()).isEqualTo(1);
    admin.ok(
        "PATCH",
        "/admin/announcements/" + announcementId,
        Map.of("title", "Stale", "bodyMarkdown", "Stale body", "important", false, "version", 0),
        409);
    admin.ok(
        "POST",
        "/admin/knowledge/categories",
        Map.of("name", "Retired knowledge", "sortOrder", 0),
        410);
    var linkCategory =
        admin.ok(
            "POST",
            "/admin/links/categories",
            Map.of("name", "Test links " + UUID.randomUUID(), "sortOrder", 1),
            200);
    var linkInput =
        new HashMap<String, Object>(
            Map.of(
                "categoryId",
                linkCategory.get("id").asText(),
                "name",
                "Unsafe",
                "url",
                "javascript:alert(1)",
                "description",
                "",
                "sortOrder",
                0,
                "version",
                0));
    admin.ok("POST", "/admin/links", linkInput, 400);
    linkInput.put("url", "https://example.com");
    var link = admin.ok("POST", "/admin/links", linkInput, 201);
    member.ok("GET", "/dashboard", null, 200);
    admin.ok("POST", "/admin/mail/sync", null, 409);
    assertThat(admin.ok("GET", "/admin/audit-log", null, 200).size()).isGreaterThan(0);
    admin.ok("POST", "/admin/users/" + memberId + "/suspend", Map.of("version", 1), 200);
    member.ok("GET", "/users/me", null, 401);
    // A login page opened with a revoked session cookie can obtain a fresh CSRF
    // token on its first attempt, then log in using another valid account.
    var recovery = revoked.send("GET", "/auth/csrf", null, false);
    assertThat(recovery.statusCode()).as(recovery.body()).isEqualTo(200);
    revoked.token = json.readTree(recovery.body()).get("token").asText();
    revoked.login("integration-admin@example.test", "Integration-admin-password-2026");
    assertThat(revoked.ok("GET", "/users/me", null, 200).get("id").asText()).isEqualTo(me);
    revoked.ok("POST", "/auth/logout", null, 204);
    admin.ok("DELETE", "/admin/links/" + link.get("id").asText() + "?version=0", null, 204);
    admin.ok("DELETE", "/admin/links/categories/" + linkCategory.get("id").asText(), null, 204);
    admin.ok("DELETE", "/admin/announcements/" + announcementId + "?version=1", null, 204);
    admin.ok("POST", "/auth/logout", null, 204);
    admin.ok("GET", "/users/me", null, 401);
    org.awaitility.Awaitility.await()
        .untilAsserted(
            () -> {
              assertThat(
                      db.sql(
                              "SELECT COUNT(*) FROM audit_log WHERE actor_user_id=? AND action='LOGIN_SUCCEEDED'")
                          .param(me)
                          .query(Long.class)
                          .single())
                  .isPositive();
              assertThat(
                      db.sql(
                              "SELECT COUNT(*) FROM audit_log WHERE actor_user_id=? AND action='LOGOUT'")
                          .param(me)
                          .query(Long.class)
                          .single())
                  .isPositive();
              assertThat(
                      db.sql("SELECT COUNT(*) FROM audit_log WHERE action='LOGIN_FAILED'")
                          .query(Long.class)
                          .single())
                  .isPositive();
              assertThat(
                      db.sql(
                              "SELECT COUNT(*) FROM audit_log WHERE action='API_REQUEST' AND response_status=403")
                          .query(Long.class)
                          .single())
                  .isPositive();
            });
    // Request paths contain route templates only; no search/email/password query values.
    assertThat(
            db.sql(
                    "SELECT COUNT(*) FROM audit_log WHERE request_path LIKE '%?%' OR request_path LIKE '%@%'")
                .query(Long.class)
                .single())
        .isZero();
  }
}
