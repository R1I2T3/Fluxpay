package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.common.enums.PolicyCategory;
import com.fluxpay.dto.PolicyDocumentRequest;
import com.fluxpay.dto.PolicyDocumentResponse;
import com.fluxpay.service.PolicyChunkService;
import com.fluxpay.service.PolicyDeletionService;
import com.fluxpay.service.PolicyDocumentService;
import com.fluxpay.service.PolicyGuidanceService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

class PolicyControllerTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void updatesAPolicyWithTheStandardEnvelope() {
    UUID id = UUID.fromString("1888ce37-7a20-4c3a-bd77-2df8a3d5f742");
    PolicyDocumentRequest request =
        new PolicyDocumentRequest(
            "Updated policy", PolicyCategory.AML, "Updated policy content", false);
    PolicyDocumentResponse response =
        new PolicyDocumentResponse(
            id,
            "Updated policy",
            PolicyCategory.AML,
            "Updated policy content",
            "document-hash",
            Instant.parse("2026-09-18T00:00:00Z"),
            List.of());
    PolicyDocumentService documents = mock(PolicyDocumentService.class);
    when(documents.update(eq(id), any())).thenReturn(response);
    PolicyController controller =
        new PolicyController(
            documents,
            mock(PolicyChunkService.class),
            mock(PolicyDeletionService.class),
            mock(PolicyGuidanceService.class));

    var result = controller.update(id, request);

    assertThat(result.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(result.getBody().data()).isEqualTo(response);
  }
}
