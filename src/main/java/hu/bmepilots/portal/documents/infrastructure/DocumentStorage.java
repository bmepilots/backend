package hu.bmepilots.portal.documents.infrastructure;

import hu.bmepilots.portal.common.error.ApiException;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** Private, streaming storage. Only server-generated UUID keys become filesystem paths. */
@Component
public class DocumentStorage {
  public static final long MAX_FILE_BYTES = 50L * 1024 * 1024;
  private final Path root;

  public DocumentStorage(
      @Value("${portal.documents.storage:./storage/documents}") String directory) {
    root = Path.of(directory).toAbsolutePath().normalize();
    try {
      Files.createDirectories(root);
    } catch (IOException e) {
      throw new IllegalStateException("Document storage is unavailable.");
    }
  }

  public record Stored(String key, long sizeBytes) {}

  public Stored save(MultipartFile upload) {
    String key = UUID.randomUUID().toString();
    Path destination = path(key);
    Path temporary = root.resolve(key + ".part");
    long size = 0;
    try {
      try (var input = upload.getInputStream();
          var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE_NEW)) {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) {
          size += read;
          if (size > MAX_FILE_BYTES) throw tooLarge();
          output.write(buffer, 0, read);
        }
      }
      if (size == 0) {
        throw new ApiException(
            HttpStatus.BAD_REQUEST, "VALIDATION", "Empty files cannot be uploaded.");
      }
      try {
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
      } catch (AtomicMoveNotSupportedException e) {
        Files.move(temporary, destination);
      }
      return new Stored(key, size);
    } catch (IOException e) {
      throw new ApiException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "STORAGE_UNAVAILABLE",
          "The file could not be stored. Please try again.");
    } finally {
      remove(temporary);
    }
  }

  public Path path(String key) {
    if (key == null
        || !key.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
      throw ApiException.missing();
    }
    return root.resolve(key);
  }

  public void delete(String key) {
    remove(path(key));
  }

  private void remove(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      LoggerFactory.getLogger(DocumentStorage.class)
          .warn("Document storage cleanup failed; an operator should check orphaned files.");
    }
  }

  public static ApiException tooLarge() {
    return new ApiException(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "UPLOAD_TOO_LARGE",
        "Each file must be 50 MB or smaller; attach up to 5 files per post.");
  }
}
