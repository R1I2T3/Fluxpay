package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.api.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fluxpay.common.security.*;
import com.fluxpay.common.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class M5SecurityWebTest {
  private AnnotationConfigWebApplicationContext context;
  private MockMvc mvc;
  private JwtUtil jwt;

  @BeforeEach
  void startRealSecurity() {
    context = new AnnotationConfigWebApplicationContext();
    context.setServletContext(new MockServletContext());
    context.register(WebFixture.class);
    try {
      context.register(Class.forName("com.fluxpay.config.M5SecurityConfig"));
    } catch (ClassNotFoundException missing) {
      fail("M5 route-scoped security has not been implemented");
    }
    context.refresh();
    jwt = context.getBean(JwtUtil.class);
    mvc = MockMvcBuilders.webAppContextSetup(context)
        .addFilters(new CorrelationIdFilter(), context.getBean("springSecurityFilterChain", Filter.class))
        .build();
  }

  @AfterEach void stop() { if (context != null) context.close(); }

  @Test void missingOrInvalidSignedIdentityCannotReachOpenSharedPolicyRoute() throws Exception {
    mvc.perform(get("/api/policies").header("X-Role", "ADMIN").header("X-User-Id", UUID.randomUUID()))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"))
        .andExpect(jsonPath("$.correlationId").isNotEmpty()).andExpect(jsonPath("$.fieldErrors").isMap())
        .andExpect(jsonPath("$.ts").isString());
    mvc.perform(get("/api/policies").header("Authorization", "Bearer eyJhbGciOiJub25lIn0.e30."))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  @Test void signedUserIsForbiddenButSignedAdminCanReachOperatorRoutes() throws Exception {
    mvc.perform(get("/api/policies").header("Authorization", bearer("USER")))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    mvc.perform(get("/api/policies").header("Authorization", bearer("ADMIN")))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.operator").value(true));
  }

  @Test void passportIsAuthenticatedAndDoesNotRequireAdminRoleAtUrlBoundary() throws Exception {
    String route = "/api/compliance/payments/00000000-0000-0000-0000-000000000001/passport";
    mvc.perform(get(route)).andExpect(status().isUnauthorized());
    mvc.perform(get(route).header("Authorization", bearer("USER"))).andExpect(status().isOk());
    mvc.perform(post(route).header("Authorization", bearer("USER")))
        .andExpect(status().isForbidden());
  }

  @Test void policyRouteIsScopedAheadOfExistingCatchAllAndKeepsCorrelation() throws Exception {
    mvc.perform(get("/api/policies").header("X-Correlation-ID", "m5-auth-probe"))
        .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.correlationId").value("m5-auth-probe"));
    mvc.perform(get("/api/auth/probe")).andExpect(status().isOk());
  }

  private String bearer(String role) {
    return "Bearer " + jwt.generate(UUID.fromString("50000000-0000-0000-0000-000000000001"), "synthetic@example.invalid", role);
  }

  @Configuration
  @EnableWebMvc
  @EnableWebSecurity
  @Import({SecurityConfig.class, JwtAuthFilter.class, ProbeController.class})
  static class WebFixture {
    @Bean JwtUtil jwtUtil() { return new JwtUtil("m5-test-only-unsigned-identity-must-fail-1234567890"); }
    @Bean ObjectMapper objectMapper() {
      return new ObjectMapper().findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
  }

  @RestController
  static class ProbeController {
    @GetMapping("/api/policies") ApiResponse<?> policies() { return new ApiResponse<>("probe", Map.of("operator", true)); }
    @GetMapping("/api/compliance/payments/{id}/passport") ApiResponse<?> passport() { return new ApiResponse<>("probe", Map.of("passport", true)); }
    @GetMapping("/api/auth/probe") ApiResponse<?> unrelated() { return new ApiResponse<>("probe", Map.of()); }
  }
}
