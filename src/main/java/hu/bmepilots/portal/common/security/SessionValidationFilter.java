package hu.bmepilots.portal.common.security;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.user.application.UserService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

public class SessionValidationFilter extends OncePerRequestFilter {
  private static final Set<String> RECOVERY_ROUTES =
      Set.of(
          "/api/v1/auth/csrf",
          "/api/v1/auth/login",
          "/api/v1/auth/register",
          "/api/v1/public/config");
  private final UserService users;
  private final AuditService audit;

  public SessionValidationFilter(UserService users, AuditService audit) {
    this.users = users;
    this.audit = audit;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    if (auth != null && auth.getPrincipal() instanceof PortalPrincipal p && !users.valid(p)) {
      audit.recordAs(p.id(), "SESSION_REVOKED", "USER", p.id());
      if (req.getSession(false) != null) req.getSession(false).invalidate();
      SecurityContextHolder.clearContext();
      // An old cookie must not prevent the login page from establishing a fresh
      // anonymous session. CSRF and normal authorization still run afterward.
      if (RECOVERY_ROUTES.contains(req.getRequestURI())) {
        chain.doFilter(req, res);
        return;
      }
      res.setStatus(401);
      res.setContentType("application/problem+json");
      res.getWriter()
          .write(
              "{\"status\":401,\"code\":\"SESSION_EXPIRED\",\"detail\":\"Your session has expired. Please sign in again.\"}");
      return;
    }
    chain.doFilter(req, res);
  }
}
