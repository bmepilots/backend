package hu.bmepilots.portal.common.security;

import org.springframework.security.core.context.SecurityContextHolder;

public final class CurrentUser {
  private CurrentUser() {}

  public static PortalPrincipal principal() {
    return (PortalPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  public static String id() {
    return principal().id();
  }

  public static String optionalId() {
    var auth = SecurityContextHolder.getContext().getAuthentication();
    return auth != null && auth.getPrincipal() instanceof PortalPrincipal p ? p.id() : null;
  }
}
