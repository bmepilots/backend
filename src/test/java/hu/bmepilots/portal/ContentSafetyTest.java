package hu.bmepilots.portal;

import static org.assertj.core.api.Assertions.*;

import hu.bmepilots.portal.links.application.LinksService;
import hu.bmepilots.portal.mail.infrastructure.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ContentSafetyTest {
  @TempDir Path dir;

  @Test
  void emailDropsActiveContentAndRemoteResources() {
    String output =
        new EmailSanitizer()
            .sanitize(
                "<script>alert(1)</script><img src='https://tracker.test/pixel'><form action='https://evil.test'>send</form><a href='javascript:alert(1)' onclick='x()'>bad</a><a href='https://example.com'>good</a><div style='background:url(https://tracker.test)'>hello</div>");
    assertThat(output)
        .doesNotContain(
            "<script", "<img", "<form", "javascript:", "onclick", "style=", "tracker.test")
        .contains("https://example.com", "noopener noreferrer", "hello");
  }

  @Test
  void attachmentPathsCannotEscapeStorage() throws Exception {
    var storage = new AttachmentStorage(dir.toString());
    assertThatThrownBy(() -> storage.resolve("../../secret"))
        .isInstanceOf(IllegalArgumentException.class);
    String key = storage.save("safe".getBytes());
    assertThat(storage.resolve(key)).hasContent("safe");
    storage.discard(key);
    assertThat(storage.resolve(key)).doesNotExist();
  }

  @Test
  void linksRejectExecutableSchemesAndEmbeddedCredentials() {
    assertThatThrownBy(() -> LinksService.validateUrl("javascript:alert(1)"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LinksService.validateUrl("https://user:secret@example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> LinksService.validateUrl("//example.com"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatCode(() -> LinksService.validateUrl("https://example.com/docs?q=hello"))
        .doesNotThrowAnyException();
  }
}
