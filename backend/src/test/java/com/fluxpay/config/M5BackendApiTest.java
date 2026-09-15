package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.*;
import com.fluxpay.service.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class M5BackendApiTest {
  @Test void policyCreateReturns201AndIndexReturnsSavedGeneration() throws Exception {
    var service=mock(M5PolicyService.class);
    var reconcile=mock(M5CanonicalHashReconciler.class);
    UUID id=UUID.randomUUID(); UUID generation=UUID.randomUUID();
    when(service.create(any())).thenReturn(new com.fluxpay.beans.M5PolicyDocument(id,"Policy","RISK","Policy text","hash",java.time.Instant.EPOCH,0,"UNINDEXED",null,null,null,0));
    when(service.index(id)).thenReturn(new M5PolicyDtos.Index(id,generation,"space","chunker",1,1,false,false));
    Class<?> type=assertDoesNotThrow(() -> Class.forName("com.fluxpay.controller.M5PolicyController"));
    Object instance=type.getConstructor(M5PolicyService.class,M5CanonicalHashReconciler.class).newInstance(service,reconcile);
    var mvc=MockMvcBuilders.standaloneSetup(instance).setCustomArgumentResolvers(adminArgument()).build();
    mvc.perform(post("/api/policies").contentType(MediaType.APPLICATION_JSON)
        .content("{\"title\":\"Policy\",\"category\":\"RISK\",\"content\":\"Policy text\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value(id.toString()));
    mvc.perform(post("/api/policies/"+id+"/index"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.generationId").value(generation.toString()));
  }
  private org.springframework.web.method.support.HandlerMethodArgumentResolver adminArgument() {
    return new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
      public boolean supportsParameter(org.springframework.core.MethodParameter p) { return p.getParameterType()==CurrentUser.class; }
      public Object resolveArgument(org.springframework.core.MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer c,org.springframework.web.context.request.NativeWebRequest r,org.springframework.web.bind.support.WebDataBinderFactory b) { return new CurrentUser(UUID.randomUUID(),"test@example.invalid","ADMIN"); }
    };
  }
  @Test void copilotControllerReturnsGroundedEnvelope() throws Exception {
    var service=mock(M5CopilotService.class);
    when(service.ask(any(),any())).thenReturn(new M5CopilotDtos.Answer("No grounded answer found in current policies.",List.of(),true,Map.of()));
    Class<?> controller=assertDoesNotThrow(() -> Class.forName("com.fluxpay.controller.M5CopilotController"));
    Object instance=controller.getConstructor(M5CopilotService.class).newInstance(service);
    var actor=new CurrentUser(UUID.randomUUID(),"test@example.invalid","ADMIN");
    var argument=new org.springframework.web.method.support.HandlerMethodArgumentResolver() {
      public boolean supportsParameter(org.springframework.core.MethodParameter p) { return p.getParameterType()==CurrentUser.class; }
      public Object resolveArgument(org.springframework.core.MethodParameter p,org.springframework.web.method.support.ModelAndViewContainer c,org.springframework.web.context.request.NativeWebRequest r,org.springframework.web.bind.support.WebDataBinderFactory b) { return actor; }
    };
    var mvc=MockMvcBuilders.standaloneSetup(instance).setCustomArgumentResolvers(argument).build();
    mvc.perform(post("/api/copilot/ask").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"hello\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.data.answer").value("No grounded answer found in current policies."));
  }
  @Test void backendRefusesBlanketLocalMocksBeforeStartingInfrastructure() {
    Class<?> app=assertDoesNotThrow(() -> Class.forName("com.fluxpay.config.M5BackendApplication"));
    new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
        .withUserConfiguration(app)
        .withInitializer(c -> c.getEnvironment().setActiveProfiles("m5-backend","m5-risk","local"))
        .run(c -> {
          assertNotNull(c.getStartupFailure());
          org.assertj.core.api.Assertions.assertThat(c.getStartupFailure())
              .hasStackTraceContaining("local profile is not permitted");
        });
  }
  @Test void integrationRejectsOsivBecauseItCanReuseStalePreScreeningEntities() {
    new org.springframework.boot.test.context.runner.WebApplicationContextRunner()
        .withUserConfiguration(M5BackendApplication.class)
        .withPropertyValues("spring.jpa.open-in-view=true")
        .withInitializer(c -> c.getEnvironment().setActiveProfiles("m5-backend","m5-risk","m5-m3-integration"))
        .run(c -> {
          assertNotNull(c.getStartupFailure());
          org.assertj.core.api.Assertions.assertThat(c.getStartupFailure()).hasStackTraceContaining("open-in-view must be false");
        });
  }
}
