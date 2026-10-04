package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
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
class CommunityIntegrationTest {
  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired PasswordEncoder passwords;

  class Client {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String id;

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

    JsonNode request(String method, String path, Object body, int status) throws Exception {
      var result = send(method, path, body, !method.equals("GET"));
      assertThat(result.statusCode()).as(result.body()).isEqualTo(status);
      return result.body().isBlank() ? json.createObjectNode() : json.readTree(result.body());
    }

    void refresh() throws Exception {
      token = json.readTree(send("GET", "/auth/csrf", null, false).body()).get("token").asText();
    }

    void login(String email, String password) throws Exception {
      id =
          request("POST", "/auth/login", Map.of("email", email, "password", password), 200)
              .get("id")
              .asText();
      refresh();
    }
  }

  private Client member(String name) throws Exception {
    String id = UUID.randomUUID().toString();
    String email = "community-" + id + "@example.test";
    db.sql(
            "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,'ACTIVE')")
        .params(id, email, email, name, passwords.encode("Community-test-password-2026"))
        .update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,'USER')").param(id).update();
    var member = new Client();
    member.login(email, "Community-test-password-2026");
    return member;
  }

  private Client admin() throws Exception {
    var admin = new Client();
    admin.login("integration-admin@example.test", "Integration-admin-password-2026");
    return admin;
  }

  private Map<String, Object> event(String start, String end, boolean allDay) {
    var input = new HashMap<String, Object>();
    input.put("title", "Community exam");
    input.put("description", "Bring a calculator.");
    input.put("type", "EXAM");
    input.put("startsAt", start);
    input.put("endsAt", end);
    input.put("allDay", allDay);
    input.put("location", "Building Q");
    input.put("version", 0);
    return input;
  }

  private boolean contains(JsonNode list, String id) {
    for (var item : list) if (item.get("id").asText().equals(id)) return true;
    return false;
  }

  @Test
  void calendarPermissionsVersionsAndDateOverlap() throws Exception {
    Client author = member("Calendar author");
    Client other = member("Other calendar member");
    Client admin = admin();
    new Client().request("GET", "/calendar/events?from=2026-10-01&to=2026-11-01", null, 401);
    var input = event("2026-09-30T09:00:00", "2026-10-02T00:00:00", false);
    assertThat(author.send("POST", "/calendar/events", input, false).statusCode()).isEqualTo(403);
    var created = author.request("POST", "/calendar/events", input, 201);
    String id = created.get("id").asText();
    assertThat(created.get("authorId").asText()).isEqualTo(author.id);
    assertThat(created.get("authorName").asText()).isEqualTo("Calendar author");
    assertThat(created.get("startsAt").asText()).startsWith("2026-09-30T09:00");
    assertThat(
            contains(
                other.request("GET", "/calendar/events?from=2026-10-01&to=2026-10-02", null, 200),
                id))
        .isTrue();
    assertThat(
            contains(
                other.request("GET", "/calendar/events?from=2026-10-02&to=2026-10-03", null, 200),
                id))
        .isFalse();
    other.request("PATCH", "/calendar/events/" + id, input, 403);
    other.request("DELETE", "/calendar/events/" + id + "?version=0", null, 403);
    input.put("title", "Updated exam");
    var updated = author.request("PATCH", "/calendar/events/" + id, input, 200);
    assertThat(updated.get("version").asLong()).isEqualTo(1);
    author.request("PATCH", "/calendar/events/" + id, input, 409);
    admin.request("DELETE", "/calendar/events/" + id + "?version=0", null, 409);
    input.put("version", 1);
    input.put("location", "Building R");
    admin.request("PATCH", "/calendar/events/" + id, input, 200);
    admin.request("DELETE", "/calendar/events/" + id + "?version=2", null, 204);
    author.request("GET", "/calendar/events/" + id, null, 404);

    var day =
        author.request("POST", "/calendar/events", event("2026-10-04T00:00:00", null, true), 201);
    String dayId = day.get("id").asText();
    assertThat(
            contains(
                author.request("GET", "/calendar/events?from=2026-10-04&to=2026-10-05", null, 200),
                dayId))
        .isTrue();
    assertThat(
            contains(
                author.request("GET", "/calendar/events?from=2026-10-05&to=2026-10-06", null, 200),
                dayId))
        .isFalse();
    author.request("DELETE", "/calendar/events/" + dayId + "?version=0", null, 204);
    var instant =
        author.request("POST", "/calendar/events", event("2026-10-25T09:00:00", null, false), 201);
    String instantId = instant.get("id").asText();
    assertThat(instant.get("startsAt").asText()).startsWith("2026-10-25T09:00");
    assertThat(
            contains(
                author.request("GET", "/calendar/events?from=2026-10-24&to=2026-10-25", null, 200),
                instantId))
        .isFalse();
    assertThat(
            contains(
                author.request("GET", "/calendar/events?from=2026-10-25&to=2026-10-26", null, 200),
                instantId))
        .isTrue();
    author.request("DELETE", "/calendar/events/" + instantId + "?version=0", null, 204);
    author.request("POST", "/calendar/events", event("2026-10-04T09:00:00", null, true), 400);
    author.request(
        "POST",
        "/calendar/events",
        event("2026-10-04T09:00:00", "2026-10-04T08:00:00", false),
        400);
    var unknownType = event("2026-10-04T09:00:00", null, false);
    unknownType.put("type", "UNKNOWN");
    author.request("POST", "/calendar/events", unknownType, 400);
    var missingVersion = event("2026-10-04T09:00:00", null, false);
    missingVersion.remove("version");
    author.request("POST", "/calendar/events", missingVersion, 400);
    author.request("GET", "/calendar/events?from=2026-10-04&to=2026-10-04", null, 400);
    author.request("GET", "/calendar/events?from=2026-01-01&to=2028-01-01", null, 400);
    assertThat(
            db.sql(
                    "SELECT actor_user_id FROM audit_log WHERE entity_id=? AND action='CALENDAR_EVENT_CREATED'")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(author.id);
    assertThat(
            db.sql(
                    "SELECT actor_user_id FROM audit_log WHERE entity_id=? AND action='CALENDAR_EVENT_DELETED'")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(admin.id);
  }

  @Test
  void membersContributeLinksAndOnlyAuthorOrAdminMayManageThem() throws Exception {
    Client author = member("Link author");
    Client other = member("Other link member");
    Client admin = admin();
    var categories = author.request("GET", "/links/categories", null, 200);
    assertThat(categories.isEmpty()).isFalse();
    var input =
        new HashMap<String, Object>(
            Map.of(
                "categoryId",
                categories.get(0).get("id").asText(),
                "name",
                "Flight preparation",
                "url",
                "https://example.test/preparation",
                "description",
                "Study notes",
                "sortOrder",
                0,
                "version",
                0));
    assertThat(author.send("POST", "/links", input, false).statusCode()).isEqualTo(403);
    var link = author.request("POST", "/links", input, 201);
    String id = link.get("id").asText();
    assertThat(link.get("authorId").asText()).isEqualTo(author.id);
    assertThat(link.get("authorName").asText()).isEqualTo("Link author");
    assertThat(link.get("createdAt").isNull()).isFalse();
    other.request("PATCH", "/links/" + id, input, 403);
    other.request("DELETE", "/links/" + id + "?version=0", null, 403);
    author.request("POST", "/admin/links", input, 403);
    author.request(
        "POST", "/admin/links/categories", Map.of("name", "Not allowed", "sortOrder", 0), 403);
    input.put("name", "Updated flight preparation");
    author.request("PATCH", "/links/" + id, input, 200);
    author.request("PATCH", "/links/" + id, input, 409);
    author.request("DELETE", "/links/" + id + "?version=0", null, 409);
    input.put("version", 1);
    admin.request("PATCH", "/admin/links/" + id, input, 200);
    admin.request("DELETE", "/links/" + id + "?version=2", null, 204);
    input.put("url", "javascript:alert(1)");
    author.request("POST", "/links", input, 400);
    input.put("url", "https://name:secret@example.test");
    author.request("POST", "/links", input, 400);
    assertThat(
            db.sql(
                    "SELECT actor_user_id FROM audit_log WHERE entity_id=? AND action='LINK_CREATED'")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(author.id);
    assertThat(
            db.sql(
                    "SELECT actor_user_id FROM audit_log WHERE entity_id=? AND action='LINK_DELETED'")
                .param(id)
                .query(String.class)
                .single())
        .isEqualTo(admin.id);
  }
}
