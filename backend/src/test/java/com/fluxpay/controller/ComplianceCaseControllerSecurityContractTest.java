package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.MethodSecurityConfig;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.dto.CopilotRequest;
import com.fluxpay.service.ComplianceCaseService;
import com.fluxpay.service.PolicyChunkService;
import com.fluxpay.service.PolicyDeletionService;
import com.fluxpay.service.PolicyDocumentService;
import com.fluxpay.service.PolicyGuidanceService;
import com.fluxpay.service.RouteReliabilityService;
import com.fluxpay.service.TransferRouteService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;

@WebMvcTest({
  ComplianceCaseController.class,
  PolicyController.class,
  TransferRouteAdminController.class
})
@Import({SecurityConfig.class, MethodSecurityConfig.class, JwtAuthFilter.class})
class ComplianceCaseControllerSecurityContractTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RESOURCE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

  @Autowired private MockMvc mockMvc;

  @MockBean private ComplianceCaseService complianceCases;
  @MockBean private PolicyDocumentService policyDocuments;
  @MockBean private PolicyChunkService policyChunks;
  @MockBean private PolicyDeletionService policyDeletion;
  @MockBean private PolicyGuidanceService policyGuidance;
  @MockBean private TransferRouteService routeAdmin;
  @MockBean private RouteReliabilityService reliability;
  @MockBean private RouteAdminAuthorizer routeAdminAuthorizer;
  @MockBean private JwtUtil jwt;

  @BeforeEach
  void setUp() {
    when(jwt.parse("user-token")).thenReturn(new CurrentUser(USER_ID, "user@fluxpay.test", "USER"));
  }

  @Test
  void userCannotCreateComplianceCase() throws Exception {
    mockMvc
        .perform(
            post("/api/compliance/cases")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"paymentId\":\""
                        + RESOURCE_ID
                        + "\",\"risk\":\"HIGH\",\"riskReasons\":[\"screening\"],"
                        + "\"suggestedAction\":\"Review\"}"))
        .andExpect(status().isForbidden());

    verify(complianceCases, never()).create(any());
  }

  @Test
  void userCannotApproveOrRejectComplianceCase() throws Exception {
    mockMvc
        .perform(
            put("/api/compliance/cases/{id}/approve", RESOURCE_ID)
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            put("/api/compliance/cases/{id}/reject", RESOURCE_ID)
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isForbidden());

    verify(complianceCases, never()).approve(any(), any(), any());
    verify(complianceCases, never()).reject(any(), any(), any());
  }

  @Test
  void userCannotDeleteComplianceCase() throws Exception {
    mockMvc
        .perform(
            delete("/api/compliance/cases/{id}", RESOURCE_ID)
                .header("Authorization", "Bearer user-token"))
        .andExpect(status().isForbidden());

    verify(complianceCases, never()).deleteManualCase(any());
  }

  @Test
  void userCannotCreatePolicy() throws Exception {
    mockMvc
        .perform(
            post("/api/policies")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Policy\",\"category\":\"AML\",\"content\":\"Text\"}"))
        .andExpect(status().isForbidden());

    verify(policyDocuments, never()).create(any());
  }

  @Test
  void userCannotAddPolicyChunk() throws Exception {
    mockMvc
        .perform(
            post("/api/policies/{id}/chunks", RESOURCE_ID)
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"content\":\"Chunk\"}"))
        .andExpect(status().isForbidden());

    verify(policyChunks, never()).addChunk(any(), any());
  }

  @Test
  void userCannotDeletePolicy() throws Exception {
    mockMvc
        .perform(
            delete("/api/policies/{id}", RESOURCE_ID).header("Authorization", "Bearer user-token"))
        .andExpect(status().isForbidden());

    verify(policyDeletion, never()).delete(any());
  }

  @Test
  void userCannotUpdateRouteEvenWhenLegacyAuthorizerAllowsIt() throws Exception {
    when(routeAdminAuthorizer.isAdmin(any())).thenReturn(true);
    TransferRoute route =
        TransferRoute.seed(
            RESOURCE_ID,
            "STANDARD_BANK",
            "Standard Bank Rail",
            "Standard Bank",
            "STANDARD",
            "5.00",
            "0.8",
            240,
            "99.50");
    when(routeAdmin.update(any(), any())).thenReturn(route);

    mockMvc
        .perform(
            put("/api/admin/routes/{id}", RESOURCE_ID)
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"providerId\":\""
                        + RESOURCE_ID
                        + "\",\"name\":\"Standard Bank Rail\","
                        + "\"destinationType\":\"EXTERNAL_ACCOUNT\",\"destinationCountry\":\"ZZ\","
                        + "\"payoutCurrency\":\"USD\",\"baseFee\":6.00,\"fxSpreadPercentage\":1.0,"
                        + "\"estimatedMinutes\":120,\"configuredSuccessRate\":99.00,"
                        + "\"active\":true,\"version\":0}"))
        .andExpect(status().isForbidden());

    verify(routeAdmin, never()).update(any(), any());
  }

  @Test
  void onlyAdminsCanMutateComplianceDataOrAskCopilot() throws NoSuchMethodException {
    PreAuthorize approve =
        ComplianceCaseController.class
            .getMethod("approve", UUID.class, ComplianceDecisionRequest.class, CurrentUser.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize reject =
        ComplianceCaseController.class
            .getMethod("reject", UUID.class, ComplianceDecisionRequest.class, CurrentUser.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize deleteCase =
        ComplianceCaseController.class
            .getMethod("delete", UUID.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize askCopilot =
        CopilotController.class
            .getMethod("ask", CopilotRequest.class)
            .getAnnotation(PreAuthorize.class);
    java.lang.reflect.Method streamMethod =
        CopilotController.class.getMethod("stream", CopilotRequest.class);
    PreAuthorize streamCopilot = streamMethod.getAnnotation(PreAuthorize.class);
    PostMapping streamMapping = streamMethod.getAnnotation(PostMapping.class);

    assertThat(approve.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(reject.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(deleteCase.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(askCopilot).isNotNull();
    assertThat(askCopilot.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(streamCopilot).isNotNull();
    assertThat(streamCopilot.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(streamMapping.produces()).contains("text/event-stream");
  }

  @Test
  void allPolicyReadsAndMutationsInheritTheAdminRole() {
    PreAuthorize policyOperations = PolicyController.class.getAnnotation(PreAuthorize.class);
    PreAuthorize indexingOperations = PolicyIndexController.class.getAnnotation(PreAuthorize.class);

    assertThat(policyOperations).isNotNull();
    assertThat(policyOperations.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(indexingOperations).isNotNull();
    assertThat(indexingOperations.value()).isEqualTo("hasRole('ADMIN')");
  }
}
