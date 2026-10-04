package hu.bmepilots.portal.settings.api;

import hu.bmepilots.portal.settings.application.SettingsService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
public class SettingsController {
  private final SettingsService service;

  public SettingsController(SettingsService service) {
    this.service = service;
  }

  @GetMapping("/api/v1/public/config")
  Object config() {
    var s = service.get();
    return new PublicConfig(s.registrationEnabled(), s.portalName());
  }

  record PublicConfig(boolean registrationEnabled, String portalName) {}

  @GetMapping("/api/v1/admin/settings")
  Object get() {
    return service.get();
  }

  @PatchMapping("/api/v1/admin/settings")
  Object update(@Valid @RequestBody SettingsService.Change c) {
    return service.update(c);
  }
}
