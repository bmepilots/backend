package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.*;

import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.PortalPrincipal;
import hu.bmepilots.portal.documents.application.DocumentUploadCleanup;
import hu.bmepilots.portal.documents.application.DocumentsService;
import hu.bmepilots.portal.documents.infrastructure.DocumentStorage;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
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
      "portal.mail.storage=./target/test-attachments",
      "portal.documents.storage=./target/test-documents"
    })
class DocumentsIntegrationTest {
  @Value("${local.server.port}")
  int port;

  @Autowired ObjectMapper json;
  @Autowired JdbcClient db;
  @Autowired PasswordEncoder passwords;
  @Autowired DocumentsService service;
  @Autowired DocumentStorage storage;
  @Autowired DocumentUploadCleanup cleanup;

  private record Upload(String filename, long bytes) {}

  private class Client {
    final HttpClient http =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .build();
    String token;
    String id;

    HttpResponse<String> send(String method, String path, Object body, boolean csrf)
        throws Exception {
      var request =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path));
      if (csrf) {
        if (token == null) refresh();
        request.header("X-CSRF-TOKEN", token);
      }
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
      token = json.readTree(send("GET", "/auth/csrf", null, false).body()).get("token").asText();
    }

    void login(String email, String password) throws Exception {
      id =
          ok("POST", "/auth/login", Map.of("email", email, "password", password), 200)
              .get("id")
              .asText();
      refresh();
    }

    HttpResponse<String> upload(List<Upload> files) throws Exception {
      return multipart("/documents", "files", files, true);
    }

    HttpResponse<String> stage(List<Upload> files) throws Exception {
      return multipart("/documents/uploads", "file", files, false);
    }

    HttpResponse<String> multipart(String path, String field, List<Upload> files, boolean metadata)
        throws Exception {
      String boundary = "document-fixture-" + UUID.randomUUID();
      List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
      if (metadata)
        parts.add(
            HttpRequest.BodyPublishers.ofString(
                "--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\"title\"\r\n\r\nCommunity notes\r\n--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\"description\"\r\n\r\nShared flight notes\r\n"));
      for (var file : files) {
        parts.add(
            HttpRequest.BodyPublishers.ofString(
                "--"
                    + boundary
                    + "\r\nContent-Disposition: form-data; name=\""
                    + field
                    + "\"; filename=\""
                    + file.filename()
                    + "\"\r\nContent-Type: application/pdf\r\n\r\n"));
        parts.add(HttpRequest.BodyPublishers.ofInputStream(() -> new SizedInput(file.bytes())));
        parts.add(HttpRequest.BodyPublishers.ofString("\r\n"));
      }
      parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n"));
      return http.send(
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
              .header("X-CSRF-TOKEN", token)
              .header("Content-Type", "multipart/form-data; boundary=" + boundary)
              .POST(
                  HttpRequest.BodyPublishers.concat(
                      parts.toArray(HttpRequest.BodyPublisher[]::new)))
              .build(),
          HttpResponse.BodyHandlers.ofString());
    }
  }

  private String staged(Client client, String name, long size) throws Exception {
    var response = client.stage(List.of(new Upload(name, size)));
    assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
    var upload = json.readTree(response.body());
    assertThat(upload.get("filename").asText()).isEqualTo(name);
    assertThat(upload.get("sizeBytes").asLong()).isEqualTo(size);
    assertThat(upload.get("expiresAt").asText()).isNotBlank();
    assertThat(upload.toString()).doesNotContain("storageKey", "storage_key");
    return upload.get("id").asText();
  }

  private Map<String, Object> publication(List<String> ids) {
    return Map.of(
        "title", "Staged community notes", "description", "Published together", "uploadIds", ids);
  }

  @Test
  void stagedUploadsRemainPrivateAndPublishFiveFilesAtomically() throws Exception {
    Client author = member("Staged author");
    Client peer = member("Staged peer");
    Client admin = new Client();
    admin.login("integration-admin@example.test", "Integration-admin-password-2026");
    List<String> ids = new ArrayList<>();
    for (int index = 0; index < 5; index++)
      ids.add(staged(author, "part-" + index + ".txt", index + 1));
    assertThat(
            db.sql("SELECT COUNT(*) FROM document_posts WHERE created_by=?")
                .param(author.id)
                .query(Long.class)
                .single())
        .isZero();
    peer.ok("POST", "/documents", publication(ids), 404);
    admin.ok("POST", "/documents", publication(ids), 404);
    peer.ok("DELETE", "/documents/uploads/" + ids.getFirst(), null, 404);
    author.ok("POST", "/documents", publication(List.of(ids.getFirst(), ids.getFirst())), 400);
    author.ok("POST", "/documents", publication(Collections.nCopies(6, ids.getFirst())), 400);
    author.ok("POST", "/documents", publication(List.of()), 400);
    assertThat(author.send("POST", "/documents", publication(ids), false).statusCode())
        .isEqualTo(403);
    var post = author.ok("POST", "/documents", publication(ids), 201);
    String postId = post.get("id").asText();
    assertThat(post.get("fileCount").asInt()).isEqualTo(5);
    assertThat(post.get("authorId").asText()).isEqualTo(author.id);
    assertThat(
            db.sql("SELECT COUNT(*) FROM document_uploads WHERE created_by=?")
                .param(author.id)
                .query(Long.class)
                .single())
        .isZero();
    var detail = peer.ok("GET", "/documents/" + postId, null, 200);
    for (int index = 0; index < 5; index++) {
      assertThat(detail.get("files").get(index).get("id").asText()).isEqualTo(ids.get(index));
      assertThat(
              peer.send(
                      "GET",
                      "/documents/" + postId + "/files/" + ids.get(index) + "/download",
                      null,
                      false)
                  .body())
          .isEqualTo("x".repeat(index + 1));
    }
    author.ok("POST", "/documents", publication(ids), 404);
    author.ok("DELETE", "/documents/uploads/" + ids.getFirst(), null, 404);
    author.ok("DELETE", "/documents/" + postId + "?version=0", null, 204);
  }

  @Test
  void stagingEnforcesSingleFileSizeQuotaAndCsrf() throws Exception {
    Client author = member("Staging limits");
    assertThat(author.stage(List.of(new Upload("empty.txt", 0))).statusCode()).isEqualTo(400);
    assertThat(
            author.stage(List.of(new Upload("one.txt", 1), new Upload("two.txt", 1))).statusCode())
        .isEqualTo(400);
    String maximum = staged(author, "maximum.txt", DocumentStorage.MAX_FILE_BYTES);
    assertThat(
            author
                .stage(List.of(new Upload("large.txt", DocumentStorage.MAX_FILE_BYTES + 1)))
                .statusCode())
        .isEqualTo(413);
    String goodToken = author.token;
    author.token = "invalid";
    assertThat(author.stage(List.of(new Upload("csrf.txt", 1))).statusCode()).isEqualTo(403);
    author.token = goodToken;
    Client anonymous = new Client();
    anonymous.refresh();
    assertThat(anonymous.stage(List.of(new Upload("anonymous.txt", 1))).statusCode())
        .isEqualTo(401);
    List<String> ids = new ArrayList<>(List.of(maximum));
    for (int index = 1; index < 10; index++) ids.add(staged(author, "quota-" + index + ".txt", 1));
    var overQuota = author.stage(List.of(new Upload("eleventh.txt", 1)));
    assertThat(overQuota.statusCode()).as(overQuota.body()).isEqualTo(409);
    assertThat(json.readTree(overQuota.body()).get("code").asText()).isEqualTo("UPLOAD_LIMIT");
    for (String id : ids) author.ok("DELETE", "/documents/uploads/" + id, null, 204);
    assertThat(
            db.sql("SELECT COUNT(*) FROM document_uploads WHERE created_by=?")
                .param(author.id)
                .query(Long.class)
                .single())
        .isZero();
  }

  @Test
  void failedPublishPreservesStagesAndExpiryCleanupKeepsPublishedFiles() throws Exception {
    Client author = member("Staged cleanup");
    String first = staged(author, "retained.txt", 7);
    String second = staged(author, "missing.txt", 8);
    String firstKey =
        db.sql("SELECT storage_key FROM document_uploads WHERE id=?")
            .param(first)
            .query(String.class)
            .single();
    String secondKey =
        db.sql("SELECT storage_key FROM document_uploads WHERE id=?")
            .param(second)
            .query(String.class)
            .single();
    author.ok("POST", "/documents", publication(List.of(first, UUID.randomUUID().toString())), 404);
    Files.delete(storage.path(secondKey));
    author.ok("POST", "/documents", publication(List.of(first, second)), 409);
    assertThat(
            db.sql("SELECT COUNT(*) FROM document_posts WHERE created_by=?")
                .param(author.id)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql("SELECT COUNT(*) FROM document_uploads WHERE created_by=?")
                .param(author.id)
                .query(Long.class)
                .single())
        .isEqualTo(2);
    assertThat(
            db.sql(
                    "SELECT COUNT(*) FROM audit_log WHERE entity_id=? AND action='DOCUMENT_FILE_UPLOADED'")
                .param(first)
                .query(Long.class)
                .single())
        .isZero();
    author.ok("DELETE", "/documents/uploads/" + second, null, 204);
    String postId =
        author.ok("POST", "/documents", publication(List.of(first)), 201).get("id").asText();
    String expired = staged(author, "expired.txt", 9);
    String expiredKey =
        db.sql("SELECT storage_key FROM document_uploads WHERE id=?")
            .param(expired)
            .query(String.class)
            .single();
    db.sql(
            "UPDATE document_uploads SET expires_at=DATE_SUB(CURRENT_TIMESTAMP(6),INTERVAL 1 MINUTE) WHERE id=?")
        .param(expired)
        .update();
    author.ok("POST", "/documents", publication(List.of(expired)), 404);
    String pending = staged(author, "pending.txt", 10);
    String pendingKey =
        db.sql("SELECT storage_key FROM document_uploads WHERE id=?")
            .param(pending)
            .query(String.class)
            .single();
    Path orphan = storage.path(UUID.randomUUID().toString());
    Path partial =
        storage.path(UUID.randomUUID().toString()).resolveSibling(UUID.randomUUID() + ".part");
    Path recent = storage.path(UUID.randomUUID().toString());
    Files.writeString(orphan, "crash residue");
    Files.writeString(partial, "interrupted stream");
    Files.writeString(recent, "active request grace");
    FileTime old = FileTime.from(Instant.now().minus(49, ChronoUnit.HOURS));
    for (Path path : List.of(orphan, partial, storage.path(firstKey), storage.path(pendingKey)))
      Files.setLastModifiedTime(path, old);
    cleanup.cleanup();
    assertThat(Files.exists(storage.path(expiredKey))).isFalse();
    assertThat(Files.exists(orphan)).isFalse();
    assertThat(Files.exists(partial)).isFalse();
    assertThat(Files.exists(recent)).isTrue();
    assertThat(Files.exists(storage.path(firstKey))).isTrue();
    assertThat(Files.exists(storage.path(pendingKey))).isTrue();
    assertThat(
            db.sql("SELECT COUNT(*) FROM document_uploads WHERE id=?")
                .param(expired)
                .query(Long.class)
                .single())
        .isZero();
    assertThat(
            db.sql(
                    "SELECT COUNT(*) FROM audit_log WHERE entity_id=? AND action='DOCUMENT_UPLOAD_EXPIRED'")
                .param(expired)
                .query(Long.class)
                .single())
        .isEqualTo(1);
    author.ok("DELETE", "/documents/uploads/" + pending, null, 204);
    author.ok("DELETE", "/documents/" + postId + "?version=0", null, 204);
    Files.delete(recent);
  }

  /** Generates a bounded upload without loading a 50 MB fixture into either JVM's heap. */
  private static class SizedInput extends InputStream {
    private long remaining;

    SizedInput(long size) {
      remaining = size;
    }

    @Override
    public int read() {
      return remaining-- > 0 ? 'x' : -1;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) {
      if (remaining <= 0) return -1;
      int count = (int) Math.min(remaining, length);
      Arrays.fill(bytes, offset, offset + count, (byte) 'x');
      remaining -= count;
      return count;
    }
  }

  private Client member(String name) throws Exception {
    String id = UUID.randomUUID().toString();
    String email = "documents-" + id + "@example.test";
    String password = "Documents-member-password-2026";
    db.sql(
            "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,'ACTIVE')")
        .params(id, email, email, name, passwords.encode(password))
        .update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,'USER')").param(id).update();
    Client client = new Client();
    client.login(email, password);
    return client;
  }

  @Test
  void membersShareFilesDiscussAndKeepOwnershipWhileAdminsModerate() throws Exception {
    Client author = member("Document author");
    Client peer = member("Document commenter");
    Client admin = new Client();
    admin.login("integration-admin@example.test", "Integration-admin-password-2026");
    var upload =
        author.upload(
            List.of(
                new Upload("one.pdf", 13),
                new Upload("two.pdf", 14),
                new Upload("three.pdf", 15),
                new Upload("four.pdf", 16),
                new Upload("five.pdf", 17)));
    assertThat(upload.statusCode()).as(upload.body()).isEqualTo(201);
    var post = json.readTree(upload.body());
    String id = post.get("id").asText();
    assertThat(post.get("fileCount").asInt()).isEqualTo(5);
    assertThat(post.get("authorId").asText()).isEqualTo(author.id);
    assertThat(post.get("authorName").asText()).isEqualTo("Document author");
    assertThat(author.ok("GET", "/documents?q=Community", null, 200).toString()).contains(id);
    var detail = peer.ok("GET", "/documents/" + id, null, 200);
    String fileId = detail.get("files").get(0).get("id").asText();
    assertThat(detail.toString())
        .doesNotContain("storageKey", "storage_key", "target/test-documents");
    String downloadPath = "/documents/" + id + "/files/" + fileId + "/download";
    var download = peer.send("GET", downloadPath, null, false);
    assertThat(download.statusCode()).isEqualTo(200);
    assertThat(download.body()).isEqualTo("x".repeat(13));
    assertThat(download.headers().firstValue("Content-Type").orElseThrow())
        .isEqualTo("application/octet-stream");
    assertThat(download.headers().firstValue("Content-Disposition").orElseThrow())
        .startsWith("attachment;");
    assertThat(download.headers().firstValue("Cache-Control").orElseThrow()).contains("no-store");
    new Client().ok("GET", downloadPath, null, 401);
    peer.ok("GET", "/documents/" + UUID.randomUUID() + "/files/" + fileId + "/download", null, 404);
    var edit = Map.of("title", "Edited notes", "description", "Updated description", "version", 0);
    peer.ok("PATCH", "/documents/" + id, edit, 403);
    author.ok(
        "PATCH",
        "/documents/" + id,
        Map.of("title", "Missing version", "description", "Must not overwrite the first revision"),
        400);
    assertThat(author.send("PATCH", "/documents/" + id, edit, false).statusCode()).isEqualTo(403);
    author.ok("PATCH", "/documents/" + id, edit, 200);
    author.ok("PATCH", "/documents/" + id, edit, 409);
    var comment =
        peer.ok(
            "POST",
            "/documents/" + id + "/comments",
            Map.of("body", "Which section covers navigation?"),
            201);
    String commentId = comment.get("id").asText();
    assertThat(comment.get("authorId").asText()).isEqualTo(peer.id);
    String commentPath = "/documents/" + id + "/comments/" + commentId;
    author.ok("PATCH", commentPath, Map.of("body", "Cannot rewrite a peer", "version", 0), 403);
    peer.ok("PATCH", commentPath, Map.of("body", "Missing version must not overwrite"), 400);
    peer.ok("PATCH", commentPath, Map.of("body", "Adding more context", "version", 0), 200);
    peer.ok("DELETE", commentPath + "?version=0", null, 409);
    admin.ok("PATCH", commentPath, Map.of("body", "Moderator clarification", "version", 1), 200);
    assertThat(
            peer.ok("GET", "/documents/" + id, null, 200).get("post").get("commentCount").asInt())
        .isEqualTo(1);
    peer.ok("DELETE", "/documents/" + id + "?version=1", null, 403);
    peer.ok(
        "PATCH",
        "/documents/" + UUID.randomUUID() + "/comments/" + commentId,
        Map.of("body", "Wrong post", "version", 2),
        404);
    admin.ok("DELETE", commentPath + "?version=2", null, 204);
    var keys =
        db.sql("SELECT storage_key FROM document_files WHERE post_id=?")
            .param(id)
            .query(String.class)
            .list();
    admin.ok(
        "PATCH",
        "/documents/" + id,
        Map.of("title", "Moderated", "description", "Reviewed", "version", 1),
        200);
    admin.ok("DELETE", "/documents/" + id + "?version=2", null, 204);
    keys.forEach(key -> assertThat(Files.exists(storage.path(key))).isFalse());
    author.ok("GET", "/documents/" + id, null, 404);
    var actions =
        db.sql("SELECT action FROM audit_log WHERE entity_id=? OR entity_id=? OR entity_id=?")
            .params(id, fileId, commentId)
            .query(String.class)
            .list();
    assertThat(actions)
        .contains(
            "DOCUMENT_CREATED",
            "DOCUMENT_UPDATED",
            "DOCUMENT_DELETED",
            "DOCUMENT_FILE_UPLOADED",
            "DOCUMENT_FILE_DOWNLOADED",
            "DOCUMENT_COMMENT_CREATED",
            "DOCUMENT_COMMENT_UPDATED",
            "DOCUMENT_COMMENT_DELETED");
  }

  @Test
  void uploadBoundariesAreEnforcedByHttpAndTheStorageStream() throws Exception {
    Client author = member("Upload boundary tester");
    var empty = author.upload(List.of());
    assertThat(empty.statusCode()).as(empty.body()).isEqualTo(400);
    var six = author.upload(Collections.nCopies(6, new Upload("extra.pdf", 1)));
    assertThat(six.statusCode()).as(six.body()).isEqualTo(400);
    var zero = author.upload(List.of(new Upload("empty.pdf", 0)));
    assertThat(zero.statusCode()).as(zero.body()).isEqualTo(400);
    var maximum = author.upload(List.of(new Upload("maximum.pdf", DocumentStorage.MAX_FILE_BYTES)));
    assertThat(maximum.statusCode()).as(maximum.body()).isEqualTo(201);
    String postId = json.readTree(maximum.body()).get("id").asText();
    assertThat(
            author
                .ok("GET", "/documents/" + postId, null, 200)
                .get("files")
                .get(0)
                .get("sizeBytes")
                .asLong())
        .isEqualTo(DocumentStorage.MAX_FILE_BYTES);
    author.ok("DELETE", "/documents/" + postId + "?version=0", null, 204);
    var oversized =
        author.upload(List.of(new Upload("oversized.pdf", DocumentStorage.MAX_FILE_BYTES + 1)));
    assertThat(oversized.statusCode()).as(oversized.body()).isEqualTo(413);
    assertThat(json.readTree(oversized.body()).get("code").asText()).isEqualTo("UPLOAD_TOO_LARGE");
    assertThatThrownBy(() -> storage.path("../outside")).isInstanceOf(ApiException.class);
  }

  @Test
  void failedLaterFileRollsBackMetadataAuditAndAlreadyStoredFiles() throws Exception {
    Client author = member("Rollback fixture");
    var principal =
        new PortalPrincipal(
            author.id,
            "fixture@example.test",
            "Rollback fixture",
            "USER",
            0,
            System.currentTimeMillis());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER"))));
    long previousPosts = db.sql("SELECT COUNT(*) FROM document_posts").query(Long.class).single();
    long previousAudits =
        db.sql("SELECT COUNT(*) FROM audit_log WHERE action='DOCUMENT_FILE_UPLOADED'")
            .query(Long.class)
            .single();
    Set<String> previousFiles;
    Path directory = storage.path(UUID.randomUUID().toString()).getParent();
    try (var files = Files.list(directory)) {
      previousFiles = new HashSet<>(files.map(path -> path.getFileName().toString()).toList());
    }
    var good =
        new MockMultipartFile(
            "files", "first.txt", "text/plain", "fixture".getBytes(StandardCharsets.UTF_8));
    var broken =
        new MockMultipartFile("files", "second.txt", "text/plain", new byte[] {1}) {
          @Override
          public InputStream getInputStream() throws IOException {
            throw new IOException("fixture");
          }
        };
    try {
      assertThatThrownBy(
              () -> service.create("Rollback", "Must not survive", List.of(good, broken)))
          .isInstanceOf(ApiException.class);
      assertThat(db.sql("SELECT COUNT(*) FROM document_posts").query(Long.class).single())
          .isEqualTo(previousPosts);
      assertThat(
              db.sql("SELECT COUNT(*) FROM audit_log WHERE action='DOCUMENT_FILE_UPLOADED'")
                  .query(Long.class)
                  .single())
          .isEqualTo(previousAudits);
      try (var files = Files.list(directory)) {
        assertThat(files.map(path -> path.getFileName().toString()).toList())
            .containsExactlyInAnyOrderElementsOf(previousFiles);
      }
      var falseSize =
          new MockMultipartFile("files", "untrusted-size.txt", "text/plain", new byte[] {1}) {
            @Override
            public InputStream getInputStream() {
              return new SizedInput(DocumentStorage.MAX_FILE_BYTES + 1);
            }
          };
      assertThatThrownBy(
              () -> service.create("Oversized stream", "Must not survive", List.of(falseSize)))
          .isInstanceOfSatisfying(
              ApiException.class, failure -> assertThat(failure.status().value()).isEqualTo(413));
      assertThat(db.sql("SELECT COUNT(*) FROM document_posts").query(Long.class).single())
          .isEqualTo(previousPosts);
      try (var files = Files.list(directory)) {
        assertThat(files.map(path -> path.getFileName().toString()).toList())
            .containsExactlyInAnyOrderElementsOf(previousFiles);
      }
    } finally {
      SecurityContextHolder.clearContext();
    }
  }
}
