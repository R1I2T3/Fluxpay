package com.fluxpay.common.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
  private final JwtUtil jwt;
  private final Environment environment;

  public JwtAuthFilter(JwtUtil jwt, Environment environment) {
    this.jwt = jwt;
    this.environment = environment;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String h = req.getHeader("Authorization");
    if (h != null && h.startsWith("Bearer ")) {
      try {
        CurrentUser u = jwt.parse(h.substring(7));
        var auth =
            new UsernamePasswordAuthenticationToken(
                u, null, List.of(new SimpleGrantedAuthority("ROLE_" + u.role())));
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
      } catch (Exception ignored) {
        SecurityContextHolder.clearContext();
      }
    }
    if (SecurityContextHolder.getContext().getAuthentication() == null
        && environment.acceptsProfiles(Profiles.of("local"))) {
      String localUserId = req.getHeader("X-Local-User-Id");
      if (localUserId != null) {
        try {
          CurrentUser u =
              new CurrentUser(UUID.fromString(localUserId), "local-test@fluxpay", "CUSTOMER");
          var localAuth =
              new UsernamePasswordAuthenticationToken(
                  u, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER")));
          SecurityContext localContext = SecurityContextHolder.createEmptyContext();
          localContext.setAuthentication(localAuth);
          SecurityContextHolder.setContext(localContext);
        } catch (IllegalArgumentException ignored) {
          SecurityContextHolder.clearContext();
        }
      }
    }
    chain.doFilter(req, res);
  }
}
