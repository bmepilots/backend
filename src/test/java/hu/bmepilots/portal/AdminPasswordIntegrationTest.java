package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.*;
import java.net.http.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
class AdminPasswordIntegrationTest {
  private static final String ORIGINAL = "Original-fixture-password-2026";
  private static final String REPLACEMENT = "Replacement-fixture-password-2026";

  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired DataSource source;
  @MockitoSpyBean PasswordEncoder encoder;
  private final List<String> fixtures = new ArrayList<>();
  private String adminId;
  private String memberId;
  private Client admin;

  class Client {
    private final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    private String token;

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

    JsonNode ok(String method, String path, Object body, int status) throws Exception {
      var response = send(method, path, body, !method.equals("GET"));
      assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
      return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
    }

    void refresh() throws Exception {
      token = ok("GET", "/auth/csrf", null, 200).get("token").asText();
    }

    JsonNode login(String id, String password) throws Exception {
      var user = ok("POST", "/auth/login", loginBody(id, password), 200);
      refresh();
      return user;
    }
  }

  @BeforeEach
  void setup() throws Exception {
    adminId = fixture("ACTIVE", "ADMIN");
    memberId = fixture("ACTIVE", "USER");
    admin = new Client();
    admin.login(adminId, ORIGINAL);
  }

  @AfterEach
  void disableFixtureAdministrators() {
    // Preserve their audit references without altering the last-admin tests in other suites.
    for (String id : fixtures) {
      db.sql("UPDATE users SET status='DISABLED',auth_version=auth_version+1 WHERE id=?")
          .param(id)
          .update();
    }
  }

  private String fixture(String status, String role) {
    String id = UUID.randomUUID().toString();
    String email = id + "@example.test";
    db.sql(
            "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,?)")
        .params(id, email, email, "Password fixture", encoder.encode(ORIGINAL), status)
        .update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,?)").params(id, role).update();
    fixtures.add(id);
    return id;
  }

  private Map<String, Object> loginBody(String id, String password) {
    return Map.of("email", id + "@example.test", "password", password);
  }

  private String resetPath(String id) {
    return "/admin/users/" + id + "/password";
  }

  private long eventCount(String id, String action) {
    return db.sql("SELECT COUNT(*) FROM audit_log WHERE entity_id=? AND action=?")
        .params(id, action)
        .query(Long.class)
        .single();
  }

  private LocalDateTime lastLogin(String id) {
    return db.sql("SELECT last_login_at FROM users WHERE id=?")
        .param(id)
        .query(
            (row, index) ->
                row.getTimestamp(1) == null ? null : row.getTimestamp(1).toLocalDateTime())
        .optional()
        .orElse(null);
  }

  @Test
  void resetRevokesEveryTargetSessionAndPreservesAdminSessionAndLoginHistory() throws Exception {
    Client first = new Client();
    Client second = new Client();
    first.login(memberId, ORIGINAL);
    var before = second.login(memberId, ORIGINAL);
    var previousLogin = lastLogin(memberId);
    long previousAuthVersion =
        db.sql("SELECT auth_version FROM users WHERE id=?")
            .param(memberId)
            .query(Long.class)
            .single();
    var result =
        admin.ok("PUT", resetPath(memberId), Map.of("newPassword", REPLACEMENT, "version", 0), 200);
    assertThat(result.get("id").asText()).isEqualTo(memberId);
    assertThat(result.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(result.get("role").asText()).isEqualTo("USER");
    assertThat(result.get("version").asLong()).isEqualTo(1);
    assertThat(result.get("lastLoginAt")).isEqualTo(before.get("lastLoginAt"));
    assertThat(result.toString())
        .doesNotContain(REPLACEMENT, ORIGINAL, "passwordHash", "authVersion");
    assertThat(lastLogin(memberId)).isEqualTo(previousLogin);
    assertThat(
            db.sql("SELECT auth_version FROM users WHERE id=?")
                .param(memberId)
                .query(Long.class)
                .single())
        .isEqualTo(previousAuthVersion + 1);
    String stored =
        db.sql("SELECT password_hash FROM users WHERE id=?")
            .param(memberId)
            .query(String.class)
            .single();
    assertThat(stored).startsWith("{argon2}").doesNotContain(REPLACEMENT);
    assertThat(encoder.matches(REPLACEMENT, stored)).isTrue();
    first.ok("GET", "/users/me", null, 401);
    second.ok("GET", "/users/me", null, 401);
    admin.ok("GET", "/admin/users", null, 200);
    var oldLogin = new Client();
    oldLogin.ok("POST", "/auth/login", loginBody(memberId, ORIGINAL), 401);
    assertThat(lastLogin(memberId)).isEqualTo(previousLogin);
    new Client().login(memberId, REPLACEMENT);
    assertThat(lastLogin(memberId)).isAfter(previousLogin);
    assertThat(eventCount(memberId, "PASSWORD_RESET")).isEqualTo(1);
    assertThat(
            db.sql(
                    "SELECT actor_user_id FROM audit_log WHERE entity_id=? AND action='PASSWORD_RESET'")
                .param(memberId)
                .query(String.class)
                .single())
        .isEqualTo(adminId);
    String entries = admin.ok("GET", "/admin/audit-log", null, 200).toString();
    String requests = admin.ok("GET", "/admin/audit-log?requests=true", null, 200).toString();
    assertThat(entries + requests).doesNotContain(ORIGINAL, REPLACEMENT, stored);
    assertThat(requests).contains("/api/v1/admin/users/{id}/password");
  }

  @Test
  void resetRejectsUnauthorizedCsrfInvalidStaleAndMissingRequestsWithoutMutation()
      throws Exception {
    var input = Map.of("newPassword", REPLACEMENT, "version", 0);
    new Client().ok("PUT", resetPath(memberId), input, 401);
    Client member = new Client();
    member.login(memberId, ORIGINAL);
    member.ok("PUT", resetPath(memberId), input, 403);
    assertThat(admin.send("PUT", resetPath(memberId), input, false).statusCode()).isEqualTo(403);
    List<Map<String, Object>> invalid =
        new ArrayList<>(
            List.of(
                Map.of("newPassword", "x".repeat(11), "version", 0),
                Map.of("newPassword", "x".repeat(129), "version", 0),
                Map.of("newPassword", " ".repeat(12), "version", 0),
                Map.of("version", 0),
                Map.of("newPassword", REPLACEMENT),
                Map.of("newPassword", REPLACEMENT, "version", -1)));
    Map<String, Object> nullVersion = new HashMap<>(input);
    nullVersion.put("version", null);
    invalid.add(nullVersion);
    for (var body : invalid) {
      var error = admin.ok("PUT", resetPath(memberId), body, 400);
      assertThat(error.toString()).doesNotContain(REPLACEMENT);
    }
    admin.ok("PUT", resetPath(memberId), Map.of("newPassword", REPLACEMENT, "version", 1), 409);
    admin.ok("PUT", resetPath(UUID.randomUUID().toString()), input, 404);
    assertThat(eventCount(memberId, "PASSWORD_RESET")).isZero();
    assertThat(member.ok("GET", "/users/me", null, 200).get("version").asLong()).isZero();
    new Client().login(memberId, ORIGINAL);
    admin.ok("PUT", resetPath(memberId), input, 200);
    admin.ok("PUT", resetPath(memberId), input, 409);
    assertThat(eventCount(memberId, "PASSWORD_RESET")).isEqualTo(1);
  }

  @Test
  void administratorMayResetOwnPasswordAndMustSignInAgain() throws Exception {
    var result =
        admin.ok("PUT", resetPath(adminId), Map.of("newPassword", REPLACEMENT, "version", 0), 200);
    assertThat(result.get("version").asLong()).isEqualTo(1);
    assertThat(result.get("role").asText()).isEqualTo("ADMIN");
    admin.ok("GET", "/admin/users", null, 401);
    new Client().ok("POST", "/auth/login", loginBody(adminId, ORIGINAL), 401);
    var signedIn = new Client();
    signedIn.login(adminId, REPLACEMENT);
    signedIn.ok("GET", "/admin/users", null, 200);
    assertThat(eventCount(adminId, "PASSWORD_RESET")).isEqualTo(1);
  }

  @Test
  void inactiveUsersRemainInactiveAndHaveNoInventedLoginDateAfterReset() throws Exception {
    for (String status : List.of("PENDING_APPROVAL", "SUSPENDED", "DISABLED", "REJECTED")) {
      String id = fixture(status, "USER");
      var result =
          admin.ok("PUT", resetPath(id), Map.of("newPassword", REPLACEMENT, "version", 0), 200);
      assertThat(result.get("status").asText()).isEqualTo(status);
      assertThat(result.get("lastLoginAt").isNull()).isTrue();
      new Client().ok("POST", "/auth/login", loginBody(id, REPLACEMENT), 401);
      assertThat(lastLogin(id)).isNull();
      assertThat(eventCount(id, "LOGIN_SUCCEEDED")).isZero();
    }
  }

  @Test
  void onlySuccessfulLoginUpdatesTimestampWithoutChangingOptimisticVersion() throws Exception {
    assertThat(lastLogin(memberId)).isNull();
    new Client().ok("POST", "/auth/login", loginBody(memberId, "Wrong-fixture-password"), 401);
    assertThat(lastLogin(memberId)).isNull();
    Client member = new Client();
    var first = member.login(memberId, ORIGINAL);
    assertThat(first.get("version").asLong()).isZero();
    var initial = lastLogin(memberId);
    assertThat(LocalDateTime.parse(first.get("lastLoginAt").asText())).isEqualTo(initial);
    member.ok("GET", "/users/me", null, 200);
    member.refresh();
    admin.ok("GET", "/admin/users", null, 200);
    assertThat(lastLogin(memberId)).isEqualTo(initial);
    new Client().ok("POST", "/auth/login", loginBody(memberId, "Wrong-fixture-password"), 401);
    assertThat(lastLogin(memberId)).isEqualTo(initial);
    var second = new Client().login(memberId, ORIGINAL);
    assertThat(lastLogin(memberId)).isAfter(initial);
    assertThat(second.get("version").asLong()).isZero();
    assertThat(eventCount(memberId, "LOGIN_SUCCEEDED")).isEqualTo(2);
    var listed = admin.ok("GET", "/admin/users?status=ACTIVE", null, 200);
    JsonNode target = null;
    for (var user : listed) if (user.get("id").asText().equals(memberId)) target = user;
    assertThat(target).isNotNull();
    assertThat(target.get("lastLoginAt")).isEqualTo(second.get("lastLoginAt"));
  }

  private void pausePasswordVerification(CountDownLatch entered, CountDownLatch release) {
    doAnswer(
            invocation -> {
              boolean correct = (boolean) invocation.callRealMethod();
              entered.countDown();
              if (!release.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent test did not release verification.");
              }
              return correct;
            })
        .when(encoder)
        .matches(eq(ORIGINAL), anyString());
  }

  @Test
  void resetWinningAgainstInFlightLoginRejectsOldPasswordAndDoesNotRecordSuccess()
      throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    pausePasswordVerification(entered, release);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var login =
          executor.submit(
              () -> new Client().send("POST", "/auth/login", loginBody(memberId, ORIGINAL), true));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        admin.ok("PUT", resetPath(memberId), Map.of("newPassword", REPLACEMENT, "version", 0), 200);
      } finally {
        release.countDown();
      }
      assertThat(login.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(401);
    }
    assertThat(lastLogin(memberId)).isNull();
    assertThat(eventCount(memberId, "LOGIN_SUCCEEDED")).isZero();
    new Client().login(memberId, REPLACEMENT);
  }

  @Test
  void inFlightSelfServiceChangeCannotOverwriteAdministratorReset() throws Exception {
    Client member = new Client();
    member.login(memberId, ORIGINAL);
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    pausePasswordVerification(entered, release);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var change =
          executor.submit(
              () ->
                  member.send(
                      "PUT",
                      "/users/me/password",
                      Map.of(
                          "currentPassword",
                          ORIGINAL,
                          "newPassword",
                          "Concurrent-fixture-password"),
                      true));
      try {
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        admin.ok("PUT", resetPath(memberId), Map.of("newPassword", REPLACEMENT, "version", 0), 200);
      } finally {
        release.countDown();
      }
      assertThat(change.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
    }
    assertThat(eventCount(memberId, "PASSWORD_CHANGED")).isZero();
    assertThat(eventCount(memberId, "PASSWORD_RESET")).isEqualTo(1);
    new Client().login(memberId, REPLACEMENT);
  }

  @Test
  void migrationBackfillsOnlyLatestMatchingSuccessfulLoginAndKeepsUnknownHistoryNull()
      throws Exception {
    // Connection-local temporary tables shadow application tables. Execute the actual
    // migration against a V8-shaped fixture without changing the running test schema.
    try (var connection = source.getConnection();
        var sql = connection.createStatement()) {
      try {
        sql.execute(
            "CREATE TEMPORARY TABLE users (id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin PRIMARY KEY, version BIGINT NOT NULL DEFAULT 0)");
        sql.execute(
            "CREATE TEMPORARY TABLE audit_log (actor_user_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin, action VARCHAR(80) NOT NULL, entity_type VARCHAR(50) NOT NULL, entity_id VARCHAR(80) NOT NULL, created_at DATETIME(6) NOT NULL)");
        sql.execute("INSERT INTO users(id) VALUES ('known'),('unknown')");
        sql.execute(
            "INSERT INTO audit_log(actor_user_id,action,entity_type,entity_id,created_at) VALUES ('known','LOGIN_SUCCEEDED','USER','known','2026-09-01 08:00:00.000001'),('known','LOGIN_SUCCEEDED','USER','known','2026-10-01 09:00:00.000002'),('known','LOGIN_FAILED','USER','known','2026-10-02 09:00:00'),('known','LOGIN_SUCCEEDED','AUTH','known','2026-10-03 09:00:00'),('unknown','API_REQUEST','REQUEST','unknown','2026-10-04 09:00:00'),('unknown','LOGIN_SUCCEEDED','USER','known','2026-10-05 09:00:00')");
        ScriptUtils.executeSqlScript(
            connection, new ClassPathResource("db/migration/V9__user_last_login.sql"));
        try (var rows =
            sql.executeQuery("SELECT id,last_login_at,version FROM users ORDER BY id")) {
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("id")).isEqualTo("known");
          assertThat(rows.getTimestamp("last_login_at").toLocalDateTime())
              .isEqualTo(LocalDateTime.parse("2026-10-01T09:00:00.000002"));
          assertThat(rows.getLong("version")).isZero();
          assertThat(rows.next()).isTrue();
          assertThat(rows.getString("id")).isEqualTo("unknown");
          assertThat(rows.getTimestamp("last_login_at")).isNull();
          assertThat(rows.getLong("version")).isZero();
        }
      } finally {
        sql.execute("DROP TEMPORARY TABLE IF EXISTS audit_log");
        sql.execute("DROP TEMPORARY TABLE IF EXISTS users");
      }
    }
  }
}
