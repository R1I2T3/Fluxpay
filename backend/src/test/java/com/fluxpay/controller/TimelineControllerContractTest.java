package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.enums.PaymentStatus;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.config.M4ApiExceptionHandler;
import com.fluxpay.dto.TimelineEventResponse;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.RouteAdminAuthorizer;
import com.fluxpay.service.TimelineService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/** HTTP contract for the payment timeline: chronological events behind the owner gate. */
@WebMvcTest(TimelineController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  M4ApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class TimelineControllerContractTest {

  private static final UUID OWNER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:owner".getBytes(StandardCharsets.UTF_8));
  private static final UUID OTHER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:other".getBytes(StandardCharsets.UTF_8));

  @Autowired private MockMvc mvc;

  @MockBean private PaymentReader reader;
  @MockBean private TimelineService timeline;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private JwtUtil jwt;

  private PaymentSnapshot payment;

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
    payment =
        new PaymentSnapshot(
            "P-001",
            OWNER_ID,
            UUID.nameUUIDFromBytes("fluxpay:P-001:sender".getBytes(StandardCharsets.UTF_8)),
            UUID.nameUUIDFromBytes("fluxpay:P-001:clearing".getBytes(StandardCharsets.UTF_8)),
            new BigDecimal("1000.00"),
            "USD",
            "KES",
            PaymentStatus.ROUTED);
  }

  @Test
  void ownerGetsChronologicalTimeline() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(true);
    when(timeline.getTimeline("P-001"))
        .thenReturn(
            List.of(
                new TimelineEventResponse(
                    "e-1",
                    "P-001",
                    "payout.submitted",
                    "payout.submitted",
                    "c-1",
                    Map.of("summary", "Submitted"),
                    Instant.parse("2026-09-04T10:00:01Z")),
                new TimelineEventResponse(
                    "e-2",
                    "P-001",
                    "payout.failed",
                    "payout.failed",
                    "c-2",
                    Map.of("summary", "Failed"),
                    Instant.parse("2026-09-04T10:00:02Z"))));

    mvc.perform(
            get("/api/payments/P-001/timeline")
                .header("Authorization", MockSecurity.bearer(OWNER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-time-1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-time-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-time-1"))
        .andExpect(jsonPath("$.data[0].eventId").value("e-1"))
        .andExpect(jsonPath("$.data[0].payload.summary").value("Submitted"))
        .andExpect(jsonPath("$.data[1].eventId").value("e-2"))
        .andExpect(jsonPath("$.data[1].payload.summary").value("Failed"));
  }

  @Test
  void timelineRejectsNonOwner() throws Exception {
    when(reader.get("P-001")).thenReturn(payment);
    when(authorizer.isOwner(any(), eq(payment))).thenReturn(false);

    mvc.perform(
            get("/api/payments/P-001/timeline")
                .header("Authorization", MockSecurity.bearer(OTHER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-time-2"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-time-2"));
    verify(timeline, never()).getTimeline("P-001");
  }
}
