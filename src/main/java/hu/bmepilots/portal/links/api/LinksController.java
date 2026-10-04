package hu.bmepilots.portal.links.api;

import hu.bmepilots.portal.links.application.LinksService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class LinksController {
  private final LinksService service;

  public LinksController(LinksService service) {
    this.service = service;
  }

  @GetMapping("/links")
  Object list() {
    return service.list();
  }

  @GetMapping("/links/categories")
  Object categories() {
    return service.categories();
  }

  @PostMapping("/admin/links/categories")
  Object category(@Valid @RequestBody LinksService.CategoryChange c) {
    return service.category(null, c);
  }

  @PatchMapping("/admin/links/categories/{id}")
  Object category(@PathVariable String id, @Valid @RequestBody LinksService.CategoryChange c) {
    return service.category(id, c);
  }

  @DeleteMapping("/admin/links/categories/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteCategory(@PathVariable String id) {
    service.deleteCategory(id);
  }

  @PostMapping({"/links", "/admin/links"})
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@Valid @RequestBody LinksService.Change c) {
    return service.save(null, c);
  }

  @PatchMapping({"/links/{id}", "/admin/links/{id}"})
  Object update(@PathVariable String id, @Valid @RequestBody LinksService.Change c) {
    return service.save(id, c);
  }

  @DeleteMapping({"/links/{id}", "/admin/links/{id}"})
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(id, version);
  }
}
