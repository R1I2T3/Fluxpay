package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.RailRegistry;
import com.fluxpay.web.advice.PayoutApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransferRailAdminController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  PayoutApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class TransferRailAdminControllerTest {

  private static final UUID ADMIN_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:admin".getBytes(StandardCharsets.UTF_8));
  private static final UUID CUSTOMER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:customer".getBytes(StandardCharsets.UTF_8));

  /** Metadata-only rail stub: execution is never exercised by the admin API. */
  private record StubRail(RailType type, Set<DestinationType> supportedDestinations)
      implements TransferRail {
    @Override
    public TransferRailResult execute(TransferRailCommand command) {
      throw new UnsupportedOperationException("metadata stub");
    }
  }

  @Autowired private MockMvc mvc;

  @MockBean private RailRegistry rails;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private JwtUtil jwt;

  private StubRail bankRail;

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
    bankRail = new StubRail(RailType.BANK_NETWORK, Set.of(DestinationType.EXTERNAL_ACCOUNT));
  }

  @Test
  void adminListsRailTypes() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(rails.all())
        .thenReturn(
            List.of(
                new StubRail(RailType.REAL_TIME_NETWORK, Set.of(DestinationType.EXTERNAL_ACCOUNT)),
                new StubRail(RailType.INTERNAL_LEDGER, Set.of(DestinationType.INTERNAL_WALLET)),
                new StubRail(RailType.PARTNER_NETWORK, Set.of(DestinationType.EXTERNAL_ACCOUNT)),
                bankRail));

    mvc.perform(
            get("/api/admin/rail-types")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .header("X-Correlation-ID", "cid-rails-1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-rails-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-rails-1"))
        .andExpect(jsonPath("$.data.railTypes[0].railType").value("BANK_NETWORK"))
        .andExpect(jsonPath("$.data.railTypes[0].displayLabel").value("Bank Network"))
        .andExpect(
            jsonPath("$.data.railTypes[0].supportedDestinations[0]").value("EXTERNAL_ACCOUNT"))
        .andExpect(jsonPath("$.data.railTypes[1].displayLabel").value("Internal Ledger"))
        .andExpect(jsonPath("$.data.railTypes[2].displayLabel").value("Partner Network"))
        .andExpect(jsonPath("$.data.railTypes[3].displayLabel").value("Real-Time Network"));
  }

  @Test
  void adminGetsRailType() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(rails.require(RailType.BANK_NETWORK)).thenReturn(bankRail);

    mvc.perform(
            get("/api/admin/rail-types/BANK_NETWORK")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.railType").value("BANK_NETWORK"))
        .andExpect(jsonPath("$.data.displayLabel").value("Bank Network"))
        .andExpect(jsonPath("$.data.supportedDestinations[0]").value("EXTERNAL_ACCOUNT"));
  }

  @Test
  void uninstalledRailIsServiceUnavailable() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(rails.require(RailType.INTERNAL_LEDGER))
        .thenThrow(
            new BusinessException(
                HttpStatus.SERVICE_UNAVAILABLE,
                "TRANSFER_RAIL_UNAVAILABLE",
                "No transfer rail is installed for INTERNAL_LEDGER."));

    mvc.perform(
            get("/api/admin/rail-types/INTERNAL_LEDGER")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .header("X-Correlation-ID", "cid-rails-2"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("TRANSFER_RAIL_UNAVAILABLE"))
        .andExpect(jsonPath("$.correlationId").value("cid-rails-2"));
  }

  @Test
  void customerListIsForbiddenWithoutRegistryInvocation() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(false);

    mvc.perform(
            get("/api/admin/rail-types")
                .header("Authorization", MockSecurity.bearer(CUSTOMER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-rails-3"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-rails-3"));
    verify(rails, never()).all();
  }

  @Test
  void missingTokenIsRejectedByFrozenChain() throws Exception {
    mvc.perform(get("/api/admin/rail-types").header("X-Correlation-ID", "cid-rails-4"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    verify(rails, never()).all();
  }
}
