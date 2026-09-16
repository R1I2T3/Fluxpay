package com.fluxpay.m5.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fluxpay.m5.application.M5PolicyIndexingService;
import com.fluxpay.m5.application.PolicyIndexResult;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;

class M5PolicyIndexControllerTest {

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  @Test
  void returnsTheCurrentApiEnvelopeForAnIndexedPolicy() {
    UUID documentId = UUID.fromString("1888ce37-7a20-4c3a-bd77-2df8a3d5f742");
    M5PolicyIndexingService service = mock(M5PolicyIndexingService.class);
    when(service.index(documentId)).thenReturn(new PolicyIndexResult(documentId, 3));
    MDC.put("correlationId", "cid-m5-index");

    var response = new M5PolicyIndexController(service).index(documentId);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().correlationId()).isEqualTo("cid-m5-index");
    assertThat(response.getBody().data().chunkCount()).isEqualTo(3);
  }
}
