package hu.bmepilots.portal.calendar.api;

import hu.bmepilots.portal.calendar.application.CalendarService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/calendar/events")
public class CalendarController {
  private final CalendarService service;

  public CalendarController(CalendarService service) {
    this.service = service;
  }

  @GetMapping
  List<CalendarService.Event> list(
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
      @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
    return service.list(from, to);
  }

  @GetMapping("/{id}")
  CalendarService.Event get(@PathVariable String id) {
    return service.get(id);
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  CalendarService.Event create(@Valid @RequestBody CalendarService.Change change) {
    return service.save(null, change);
  }

  @PatchMapping("/{id}")
  CalendarService.Event update(
      @PathVariable String id, @Valid @RequestBody CalendarService.Change change) {
    return service.save(id, change);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  void delete(@PathVariable String id, @RequestParam long version) {
    service.delete(id, version);
  }
}
