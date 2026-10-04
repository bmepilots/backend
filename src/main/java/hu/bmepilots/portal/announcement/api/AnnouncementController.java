package hu.bmepilots.portal.announcement.api;

import hu.bmepilots.portal.announcement.application.AnnouncementService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AnnouncementController {
  private final AnnouncementService service;

  public AnnouncementController(AnnouncementService service) {
    this.service = service;
  }

  @GetMapping("/announcements")
  Object list(@RequestParam(defaultValue = "0") int page) {
    return service.list(page);
  }

  @GetMapping("/announcements/{id}")
  Object get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping("/admin/announcements")
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@Valid @RequestBody AnnouncementService.Change c) {
    return service.create(c);
  }

  @PatchMapping("/admin/announcements/{id}")
  Object update(@PathVariable String id, @Valid @RequestBody AnnouncementService.Change c) {
    return service.update(id, c);
  }

  @DeleteMapping("/admin/announcements/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(id, version);
  }
}
