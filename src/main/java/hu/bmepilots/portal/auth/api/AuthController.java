package hu.bmepilots.portal.auth.api;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.common.error.ApiException;
import hu.bmepilots.portal.user.application.UserService;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  private final UserService users;
  private final AuditService audit;
  private final Map<String, Window> attempts = new ConcurrentHashMap<>();

  private record Window(long start, int count) {}

  public AuthController(UserService users, AuditService audit) {
    this.users = users;
    this.audit = audit;
  }

  @GetMapping("/csrf")
  Object csrf(CsrfToken token) {
    return Map.of("token", token.getToken(), "headerName", token.getHeaderName());
  }

  private synchronized void limit(String key, int maximum) {
    long now = System.currentTimeMillis();
    attempts.entrySet().removeIf(e -> now - e.getValue().start() > 900_000);
    var old = attempts.getOrDefault(key, new Window(now, 0));
    if (old.count() >= maximum)
      throw new ApiException(
          HttpStatus.TOO_MANY_REQUESTS,
          "RATE_LIMIT",
          "Too many attempts. Please try again in 15 minutes.");
    if (attempts.size() > 10000)
      throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT", "Please try again later.");
    attempts.put(key, new Window(old.start(), old.count() + 1));
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.ACCEPTED)
  Object register(@Valid @RequestBody UserService.Registration input, HttpServletRequest req) {
    limit("register:" + req.getRemoteAddr(), 10);
    users.register(input);
    return Map.of(
        "message",
        "If this is a new application, it is awaiting administrator approval. You can sign in once approved.");
  }

  @PostMapping("/login")
  Object login(
      @Valid @RequestBody UserService.Login input,
      HttpServletRequest req,
      HttpServletResponse res) {
    limit("ip:" + req.getRemoteAddr(), 60);
    limit("email:" + input.email().trim().toLowerCase(Locale.ROOT), 15);
    var p = users.authenticate(input);
    audit.recordAs(p.id(), "LOGIN_SUCCEEDED", "USER", p.id());
    if (req.getSession(false) != null) req.getSession(false).invalidate();
    req.getSession(true);
    var context = SecurityContextHolder.createEmptyContext();
    context.setAuthentication(
        UsernamePasswordAuthenticationToken.authenticated(
            p, null, List.of(new SimpleGrantedAuthority("ROLE_" + p.role()))));
    SecurityContextHolder.setContext(context);
    new HttpSessionSecurityContextRepository().saveContext(context, req, res);
    return users.get(p.id());
  }
}
