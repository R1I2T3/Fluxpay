package com.fluxpay.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.dto.PolicyIndexResult;
import com.fluxpay.service.PolicyIndexingService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

class PolicyIndexControllerTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void returnsTheCurrentApiEnvelopeForAnIndexedPolicy() {
    UUID documentId = UUID.fromString("1888ce37-7a20-4c3a-bd77-2df8a3d5f742");
    PolicyIndexingService service = mock(PolicyIndexingService.class);
    when(service.index(documentId)).thenReturn(new PolicyIndexResult(documentId, 3));
    MDC.put("correlationId", "cid-policy-index");

    var response = new PolicyIndexController(service).index(documentId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().correlationId()).isEqualTo("cid-policy-index");
    assertThat(response.getBody().data().chunkCount()).isEqualTo(3);
  }
}
