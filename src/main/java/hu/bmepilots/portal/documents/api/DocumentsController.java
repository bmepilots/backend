package hu.bmepilots.portal.documents.api;

import hu.bmepilots.portal.documents.application.DocumentsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/documents")
public class DocumentsController {
  private final DocumentsService service;

  public DocumentsController(DocumentsService service) {
    this.service = service;
  }

  @GetMapping
  Object list(
      @RequestParam(defaultValue = "") @Size(max = 200) String q,
      @RequestParam(defaultValue = "0") int page) {
    return service.list(q, page);
  }

  @GetMapping("/{id}")
  Object detail(@PathVariable String id) {
    return service.detail(id);
  }

  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  Object create(
      @RequestParam String title,
      @RequestParam(defaultValue = "") String description,
      @RequestParam(required = false) List<MultipartFile> files) {
    return service.create(title, description, files);
  }

  @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  Object publish(@Valid @RequestBody DocumentsService.Publish change) {
    return service.publish(change);
  }

  @PostMapping(value = "/uploads", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  Object stage(@RequestParam List<MultipartFile> file) {
    return service.stage(file);
  }

  @DeleteMapping("/uploads/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void discard(@PathVariable String id) {
    service.discard(id);
  }

  @PatchMapping("/{id}")
  Object update(@PathVariable String id, @Valid @RequestBody DocumentsService.Change change) {
    return service.update(id, change);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(id, version);
  }

  @PostMapping("/{id}/comments")
  @ResponseStatus(HttpStatus.CREATED)
  Object comment(@PathVariable String id, @Valid @RequestBody DocumentsService.NewComment change) {
    return service.comment(id, change);
  }

  @PatchMapping("/{id}/comments/{commentId}")
  Object updateComment(
      @PathVariable String id,
      @PathVariable String commentId,
      @Valid @RequestBody DocumentsService.CommentChange change) {
    return service.updateComment(id, commentId, change);
  }

  @DeleteMapping("/{id}/comments/{commentId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteComment(
      @PathVariable String id, @PathVariable String commentId, @RequestParam long version) {
    service.deleteComment(id, commentId, version);
  }

  @GetMapping("/{postId}/files/{fileId}/download")
  ResponseEntity<FileSystemResource> download(
      @PathVariable String postId, @PathVariable String fileId) {
    var file = service.download(postId, fileId);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .cacheControl(CacheControl.noStore())
        .contentLength(file.sizeBytes())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new FileSystemResource(file.path()));
  }
}
