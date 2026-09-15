package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.web.CorrelationIdFilter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/** Signed JWTs remain mandatory for risk APIs even in a combined application using local mode. */
@Profile("m5-risk")
@Configuration(proxyBeanMethods = false)
public class M5RiskSecurityConfig {
  @Bean
  @Order(4)
  SecurityFilterChain m5RiskSecurityFilterChain(HttpSecurity http, JwtUtil tokens,
      ObjectProvider<ObjectMapper> mapper) throws Exception {
    var signedOnly = new StandardEnvironment();
    signedOnly.setActiveProfiles("m5-risk-signed-jwt-only");
    // This instance belongs only to this chain: no servlet registration or changes to shared auth.
    var jwt = new JwtAuthFilter(tokens, signedOnly);
    ObjectMapper json = mapper.getIfAvailable(() -> new ObjectMapper().findAndRegisterModules());
    return http.securityMatcher("/api/compliance/**", "/api/policies/**", "/api/copilot/**")
        .csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.GET, "/api/compliance/payments/*/passport").authenticated()
            .anyRequest().hasRole("ADMIN"))
        .exceptionHandling(errors -> errors
            .authenticationEntryPoint((request, response, cause) -> M5SecurityConfig.error(
                json, request, response, 401, "AUTH_REQUIRED", "A valid signed bearer token is required"))
            .accessDeniedHandler((request, response, cause) -> M5SecurityConfig.error(
                json, request, response, 403, "FORBIDDEN", "ADMIN access is required")))
        .addFilterBefore(jwt, UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new CorrelationIdFilter(), JwtAuthFilter.class)
        .build();
  }
}
