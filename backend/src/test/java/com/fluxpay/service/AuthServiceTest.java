package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.beans.User;
import com.fluxpay.common.contracts.WalletProvisioner;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.dto.LoginRequest;
import com.fluxpay.dto.RegisterRequest;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
  @Mock private UserRepository users;
  @Mock private KycCaseRepository kycCases;
  @Mock private JwtUtil jwt;
  @Mock private WalletProvisioner walletProvisioner;

  private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
  private AuthService authService;

  @BeforeEach
  void setUp() {
    authService = new AuthService(users, kycCases, passwordEncoder, jwt, walletProvisioner);
  }

  @Test
  void registerCanonicalizesEmailHashesPasswordAndProvisionsWallet() {
    when(users.existsByCanonicalEmail("new.user@fluxpay.test")).thenReturn(false);
    when(users.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(jwt.generate(any(UUID.class), eq("new.user@fluxpay.test"), eq("USER")))
        .thenReturn("signed-jwt");

    var response =
        authService.register(
            new RegisterRequest("  New.User@FluxPay.Test  ", "Pass123!", "  New User  "));

    ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
    verify(users).saveAndFlush(userCaptor.capture());
    User savedUser = userCaptor.getValue();
    assertThat(savedUser.getEmail()).isEqualTo("new.user@fluxpay.test");
    assertThat(savedUser.getFullName()).isEqualTo("New User");
    assertThat(savedUser.getRole()).isEqualTo("USER");
    assertThat(savedUser.getPasswordHash()).isNotEqualTo("Pass123!");
    assertThat(passwordEncoder.matches("Pass123!", savedUser.getPasswordHash())).isTrue();
    verify(walletProvisioner).provision(savedUser.getId());
    assertThat(response.token()).isEqualTo("signed-jwt");
    assertThat(response.user().kycStatus()).isEqualTo(KycStatus.NONE);
  }

  @Test
  void registerRejectsCanonicalDuplicateWithoutSavingOrProvisioning() {
    when(users.existsByCanonicalEmail("new.user@fluxpay.test")).thenReturn(true);

    assertThatThrownBy(
            () ->
                authService.register(
                    new RegisterRequest(" New.User@FluxPay.Test ", "Pass123!", "New User")))
        .isInstanceOf(M1AuthException.class)
        .extracting(exception -> ((M1AuthException) exception).getCode())
        .isEqualTo(M1AuthException.EMAIL_EXISTS);

    verify(users, never()).saveAndFlush(any());
    verify(walletProvisioner, never()).provision(any());
  }

  @Test
  void registerRejectsPasswordLongerThan72Utf8Bytes() {
    when(users.existsByCanonicalEmail("new.user@fluxpay.test")).thenReturn(false);
    String oversizedPassword = "a".repeat(73);

    assertThatThrownBy(
            () ->
                authService.register(
                    new RegisterRequest("new.user@fluxpay.test", oversizedPassword, "New User")))
        .isInstanceOf(M1AuthException.class)
        .extracting(exception -> ((M1AuthException) exception).getCode())
        .isEqualTo("VALIDATION");

    verify(users, never()).saveAndFlush(any());
    verify(walletProvisioner, never()).provision(any());
  }

  @Test
  void loginCanonicalizesPaddedMixedCaseEmail() {
    User user = user("legacy.user@fluxpay.test", "USER");
    when(users.findByCanonicalEmail("legacy.user@fluxpay.test")).thenReturn(Optional.of(user));
    when(kycCases.findByUserId(user.getId())).thenReturn(Optional.empty());
    when(jwt.generate(user.getId(), user.getEmail(), user.getRole())).thenReturn("signed-jwt");

    var response = authService.login(new LoginRequest("  LEGACY.USER@FLUXPAY.TEST  ", "Pass123!"));

    assertThat(response.token()).isEqualTo("signed-jwt");
    assertThat(response.user().email()).isEqualTo("legacy.user@fluxpay.test");
    assertThat(response.user().kycStatus()).isEqualTo(KycStatus.NONE);
  }

  @Test
  void loginUsesSameInvalidCredentialsErrorForUnknownEmailAndWrongPassword() {
    when(users.findByCanonicalEmail("unknown@fluxpay.test")).thenReturn(Optional.empty());

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("unknown@fluxpay.test", "Pass123!")))
        .isInstanceOf(M1AuthException.class)
        .extracting(exception -> ((M1AuthException) exception).getCode())
        .isEqualTo(M1AuthException.INVALID_CREDENTIALS);

    User user = user("known@fluxpay.test", "USER");
    when(users.findByCanonicalEmail("known@fluxpay.test")).thenReturn(Optional.of(user));

    assertThatThrownBy(
            () -> authService.login(new LoginRequest("known@fluxpay.test", "WrongPass123!")))
        .isInstanceOf(M1AuthException.class)
        .extracting(exception -> ((M1AuthException) exception).getCode())
        .isEqualTo(M1AuthException.INVALID_CREDENTIALS);
  }

  @Test
  void loginReturnsStoredKycStatus() {
    User user = user("verified.user@fluxpay.test", "USER");
    KycCase verifiedCase =
        new KycCase(
            UUID.randomUUID(),
            user.getId(),
            KycStatus.VERIFIED,
            KycDocumentType.PAN,
            "ABCDE1234F",
            Instant.now(),
            Instant.now());
    when(users.findByCanonicalEmail("verified.user@fluxpay.test")).thenReturn(Optional.of(user));
    when(kycCases.findByUserId(user.getId())).thenReturn(Optional.of(verifiedCase));
    when(jwt.generate(user.getId(), user.getEmail(), user.getRole())).thenReturn("signed-jwt");

    var response = authService.login(new LoginRequest("verified.user@fluxpay.test", "Pass123!"));

    assertThat(response.user().kycStatus()).isEqualTo(KycStatus.VERIFIED);
  }

  private User user(String email, String role) {
    Instant now = Instant.now();
    return new User(
        UUID.randomUUID(), email, passwordEncoder.encode("Pass123!"), role, "Test User", now, now);
  }
}
