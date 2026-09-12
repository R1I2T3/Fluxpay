package com.fluxpay.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.common.security.SecurityConfig;
import com.fluxpay.config.M1SecurityConfig;
import com.fluxpay.dto.AuthResponse;
import com.fluxpay.dto.KycAdminRow;
import com.fluxpay.dto.KycFileMeta;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.dto.UserResponse;
import com.fluxpay.service.AuthService;
import com.fluxpay.service.KycService;
import com.fluxpay.service.M1AuthException;
import com.fluxpay.service.M1KycException;
import com.fluxpay.service.UserService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {
      AuthController.class,
      UserController.class,
      KycController.class,
      AdminKycController.class
    })
@Import({SecurityConfig.class, M1SecurityConfig.class})
class M1ControllerMvcTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final UUID APPLICATION_ID =
      UUID.fromString("33333333-3333-3333-3333-333333333333");

  @Autowired private MockMvc mockMvc;
  @MockBean private AuthService authService;
  @MockBean private UserService userService;
  @MockBean private KycService kycService;
  @MockBean private JwtUtil jwt;

  @BeforeEach
  void setUp() {
    when(jwt.parse("user-token")).thenReturn(new CurrentUser(USER_ID, "user@fluxpay.test", "USER"));
    when(jwt.parse("admin-token"))
        .thenReturn(new CurrentUser(ADMIN_ID, "admin@fluxpay.test", "ADMIN"));
  }

  @Test
  void registerReturnsCreatedEnvelope() throws Exception {
    when(authService.register(any())).thenReturn(authResponse(USER_ID, "USER", KycStatus.NONE));

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"user@fluxpay.test\",\"password\":\"Pass123!\",\"fullName\":\"Test User\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
        .andExpect(jsonPath("$.data.user.role").value("USER"));
  }

  @Test
  void loginReturnsAuthEnvelope() throws Exception {
    when(authService.login(any())).thenReturn(authResponse(USER_ID, "USER", KycStatus.NONE));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"user@fluxpay.test\",\"password\":\"Pass123!\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.expiresIn").value(3600));
  }

  @Test
  void paddedMixedCaseLoginReachesServiceForCanonicalization() throws Exception {
    when(authService.login(any())).thenReturn(authResponse(USER_ID, "USER", KycStatus.NONE));

    mockMvc
        .perform(
            post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"  USER@FLUXPAY.TEST  \",\"password\":\"Pass123!\"}"))
        .andExpect(status().isOk());

    ArgumentCaptor<com.fluxpay.dto.LoginRequest> requestCaptor =
        ArgumentCaptor.forClass(com.fluxpay.dto.LoginRequest.class);
    verify(authService).login(requestCaptor.capture());
    org.assertj.core.api.Assertions.assertThat(requestCaptor.getValue().email())
        .isEqualTo("  USER@FLUXPAY.TEST  ");
  }

  @Test
  void duplicateCanonicalEmailReturnsConflict() throws Exception {
    when(authService.register(any()))
        .thenThrow(
            new M1AuthException(M1AuthException.EMAIL_EXISTS, "email is already registered"));

    mockMvc
        .perform(
            post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"email\":\"USER@FLUXPAY.TEST\",\"password\":\"Pass123!\",\"fullName\":\"Test User\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EMAIL_EXISTS"));
  }

  @Test
  void getMyProfileUsesAuthenticatedUser() throws Exception {
    when(userService.getProfile(USER_ID)).thenReturn(userResponse(KycStatus.NONE));

    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer user-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.email").value("user@fluxpay.test"));
  }

  @Test
  void updateMyProfileUsesAuthenticatedUser() throws Exception {
    when(userService.updateProfile(eq(USER_ID), any())).thenReturn(userResponse(KycStatus.NONE));

    mockMvc
        .perform(
            put("/api/users/me")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Updated User\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.fullName").value("Test User"));
  }

  @Test
  void submitKycReturnsCreatedEnvelope() throws Exception {
    when(kycService.submit(eq(USER_ID), any())).thenReturn(kycStatus(KycStatus.PENDING, 0L));

    mockMvc
        .perform(
            post("/api/kyc/applications")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validKycBody()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.status").value("PENDING"));
  }

  @Test
  void getMyKycStatusReturnsServiceResponse() throws Exception {
    when(kycService.getMyStatus(USER_ID)).thenReturn(kycStatus(KycStatus.VERIFIED, 3L));

    mockMvc
        .perform(get("/api/kyc/my-status").header("Authorization", "Bearer user-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("VERIFIED"));
  }

  @Test
  void adminCanListApplications() throws Exception {
    when(kycService.listForAdmin(eq(KycStatus.PENDING), any())).thenReturn(List.of(adminRow()));

    mockMvc
        .perform(
            get("/api/admin/kyc/applications")
                .param("status", "PENDING")
                .header("Authorization", "Bearer admin-token"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].applicationId").value(APPLICATION_ID.toString()));
  }

  @Test
  void adminCanApproveApplication() throws Exception {
    when(kycService.approve(eq(ADMIN_ID), eq(APPLICATION_ID), any()))
        .thenReturn(kycStatus(KycStatus.VERIFIED, 1L));

    mockMvc
        .perform(
            put("/api/admin/kyc/applications/{id}/approve", APPLICATION_ID)
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("VERIFIED"));
  }

  @Test
  void adminCanRejectApplication() throws Exception {
    when(kycService.reject(eq(ADMIN_ID), eq(APPLICATION_ID), any()))
        .thenReturn(kycStatus(KycStatus.REJECTED, 1L));

    mockMvc
        .perform(
            put("/api/admin/kyc/applications/{id}/reject", APPLICATION_ID)
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"reason\":\"Need clearer document\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("REJECTED"));
  }

  @Test
  void missingTokenReturnsAuthRequired() throws Exception {
    mockMvc
        .perform(get("/api/users/me"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  @Test
  void invalidTokenReturnsAuthRequired() throws Exception {
    when(jwt.parse("invalid-token")).thenThrow(new IllegalArgumentException("invalid"));

    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer invalid-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  @Test
  void expiredTokenReturnsAuthRequired() throws Exception {
    when(jwt.parse("expired-token")).thenThrow(new IllegalArgumentException("expired"));

    mockMvc
        .perform(get("/api/users/me").header("Authorization", "Bearer expired-token"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("AUTH_REQUIRED"));
  }

  @Test
  void userIsDeniedFromAdminEndpoint() throws Exception {
    mockMvc
        .perform(get("/api/admin/kyc/applications").header("Authorization", "Bearer user-token"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void staleKycReviewReturnsConflictEnvelope() throws Exception {
    when(kycService.approve(eq(ADMIN_ID), eq(APPLICATION_ID), any()))
        .thenThrow(new M1KycException(M1KycException.KYC_CONFLICT, "KYC application has changed"));

    mockMvc
        .perform(
            put("/api/admin/kyc/applications/{id}/approve", APPLICATION_ID)
                .header("Authorization", "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("KYC_CONFLICT"));
  }

  private AuthResponse authResponse(UUID id, String role, KycStatus kycStatus) {
    return new AuthResponse(
        "signed-jwt",
        "Bearer",
        3600,
        new UserResponse(id, "user@fluxpay.test", "Test User", role, kycStatus));
  }

  private UserResponse userResponse(KycStatus status) {
    return new UserResponse(USER_ID, "user@fluxpay.test", "Test User", "USER", status);
  }

  private KycStatusResponse kycStatus(KycStatus status, long version) {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    return new KycStatusResponse(
        APPLICATION_ID, version, status, null, now, status == KycStatus.PENDING ? null : now);
  }

  private KycAdminRow adminRow() {
    return new KycAdminRow(
        APPLICATION_ID,
        0L,
        "user@fluxpay.test",
        "Test User",
        com.fluxpay.beans.KycDocumentType.PAN,
        "ABCDE1234F",
        KycStatus.PENDING,
        Instant.parse("2026-01-01T00:00:00Z"),
        null,
        null,
        List.of(new KycFileMeta("pan.pdf", "application/pdf", 1024)));
  }

  private String validKycBody() {
    return "{\"docType\":\"PAN\",\"docNumber\":\"ABCDE1234F\",\"documents\":[{\"fileName\":\"pan.pdf\",\"fileType\":\"application/pdf\",\"fileSize\":1024}]}";
  }
}
