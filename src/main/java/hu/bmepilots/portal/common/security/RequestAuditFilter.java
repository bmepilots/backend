package hu.bmepilots.portal.common.security;

import hu.bmepilots.portal.audit.application.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

/** Records metadata only: never request bodies, query strings, headers or session identifiers. */
public class RequestAuditFilter extends OncePerRequestFilter {
  private static final Logger LOG = LoggerFactory.getLogger(RequestAuditFilter.class);
  private static final Set<String> AUTH_ROUTES =
      Set.of(
          "/api/v1/auth/login",
          "/api/v1/auth/logout",
          "/api/v1/auth/register",
          "/api/v1/auth/csrf");
  private final AuditService audit;

  public RequestAuditFilter(AuditService audit) {
    this.audit = audit;
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !request.getRequestURI().startsWith("/api/v1/");
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    long start = System.nanoTime();
    String initialActor = CurrentUser.optionalId();
    boolean failed = false;
    try {
      chain.doFilter(req, res);
    } catch (ServletException | IOException | RuntimeException error) {
      failed = true;
      throw error;
    } finally {
      String actor = CurrentUser.optionalId();
      if (actor == null) actor = initialActor;
      Object template = req.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
      String route =
          template instanceof String s
              ? s
              : AUTH_ROUTES.contains(req.getRequestURI())
                  ? req.getRequestURI()
                  : "/api/v1/[unmatched]";
      int status = failed ? 500 : res.getStatus();
      long duration = (System.nanoTime() - start) / 1_000_000;
      String method =
          Set.of("GET", "POST", "PATCH", "PUT", "DELETE", "HEAD", "OPTIONS")
                  .contains(req.getMethod())
              ? req.getMethod()
              : "OTHER";
      LOG.info(
          "API request actor={} method={} route={} status={} durationMs={}",
          actor,
          method,
          route,
          status,
          duration);
      try {
        audit.request(actor, method, route, status, duration);
        if (route.equals("/api/v1/auth/login") && status >= 400) {
          audit.recordAs(
              null, status == 429 ? "LOGIN_RATE_LIMITED" : "LOGIN_FAILED", "AUTH", "session");
        }
        if (route.equals("/api/v1/auth/register")) {
          audit.recordAs(
              actor,
              status < 400 ? "REGISTRATION_REQUESTED" : "REGISTRATION_REJECTED",
              "AUTH",
              "registration");
        }
      } catch (RuntimeException auditFailure) {
        // Preserve the original response if operational logging fails. Domain mutations
        // still commit their audit entry within their own transaction.
        LOG.error("Request audit unavailable type={}", auditFailure.getClass().getSimpleName());
      }
    }
  }
}
