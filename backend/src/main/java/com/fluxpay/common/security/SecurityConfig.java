package com.fluxpay.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
  private final JwtUtil jwtUtil;
  private final ObjectMapper objectMapper;

  public SecurityConfig(JwtUtil jwtUtil, ObjectMapper objectMapper) {
    this.jwtUtil = jwtUtil;
    this.objectMapper = objectMapper;
  }

  @Bean
  public BCryptPasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    return http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/api/auth/**", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (request, response, exception) ->
                            writeSecurityError(
                                request,
                                response,
                                HttpStatus.UNAUTHORIZED,
                                "AUTH_REQUIRED",
                                "authentication is required"))
                    .accessDeniedHandler(
                        (request, response, exception) ->
                            writeSecurityError(
                                request,
                                response,
                                HttpStatus.FORBIDDEN,
                                "FORBIDDEN",
                                "access is forbidden")))
        .addFilterBefore(new JwtAuthFilter(jwtUtil), UsernamePasswordAuthenticationFilter.class)
        .build();
  }

  private void writeSecurityError(
      HttpServletRequest request,
      HttpServletResponse response,
      HttpStatus status,
      String code,
      String message)
      throws IOException {
    String correlationId = MDC.get("correlationId");
    if (correlationId == null) {
      correlationId = request.getHeader(CorrelationIdFilter.HEADER);
    }
    if (correlationId == null || correlationId.isBlank()) {
      correlationId = UUID.randomUUID().toString();
    }
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setHeader(CorrelationIdFilter.HEADER, correlationId);
    objectMapper.writeValue(
        response.getOutputStream(),
        new ApiError(correlationId, code, message, Map.of(), Instant.now()));
  }
}
