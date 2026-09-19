package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.beans.TransferProvider;
import com.fluxpay.common.contracts.RouteAdminAuthorizer;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.common.web.CorrelationIdFilter;
import com.fluxpay.common.web.GlobalExceptionHandler;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.DeletionResult;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.service.TransferProviderService;
import com.fluxpay.web.advice.PayoutApiExceptionHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TransferProviderAdminController.class)
@Import({
  SecurityConfig.class,
  JwtAuthFilter.class,
  CorrelationIdFilter.class,
  PayoutApiExceptionHandler.class,
  GlobalExceptionHandler.class
})
class TransferProviderAdminControllerTest {

  private static final UUID ADMIN_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:admin".getBytes(StandardCharsets.UTF_8));
  private static final UUID CUSTOMER_ID =
      UUID.nameUUIDFromBytes("fluxpay:test:customer".getBytes(StandardCharsets.UTF_8));
  private static final UUID P_HDFC =
      UUID.nameUUIDFromBytes("fluxpay:provider:HDFC_BANK".getBytes(StandardCharsets.UTF_8));

  @Autowired private MockMvc mvc;

  @MockBean private TransferProviderService service;
  @MockBean private RouteAdminAuthorizer authorizer;
  @MockBean private JwtUtil jwt;

  private TransferProvider provider;

  @BeforeEach
  void setUp() {
    MockSecurity.stubJwt(jwt);
    provider =
        TransferProvider.create(
            P_HDFC,
            "HDFC_BANK",
            "HDFC Bank",
            RailType.BANK_NETWORK,
            true,
            false,
            Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void adminCreatesProvider() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.create(any())).thenReturn(provider);
    mvc.perform(
            post("/api/admin/providers")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"providerCode":"HDFC_BANK","providerName":"HDFC Bank",
                         "railType":"BANK_NETWORK","active":true}
                        """))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.providerCode").value("HDFC_BANK"));
  }

  @Test
  void adminListsProviders() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.list()).thenReturn(List.of(provider));

    mvc.perform(
            get("/api/admin/providers")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .header("X-Correlation-ID", "cid-providers-1"))
        .andExpect(status().isOk())
        .andExpect(header().string("X-Correlation-ID", "cid-providers-1"))
        .andExpect(jsonPath("$.correlationId").value("cid-providers-1"))
        .andExpect(jsonPath("$.data.providers[0].providerCode").value("HDFC_BANK"))
        .andExpect(jsonPath("$.data.providers[0].railType").value("BANK_NETWORK"));
  }

  @Test
  void adminGetsProvider() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.get(P_HDFC)).thenReturn(provider);

    mvc.perform(
            get("/api/admin/providers/" + P_HDFC)
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.providerCode").value("HDFC_BANK"))
        .andExpect(jsonPath("$.data.version").value(0));
  }

  @Test
  void adminUpdatesProvider() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.update(any(), any())).thenReturn(provider);

    mvc.perform(
            put("/api/admin/providers/" + P_HDFC)
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"providerName":"HDFC Bank Ltd",
                         "railType":"BANK_NETWORK","active":true,"version":0}
                        """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.providerCode").value("HDFC_BANK"));
  }

  @Test
  void adminDeletesUnusedProvider() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.delete(any(), any()))
        .thenReturn(new DeletionResult(DeletionResult.Disposition.DELETED, P_HDFC));

    mvc.perform(
            delete("/api/admin/providers/" + P_HDFC + "?version=0")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disposition").value("DELETED"))
        .andExpect(jsonPath("$.data.id").value(P_HDFC.toString()));
  }

  @Test
  void usedProviderDeleteArchives() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.delete(any(), any()))
        .thenReturn(new DeletionResult(DeletionResult.Disposition.ARCHIVED, P_HDFC));

    mvc.perform(
            delete("/api/admin/providers/" + P_HDFC + "?version=0")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.disposition").value("ARCHIVED"));
  }

  @Test
  void staleVersionIsConflict() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.update(any(), any()))
        .thenThrow(new BusinessException(HttpStatus.CONFLICT, "STALE_PROVIDER", "stale"));

    mvc.perform(
            put("/api/admin/providers/" + P_HDFC)
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .header("X-Correlation-ID", "cid-providers-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"providerName":"HDFC Bank Ltd",
                         "railType":"BANK_NETWORK","active":true,"version":7}
                        """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("STALE_PROVIDER"))
        .andExpect(jsonPath("$.correlationId").value("cid-providers-2"));
  }

  @Test
  void duplicateCodeIsConflict() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);
    when(service.create(any()))
        .thenThrow(
            new BusinessException(HttpStatus.CONFLICT, "PROVIDER_CODE_CONFLICT", "duplicate"));

    mvc.perform(
            post("/api/admin/providers")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"providerCode":"HDFC_BANK","providerName":"HDFC Bank",
                         "railType":"BANK_NETWORK","active":true}
                        """))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("PROVIDER_CODE_CONFLICT"));
  }

  @Test
  void invalidCodeIsBadRequestWithoutServiceInvocation() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(true);

    mvc.perform(
            post("/api/admin/providers")
                .header("Authorization", MockSecurity.bearer(ADMIN_ID, "ADMIN"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"providerCode":"AB","providerName":"HDFC Bank",
                         "railType":"BANK_NETWORK","active":true}
                        """))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_TRANSFER_ROUTE"));
    verify(service, never()).create(any());
  }

  @Test
  void customerCreateIsForbiddenWithoutServiceInvocation() throws Exception {
    when(authorizer.isAdmin(any())).thenReturn(false);

    mvc.perform(
            post("/api/admin/providers")
                .header("Authorization", MockSecurity.bearer(CUSTOMER_ID, "CUSTOMER"))
                .header("X-Correlation-ID", "cid-providers-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                        {"providerCode":"HDFC_BANK","providerName":"HDFC Bank",
                         "railType":"BANK_NETWORK","active":true}
                        """))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"))
        .andExpect(jsonPath("$.correlationId").value("cid-providers-3"));
    verify(service, never()).create(any());
  }

  @Test
  void missingTokenIsRejectedByFrozenChain() throws Exception {
    mvc.perform(get("/api/admin/providers").header("X-Correlation-ID", "cid-providers-4"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
    verify(service, never()).list();
  }
}
