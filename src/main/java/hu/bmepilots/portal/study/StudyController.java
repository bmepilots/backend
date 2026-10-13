package hu.bmepilots.portal.study;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/study")
public class StudyController {
  private final StudyCatalog catalog;
  private final StudyService service;

  public StudyController(StudyCatalog catalog, StudyService service) {
    this.catalog = catalog;
    this.service = service;
  }

  @GetMapping("/subjects")
  Object subjects() {
    return java.util.List.of(catalog.publicView());
  }

  @GetMapping("/progress")
  Object progress() {
    return service.progress();
  }

  @PostMapping("/attempts")
  Object submit(@Valid @RequestBody StudyService.Attempt attempt) {
    return service.submit(attempt);
  }

  @GetMapping("/attempts/{id}")
  Object result(@PathVariable String id) {
    return service.result(id);
  }
}
