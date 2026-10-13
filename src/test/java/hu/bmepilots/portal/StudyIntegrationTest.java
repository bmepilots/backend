package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.*;

import hu.bmepilots.portal.study.StudyCatalog;
import java.net.*;
import java.net.http.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import tools.jackson.databind.*;

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
class StudyIntegrationTest {
  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired PasswordEncoder passwords;
  @Autowired StudyCatalog catalog;

  class Client {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String id;

    HttpResponse<String> send(String method, String path, Object body, boolean csrf)
        throws Exception {
      if (csrf && token == null)
        token = json.readTree(send("GET", "/auth/csrf", null, false).body()).get("token").asText();
      var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
      if (csrf) req.header("X-CSRF-TOKEN", token);
      if (body != null) req.header("Content-Type", "application/json");
      return http.send(
          req.method(
                  method,
                  body == null
                      ? HttpRequest.BodyPublishers.noBody()
                      : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }

    JsonNode request(String method, String path, Object body, int status) throws Exception {
      var res = send(method, path, body, !method.equals("GET"));
      assertThat(res.statusCode()).as(res.body()).isEqualTo(status);
      return json.readTree(res.body());
    }
  }

  Client member() throws Exception {
    var c = new Client();
    c.id = UUID.randomUUID().toString();
    String email = "study-" + c.id + "@example.test";
    db.sql(
            "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,'ACTIVE')")
        .params(
            c.id, email, email, "Study tester", passwords.encode("Study-integration-password-2026"))
        .update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,'USER')").param(c.id).update();
    c.request(
        "POST",
        "/auth/login",
        Map.of("email", email, "password", "Study-integration-password-2026"),
        200);
    c.token = null;
    return c;
  }

  Map<String, Object> attempt(String mode, List<Map<String, Object>> submissions) {
    return new HashMap<>(
        Map.of(
            "requestId",
            UUID.randomUUID().toString(),
            "subjectId",
            catalog.subject().id(),
            "version",
            catalog.subject().version(),
            "mode",
            mode,
            "submissions",
            submissions));
  }

  Map<String, Object> submission(String id, Map<String, String> answers) {
    return Map.of("exerciseId", id, "answers", answers);
  }

  @Test
  void privateDurableGradingCsrfAndIdempotency() throws Exception {
    new Client().request("GET", "/study/subjects", null, 401);
    var c = member();
    var other = member();
    var publicBank = c.request("GET", "/study/subjects", null, 200).toString();
    assertThat(publicBank).doesNotContain("\"expected\"", "\"steps\"");
    var input =
        attempt(
            "PRACTICE",
            List.of(submission("dc-current-divider", Map.of("I1", "20 mA", "I2", "20 mA"))));
    assertThat(c.send("POST", "/study/attempts", input, false).statusCode()).isEqualTo(403);
    var result = c.request("POST", "/study/attempts", input, 200);
    assertThat(result.get("earned").asInt()).isEqualTo(1);
    assertThat(result.get("possible").asInt()).isEqualTo(3);
    assertThat(result.at("/exercises/0/question/title").asText())
        .isEqualTo("Sharing a current source");
    assertThat(c.request("POST", "/study/attempts", input, 200)).isEqualTo(result);
    assertThat(c.request("GET", "/study/attempts/" + result.get("id").asText(), null, 200))
        .isEqualTo(result);
    other.request("GET", "/study/attempts/" + result.get("id").asText(), null, 404);
    assertThat(other.request("GET", "/study/progress", null, 200).at("/total/attempts").asInt())
        .isZero();
    assertThat(c.request("GET", "/study/progress", null, 200).at("/total/attempts").asInt())
        .isEqualTo(1);
    assertThat(
            db.sql(
                    "SELECT COUNT(*) FROM audit_log WHERE action='STUDY_ATTEMPT_SUBMITTED' AND actor_user_id=?")
                .param(c.id)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    input.put("mode", "MOCK");
    c.request("POST", "/study/attempts", input, 409);
    db.sql("UPDATE users SET status='SUSPENDED' WHERE id=?").param(c.id).update();
    c.request("GET", "/study/subjects", null, 401);
  }

  @Test
  void validMockAndUntrustedPayloads() throws Exception {
    var c = member();
    var submissions = new ArrayList<Map<String, Object>>();
    for (String id :
        List.of("theory-ohm", "theory-kcl", "theory-norton", "r-bridge", "eq-node", "sp-clamped")) {
      var answers = new HashMap<String, String>();
      catalog.exercise(id).fields().forEach(f -> answers.put(f.id(), f.expected()));
      submissions.add(submission(id, answers));
    }
    var body = attempt("MOCK", submissions);
    var result = c.request("POST", "/study/attempts", body, 200);
    assertThat(result.get("earned")).isEqualTo(result.get("possible"));
    var invalid = attempt("MOCK", List.of(submission("r-series", Map.of())));
    c.request("POST", "/study/attempts", invalid, 400);
    invalid = attempt("PRACTICE", List.of(submission("r-series", Map.of("unknown", "42"))));
    c.request("POST", "/study/attempts", invalid, 400);
    invalid = attempt("PRACTICE", List.of(submission("r-series", Map.of("Req", "x".repeat(257)))));
    c.request("POST", "/study/attempts", invalid, 400);
    invalid = attempt("PRACTICE", List.of(submission("r-series", Map.of("Req", "630"))));
    invalid.put("version", "outdated");
    c.request("POST", "/study/attempts", invalid, 409);
    invalid.put("version", catalog.subject().version());
    invalid.put("subjectId", "another-subject");
    c.request("POST", "/study/attempts", invalid, 404);
    invalid =
        attempt(
            "PRACTICE",
            List.of(
                submission(
                    "eq-node",
                    Map.of("kcl", "I1=I2+I3", "loop", "U2=Vs-U1", "parallel", "2*U3=2*U2"))));
    assertThat(c.request("POST", "/study/attempts", invalid, 200).get("earned").asInt())
        .isEqualTo(3);
  }
}
