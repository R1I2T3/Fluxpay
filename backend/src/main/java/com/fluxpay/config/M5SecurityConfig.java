package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.api.ApiError;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Protects M5 endpoints ahead of the frozen shared catch-all chain. */
@Configuration(proxyBeanMethods = false)
public class M5SecurityConfig {
  @Bean
  @Order(5)
  SecurityFilterChain m5SecurityFilterChain(HttpSecurity http, JwtAuthFilter jwt,
      ObjectProvider<ObjectMapper> mapper, ObjectProvider<CorrelationIdFilter> correlation) throws Exception {
    ObjectMapper json = mapper.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
    return http.securityMatcher("/api/compliance/**", "/api/policies/**", "/api/copilot/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, "/api/compliance/payments/*/passport").authenticated()
            .anyRequest().hasRole("ADMIN"))
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, cause) -> error(json, request, response, 401, "AUTH_REQUIRED", "A valid signed bearer token is required"))
            .accessDeniedHandler((request, response, cause) -> error(json, request, response, 403, "FORBIDDEN", "ADMIN access is required")))
        .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(correlation.getIfAvailable(CorrelationIdFilter::new), JwtAuthFilter.class)
        .build();
  }

  /** The same real filter runs inside the selected chain, not twice as a servlet filter. */
  @Bean
  FilterRegistrationBean<JwtAuthFilter> m5JwtServletRegistration(JwtAuthFilter filter) {
    var registration = new FilterRegistrationBean<>(filter);
    registration.setEnabled(false);
    return registration;
  }

  public static void error(ObjectMapper json, HttpServletRequest request, HttpServletResponse response,
      int status, String code, String message) throws IOException {
    String correlation = MDC.get("correlationId");
    if (correlation == null || correlation.isBlank()) correlation = UUID.randomUUID().toString();
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader(CorrelationIdFilter.HEADER, correlation);
    json.writeValue(response.getOutputStream(), new ApiError(correlation, code, message, Map.of(), Instant.now()));
  }
}
