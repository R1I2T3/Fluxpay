package com.fluxpay.controller;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fluxpay.common.TestAuthHelper;
import com.fluxpay.common.security.*;
import com.fluxpay.service.CopilotService;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CopilotController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, MethodSecurityConfig.class})
class CopilotStreamSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean CopilotService copilot;
  @MockBean JwtUtil jwt;

  @Test
  void authenticatedAdminCanCompleteTheAsyncStream() throws Exception {
    UUID id = UUID.randomUUID();
    String token = TestAuthHelper.mockJwt(id, "ADMIN");
    when(jwt.parse(token)).thenReturn(TestAuthHelper.withUser(id, "admin@example.test", "ADMIN"));
    doAnswer(
        invocation -> {
          Consumer<String> delta = invocation.getArgument(1);
          delta.accept("Policy response");
          return null;
        })
        .when(copilot)
        .stream(any(), any());
    var pending =
        mvc.perform(
                post("/api/copilot/ask/stream")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"question\":\"When is review needed?\"}"))
            .andExpect(request().asyncStarted())
            .andReturn();
    pending.getAsyncResult(3000);
    mvc.perform(asyncDispatch(pending))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Policy response")))
        .andExpect(content().string(containsString("event:done")));
    verify(jwt, atLeast(2)).parse(token);
  }

  @Test
  void customerAndAnonymousRequestsStillCannotStream() throws Exception {
    UUID id = UUID.randomUUID();
    String token = TestAuthHelper.mockJwt(id, "USER");
    when(jwt.parse(token)).thenReturn(TestAuthHelper.withUser(id, "user@example.test", "USER"));
    mvc.perform(
            post("/api/copilot/ask/stream")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Policy?\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/copilot/ask/stream")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"question\":\"Policy?\"}"))
        .andExpect(status().isUnauthorized());
    verifyNoInteractions(copilot);
  }
}
