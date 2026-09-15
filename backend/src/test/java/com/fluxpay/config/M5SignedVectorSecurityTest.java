package com.fluxpay.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.security.*;
import jakarta.servlet.Filter;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class M5SignedVectorSecurityTest {
  @Test void localHeaderCannotAuthenticateAnyM5RouteFamily() throws Exception {
    try (var context = new AnnotationConfigWebApplicationContext()) {
      context.setServletContext(new MockServletContext());
      context.getEnvironment().setActiveProfiles("local","m5-risk");
      context.register(Fixture.class); context.refresh();
      var mvc = MockMvcBuilders.webAppContextSetup(context)
          .addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();
      for (String path : new String[]{"/api/compliance/cases","/api/policies","/api/copilot/ask"}) {
        mvc.perform(get(path).header("X-Local-User-Id",UUID.randomUUID().toString()).header("X-Role","ADMIN"))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
      }
    }
  }
  @Configuration @EnableWebMvc @EnableWebSecurity
  @Import({SecurityConfig.class,JwtAuthFilter.class,M5SecurityConfig.class,M5RiskSecurityConfig.class})
  static class Fixture {
    @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    @Bean JwtUtil tokens() { return new JwtUtil("signed-vector-test-only-key-12345678901234567890"); }
  }
}
