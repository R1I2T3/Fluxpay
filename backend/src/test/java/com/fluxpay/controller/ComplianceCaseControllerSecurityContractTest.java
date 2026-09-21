package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.dto.CopilotRequest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

class ComplianceCaseControllerSecurityContractTest {

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
