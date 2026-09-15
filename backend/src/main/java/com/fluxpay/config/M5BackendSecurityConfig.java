package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.security.*;
import com.fluxpay.common.web.CorrelationIdFilter;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Profile("m5-backend") @Configuration(proxyBeanMethods=false)
public class M5BackendSecurityConfig {
  @Bean @Order(6) SecurityFilterChain m5BackendRemainder(HttpSecurity http,JwtUtil tokens,ObjectMapper json) throws Exception {
    var signedOnly=new StandardEnvironment();signedOnly.setActiveProfiles("m5-signed-only");
    return http.csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .requestCache(c -> c.disable())
        .authorizeHttpRequests(a -> a.requestMatchers("/api/payments/**").authenticated().anyRequest().denyAll())
        .exceptionHandling(e -> e.authenticationEntryPoint((r,s,x) -> M5SecurityConfig.error(json,r,s,401,"AUTH_REQUIRED","A valid signed bearer token is required"))
            .accessDeniedHandler((r,s,x) -> M5SecurityConfig.error(json,r,s,403,"FORBIDDEN","Access is forbidden")))
        .addFilterBefore(new JwtAuthFilter(tokens,signedOnly),UsernamePasswordAuthenticationFilter.class)
        .addFilterBefore(new CorrelationIdFilter(),JwtAuthFilter.class).build();
  }
}
