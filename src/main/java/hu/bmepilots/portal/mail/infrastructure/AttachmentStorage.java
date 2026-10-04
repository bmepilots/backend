package hu.bmepilots.portal.mail.infrastructure;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AttachmentStorage {
  private final Path root;

  public AttachmentStorage(@Value("${portal.mail.storage}") String directory) throws IOException {
    root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  public Path resolve(String key) {
    UUID.fromString(key);
    Path p = root.resolve(key).normalize();
    if (!p.startsWith(root)) throw new IllegalArgumentException();
    return p;
  }

  public String save(byte[] bytes) throws IOException {
    String key = UUID.randomUUID().toString();
    Path tmp = Files.createTempFile(root, "import-", ".tmp");
    try {
      Files.write(tmp, bytes);
      Files.move(tmp, resolve(key), StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(tmp);
    }
    return key;
  }

  public void discard(String key) {
    try {
      Files.deleteIfExists(resolve(key));
    } catch (IOException ignored) {
      /* Unreferenced files can be removed by a future maintenance job. */
    }
  }
}
