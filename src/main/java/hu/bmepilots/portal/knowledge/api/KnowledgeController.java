package hu.bmepilots.portal.knowledge.api;

import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.knowledge.application.KnowledgeService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class KnowledgeController {
  private final KnowledgeService service;

  public KnowledgeController(KnowledgeService service) {
    this.service = service;
  }

  @GetMapping("/knowledge/categories")
  Object categories() {
    return service.categories();
  }

  @PostMapping("/admin/knowledge/categories")
  Object category(@Valid @RequestBody KnowledgeService.CategoryChange c) {
    throw retired();
  }

  @PatchMapping("/admin/knowledge/categories/{id}")
  Object category(@PathVariable String id, @Valid @RequestBody KnowledgeService.CategoryChange c) {
    throw retired();
  }

  @DeleteMapping("/admin/knowledge/categories/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void deleteCategory(@PathVariable String id) {
    throw retired();
  }

  @GetMapping("/knowledge/articles")
  Object list(
      @RequestParam(required = false) String category, @RequestParam(defaultValue = "0") int page) {
    return service.list(category, page);
  }

  @GetMapping("/knowledge/articles/{id}")
  Object get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping("/admin/knowledge/articles")
  @ResponseStatus(HttpStatus.CREATED)
  Object create(@Valid @RequestBody KnowledgeService.Change c) {
    throw retired();
  }

  @PatchMapping("/admin/knowledge/articles/{id}")
  Object update(@PathVariable String id, @Valid @RequestBody KnowledgeService.Change c) {
    throw retired();
  }

  @DeleteMapping("/admin/knowledge/articles/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestParam long version) {
    throw retired();
  }

  private ApiException retired() {
    return new ApiException(
        HttpStatus.GONE,
        "KNOWLEDGE_REPLACED",
        "Knowledge base has moved to Shared Documents. Please manage content there.");
  }
}
