package hu.bmepilots.portal.common.security;

import hu.bmepilots.portal.audit.application.AuditService;
import hu.bmepilots.portal.user.application.UserService;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.*;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  SecurityFilterChain security(HttpSecurity http, UserService users, AuditService audit)
      throws Exception {
    return http.csrf(
            csrf ->
                csrf.csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/api/v1/auth/csrf",
                        "/api/v1/auth/login",
                        "/api/v1/auth/register",
                        "/api/v1/public/config",
                        "/actuator/health",
                        "/error")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .requestMatchers("/api/v1/**")
                    .authenticated()
                    .anyRequest()
                    .denyAll())
        .addFilterAfter(new RequestAuditFilter(audit), SecurityContextHolderFilter.class)
        .addFilterAfter(new SessionValidationFilter(users, audit), RequestAuditFilter.class)
        .requestCache(c -> c.disable())
        .logout(
            l ->
                l.logoutUrl("/api/v1/auth/logout")
                    .deleteCookies("BMESESSION")
                    .logoutSuccessHandler(
                        (q, r, a) -> {
                          if (a != null && a.getPrincipal() instanceof PortalPrincipal p)
                            audit.recordAs(p.id(), "LOGOUT", "USER", p.id());
                          r.setStatus(204);
                        }))
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (q, r, x) -> {
                          r.setStatus(401);
                          r.setContentType("application/problem+json");
                          r.getWriter()
                              .write(
                                  "{\"status\":401,\"code\":\"UNAUTHORIZED\",\"detail\":\"Please sign in to continue.\"}");
                        })
                    .accessDeniedHandler(
                        (q, r, x) -> {
                          r.setStatus(403);
                          r.setContentType("application/problem+json");
                          r.getWriter()
                              .write(
                                  "{\"status\":403,\"code\":\"FORBIDDEN\",\"detail\":\"Permission denied or security token expired. Please refresh the page.\"}");
                        }))
        .headers(
            h ->
                h.contentSecurityPolicy(
                        c -> c.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                    .referrerPolicy(
                        r ->
                            r.policy(
                                org.springframework.security.web.header.writers
                                    .ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
        .build();
  }
}
