package hu.bmepilots.portal.mail.api;

import hu.bmepilots.portal.common.security.CurrentUser;
import hu.bmepilots.portal.mail.application.*;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class MailController {
  private final MailService service;
  private final MailSyncService sync;

  public MailController(MailService service, MailSyncService sync) {
    this.service = service;
    this.sync = sync;
  }

  @GetMapping("/mail/messages")
  Object list(
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "false") boolean unread,
      @RequestParam(defaultValue = "0") int page) {
    return service.list(CurrentUser.id(), q, unread, page);
  }

  @GetMapping("/mail/messages/{id}")
  Object get(@PathVariable String id) {
    return service.get(id);
  }

  @RequestMapping(
      value = "/mail/messages/{id}/state",
      method = {RequestMethod.PUT, RequestMethod.PATCH})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void state(@PathVariable String id, @RequestBody MailService.State s) {
    service.state(CurrentUser.id(), id, s);
  }

  @GetMapping("/mail/messages/{messageId}/attachments/{attachmentId}/download")
  ResponseEntity<FileSystemResource> download(
      @PathVariable String messageId, @PathVariable String attachmentId) {
    var file = service.download(messageId, attachmentId);
    return ResponseEntity.ok()
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .cacheControl(CacheControl.noStore())
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.filename(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(new FileSystemResource(file.path()));
  }

  @GetMapping("/admin/mail/status")
  Object status() {
    return sync.status();
  }

  @PostMapping("/admin/mail/sync")
  @ResponseStatus(HttpStatus.ACCEPTED)
  void start() {
    sync.requestSync();
  }

  @PostMapping("/admin/mail/test-connection")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void test() {
    sync.test();
  }

  @PostMapping("/admin/mail/retry")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void retry() {
    sync.retryFailures();
  }
}
