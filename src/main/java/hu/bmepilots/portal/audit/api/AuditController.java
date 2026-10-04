package hu.bmepilots.portal.audit.api;

import hu.bmepilots.portal.audit.application.AuditService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/audit-log")
public class AuditController {
  private final AuditService service;

  public AuditController(AuditService service) {
    this.service = service;
  }

  @GetMapping
  Object list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "false") boolean requests) {
    return service.list(page, requests);
  }
}
