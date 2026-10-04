package hu.bmepilots.portal.settings.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SettingsService {
  private final JdbcClient db;
  private final AuditService audit;

  public SettingsService(JdbcClient db, AuditService audit) {
    this.db = db;
    this.audit = audit;
  }

  public record Settings(boolean registrationEnabled, String portalName, long version) {}

  public record Change(
      boolean registrationEnabled,
      @NotBlank @Size(max = 100) String portalName,
      @Min(0) long version) {}

  public Settings get() {
    return db.sql("SELECT registration_enabled,portal_name,version FROM app_settings WHERE id=1")
        .query(Settings.class)
        .single();
  }

  @Transactional
  public Settings update(Change c) {
    if (db.sql(
                "UPDATE app_settings SET registration_enabled=?,portal_name=?,version=version+1 WHERE id=1 AND version=?")
            .params(c.registrationEnabled(), c.portalName().trim(), c.version())
            .update()
        != 1) throw ApiException.conflict();
    audit.record("SETTINGS_UPDATED", "SETTINGS", "1");
    return get();
  }
}
