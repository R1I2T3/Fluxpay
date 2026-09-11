package com.fluxpay.service;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.User;
import com.fluxpay.common.contracts.WalletProvisioner;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.dto.AuthResponse;
import com.fluxpay.dto.LoginRequest;
import com.fluxpay.dto.RegisterRequest;
import com.fluxpay.dto.UserResponse;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.UserRepository;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
  private static final String USER_ROLE = "USER";
  private static final int BCRYPT_MAX_PASSWORD_BYTES = 72;

  private final UserRepository users;
  private final KycCaseRepository kycCases;
  private final BCryptPasswordEncoder passwordEncoder;
  private final JwtUtil jwt;
  private final WalletProvisioner walletProvisioner;

  public AuthService(
      UserRepository users,
      KycCaseRepository kycCases,
      BCryptPasswordEncoder passwordEncoder,
      JwtUtil jwt,
      WalletProvisioner walletProvisioner) {
    this.users = users;
    this.kycCases = kycCases;
    this.passwordEncoder = passwordEncoder;
    this.jwt = jwt;
    this.walletProvisioner = walletProvisioner;
  }

  @Transactional
  public AuthResponse register(RegisterRequest request) {
    String email = canonicalizeEmail(request.email());
    if (users.existsByCanonicalEmail(email)) {
      throw new M1AuthException(M1AuthException.EMAIL_EXISTS, "email is already registered");
    }
    validatePasswordLength(request.password());

    Instant now = Instant.now();
    User user =
        new User(
            UUID.randomUUID(),
            email,
            passwordEncoder.encode(request.password()),
            USER_ROLE,
            request.fullName().trim(),
            now,
            now);
    user = users.saveAndFlush(user);
    walletProvisioner.provision(user.getId());
    return authenticationResponse(user, KycStatus.NONE);
  }

  @Transactional(readOnly = true)
  public AuthResponse login(LoginRequest request) {
    String email = canonicalizeEmail(request.email());
    User user =
        users
            .findByCanonicalEmail(email)
            .filter(candidate -> passwordMatches(request.password(), candidate.getPasswordHash()))
            .orElseThrow(
                () ->
                    new M1AuthException(
                        M1AuthException.INVALID_CREDENTIALS, "invalid email or password"));
    KycStatus kycStatus = kycCases.findByUserId(user.getId()).map(KycCase::getStatus).orElse(KycStatus.NONE);
    return authenticationResponse(user, kycStatus);
  }

  private AuthResponse authenticationResponse(User user, KycStatus kycStatus) {
    String token = jwt.generate(user.getId(), user.getEmail(), user.getRole());
    return new AuthResponse(
        token,
        AuthResponse.TOKEN_TYPE,
        AuthResponse.EXPIRES_IN_SECONDS,
        new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(), kycStatus));
  }

  private String canonicalizeEmail(String email) {
    return email.trim().toLowerCase(Locale.ROOT);
  }

  private void validatePasswordLength(String password) {
    if (password.getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_PASSWORD_BYTES) {
      throw new M1AuthException("VALIDATION", "password must not exceed 72 UTF-8 bytes");
    }
  }

  private boolean passwordMatches(String password, String passwordHash) {
    return password.getBytes(StandardCharsets.UTF_8).length <= BCRYPT_MAX_PASSWORD_BYTES
        && passwordEncoder.matches(password, passwordHash);
  }
}
