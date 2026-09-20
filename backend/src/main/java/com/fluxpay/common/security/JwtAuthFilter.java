package com.fluxpay.common.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger(JwtAuthFilter.class);
  private final JwtUtil jwt;

  public JwtAuthFilter(JwtUtil jwt) {
    this.jwt = jwt;
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
      } catch (Exception exception) {
        // Do not log the bearer token. The exception type and request path are enough to diagnose
        // why Spring Security treated this request as anonymous.
        log.warn(
            "JWT authentication failed for {}: {}",
            req.getRequestURI(),
            exception.getClass().getSimpleName());
        SecurityContextHolder.clearContext();
      }
    }
    chain.doFilter(req, res);
  }
}
