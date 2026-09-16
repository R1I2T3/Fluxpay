package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.ComplianceDecisionRequest;
import com.fluxpay.m5.api.CopilotRequest;
import com.fluxpay.m5.api.M5CopilotController;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

class ComplianceCaseControllerSecurityContractTest {

  @Test
  void onlyAdminsCanMutateOrDeleteMemberFiveData() throws NoSuchMethodException {
    PreAuthorize approve =
        ComplianceCaseController.class
            .getMethod(
                "approve", UUID.class, ComplianceDecisionRequest.class, CurrentUser.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize reject =
        ComplianceCaseController.class
            .getMethod(
                "reject", UUID.class, ComplianceDecisionRequest.class, CurrentUser.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize deleteCase =
        ComplianceCaseController.class
            .getMethod("delete", UUID.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize deletePolicy =
        PolicyController.class
            .getMethod("delete", UUID.class)
            .getAnnotation(PreAuthorize.class);
    PreAuthorize askCopilot =
        M5CopilotController.class
            .getMethod("ask", CopilotRequest.class)
            .getAnnotation(PreAuthorize.class);

    assertThat(approve.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(reject.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(deleteCase.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(deletePolicy.value()).isEqualTo("hasRole('ADMIN')");
    assertThat(askCopilot).isNotNull();
    assertThat(askCopilot.value()).isEqualTo("hasRole('ADMIN')");
  }
}
