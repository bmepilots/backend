package hu.bmepilots.portal.user.application;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.common.security.PortalPrincipal;
import hu.bmepilots.portal.settings.application.SettingsService;
import jakarta.validation.constraints.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {
  private final JdbcClient db;
  private final PasswordEncoder encoder;
  private final SettingsService settings;
  private final AuditService audit;
  private final String dummyHash;

  public UserService(
      JdbcClient db, PasswordEncoder encoder, SettingsService settings, AuditService audit) {
    this.db = db;
    this.encoder = encoder;
    this.settings = settings;
    this.audit = audit;
    this.dummyHash = encoder.encode(UUID.randomUUID().toString());
  }

  public record Registration(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 2, max = 100) String displayName,
      @NotBlank @Size(min = 12, max = 128) String password) {}

  public record Login(
      @NotBlank @Email @Size(max = 254) String email, @NotBlank @Size(max = 128) String password) {}

  public record View(
      String id,
      String email,
      String displayName,
      String status,
      String role,
      long version,
      LocalDateTime createdAt) {}

  public record Credentials(
      String id,
      String email,
      String displayName,
      String passwordHash,
      String status,
      long authVersion,
      String role) {}

  public record PasswordChange(
      @NotBlank @Size(max = 128) String currentPassword,
      @NotBlank @Size(min = 12, max = 128) String newPassword) {}

  private static final String VIEW =
      "SELECT u.id,u.email,u.display_name,u.status,CASE WHEN EXISTS(SELECT 1 FROM user_roles r WHERE r.user_id=u.id AND r.role_code='ADMIN') THEN 'ADMIN' ELSE 'USER' END role,u.version,u.created_at FROM users u";

  private static String normalize(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public void register(Registration r) {
    if (!settings.get().registrationEnabled())
      throw new ApiException(
          HttpStatus.FORBIDDEN, "REGISTRATION_CLOSED", "Registration is currently closed.");
    // A generic response prevents email enumeration through registration.
    if (db.sql("SELECT COUNT(*) FROM users WHERE email_normalized=?")
            .param(normalize(r.email()))
            .query(Long.class)
            .single()
        > 0) return;
    create(r.email(), r.displayName(), r.password(), "PENDING_APPROVAL", "USER");
  }

  private String create(String email, String name, String password, String status, String role) {
    String id = UUID.randomUUID().toString();
    db.sql(
            "INSERT INTO users(id,email,email_normalized,display_name,password_hash,status) VALUES (?,?,?,?,?,?)")
        .params(id, email.trim(), normalize(email), name.trim(), encoder.encode(password), status)
        .update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,?)").params(id, role).update();
    audit.record("USER_CREATED", "USER", id);
    return id;
  }

  @Transactional
  public void bootstrap(String email, String password) {
    db.sql("SELECT code FROM roles WHERE code='ADMIN' FOR UPDATE").query(String.class).single();
    if (db.sql("SELECT COUNT(*) FROM user_roles WHERE role_code='ADMIN'").query(Long.class).single()
        > 0) return;
    if (password.length() < 16 || email.isBlank())
      throw new IllegalArgumentException(
          "Bootstrap requires email and a password of at least 16 characters.");
    create(email, "Community admin", password, "ACTIVE", "ADMIN");
  }

  public PortalPrincipal authenticate(Login input) {
    var found =
        db.sql(
                "SELECT u.id,u.email,u.display_name,u.password_hash,u.status,u.auth_version,CASE WHEN EXISTS(SELECT 1 FROM user_roles r WHERE r.user_id=u.id AND r.role_code='ADMIN') THEN 'ADMIN' ELSE 'USER' END role FROM users u WHERE u.email_normalized=?")
            .param(normalize(input.email()))
            .query(Credentials.class)
            .optional();
    boolean correct =
        encoder.matches(input.password(), found.map(Credentials::passwordHash).orElse(dummyHash));
    if (!correct || found.isEmpty())
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid credentials or inactive account.");
    var u = found.get();
    if (!u.status().equals("ACTIVE"))
      throw new ApiException(
          HttpStatus.UNAUTHORIZED, "LOGIN_FAILED", "Invalid credentials or inactive account.");
    return new PortalPrincipal(
        u.id(), u.email(), u.displayName(), u.role(), u.authVersion(), System.currentTimeMillis());
  }

  public boolean valid(PortalPrincipal p) {
    return System.currentTimeMillis() - p.issuedAt() < 43_200_000L
        && db.sql("SELECT COUNT(*) FROM users WHERE id=? AND status='ACTIVE' AND auth_version=?")
                .params(p.id(), p.authVersion())
                .query(Long.class)
                .single()
            == 1;
  }

  public View get(String id) {
    return db.sql(VIEW + " WHERE u.id=?")
        .param(id)
        .query(View.class)
        .optional()
        .orElseThrow(ApiException::missing);
  }

  public long pendingCount() {
    return db.sql("SELECT COUNT(*) FROM users WHERE status='PENDING_APPROVAL'")
        .query(Long.class)
        .single();
  }

  public List<View> list(String status, int page) {
    if (status != null
        && !Set.of("PENDING_APPROVAL", "ACTIVE", "REJECTED", "SUSPENDED", "DISABLED")
            .contains(status)) throw new IllegalArgumentException();
    return db.sql(
            VIEW
                + (status == null ? "" : " WHERE u.status=:status")
                + " ORDER BY u.created_at DESC,u.id LIMIT 50 OFFSET :offset")
        .param("status", status)
        .param("offset", Math.max(0, Math.min(page, 10000)) * 50)
        .query(View.class)
        .list();
  }

  private void lockAdminGuard() {
    db.sql("SELECT code FROM roles WHERE code='ADMIN' FOR UPDATE").query(String.class).single();
  }

  private void protectLastAdmin(View u) {
    if (u.status().equals("ACTIVE")
        && u.role().equals("ADMIN")
        && db.sql(
                    "SELECT COUNT(*) FROM users u JOIN user_roles r ON r.user_id=u.id WHERE u.status='ACTIVE' AND r.role_code='ADMIN'")
                .query(Long.class)
                .single()
            <= 1)
      throw new ApiException(
          HttpStatus.CONFLICT,
          "LAST_ADMIN",
          "The last active administrator cannot be disabled or demoted.");
  }

  @Transactional
  public View transition(String id, String target, long version) {
    lockAdminGuard();
    var u = get(id);
    boolean allowed =
        switch (target) {
          case "ACTIVE" -> Set.of("PENDING_APPROVAL", "SUSPENDED", "DISABLED").contains(u.status());
          case "REJECTED" -> u.status().equals("PENDING_APPROVAL");
          case "SUSPENDED" -> u.status().equals("ACTIVE");
          case "DISABLED" -> Set.of("ACTIVE", "SUSPENDED").contains(u.status());
          default -> false;
        };
    if (!allowed) throw ApiException.conflict();
    if (!target.equals("ACTIVE")) protectLastAdmin(u);
    if (db.sql(
                "UPDATE users SET status=?,auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=?")
            .params(target, id, version)
            .update()
        != 1) throw ApiException.conflict();
    audit.record(
        target.equals("ACTIVE") && u.status().equals("PENDING_APPROVAL")
            ? "USER_APPROVED"
            : "USER_" + target,
        "USER",
        id);
    return get(id);
  }

  @Transactional
  public View role(String id, String role, long version) {
    if (!Set.of("USER", "ADMIN").contains(role)) throw new IllegalArgumentException();
    lockAdminGuard();
    var u = get(id);
    if (role.equals("USER")) protectLastAdmin(u);
    if (db.sql(
                "UPDATE users SET auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=? AND version=?")
            .params(id, version)
            .update()
        != 1) throw ApiException.conflict();
    db.sql("DELETE FROM user_roles WHERE user_id=?").param(id).update();
    db.sql("INSERT INTO user_roles(user_id,role_code) VALUES (?,?)").params(id, role).update();
    audit.record("ROLE_CHANGED", "USER", id);
    return get(id);
  }

  @Transactional
  public void changePassword(String id, PasswordChange c) {
    String hash =
        db.sql("SELECT password_hash FROM users WHERE id=?").param(id).query(String.class).single();
    if (!encoder.matches(c.currentPassword(), hash))
      throw new ApiException(
          HttpStatus.BAD_REQUEST, "PASSWORD_MISMATCH", "The current password is incorrect.");
    db.sql(
            "UPDATE users SET password_hash=?,auth_version=auth_version+1,version=version+1,updated_at=CURRENT_TIMESTAMP(6) WHERE id=?")
        .params(encoder.encode(c.newPassword()), id)
        .update();
    audit.record("PASSWORD_CHANGED", "USER", id);
  }
}
