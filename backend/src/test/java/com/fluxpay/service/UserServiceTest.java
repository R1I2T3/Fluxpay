package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.beans.User;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.dto.UpdateProfileRequest;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.UserRepository;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {
  @Mock private UserRepository users;
  @Mock private KycCaseRepository kycCases;

  private UserService userService;

  @BeforeEach
  void setUp() {
    userService = new UserService(users, kycCases);
  }

  @Test
  void getProfileReturnsNoneWhenNoKycCaseExists() {
    User user = user();
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(kycCases.findByUserId(user.getId())).thenReturn(Optional.empty());

    var response = userService.getProfile(user.getId());

    assertThat(response.id()).isEqualTo(user.getId());
    assertThat(response.kycStatus()).isEqualTo(KycStatus.NONE);
  }

  @Test
  void getProfileReturnsStoredKycStatus() {
    User user = user();
    KycCase kycCase = kycCase(user.getId(), KycStatus.VERIFIED);
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(kycCases.findByUserId(user.getId())).thenReturn(Optional.of(kycCase));

    var response = userService.getProfile(user.getId());

    assertThat(response.kycStatus()).isEqualTo(KycStatus.VERIFIED);
  }

  @Test
  void updateProfileTrimsNameWithoutChangingProtectedFields() {
    User user = user();
    String email = user.getEmail();
    String role = user.getRole();
    String passwordHash = user.getPasswordHash();
    when(users.findById(user.getId())).thenReturn(Optional.of(user));
    when(kycCases.findByUserId(user.getId())).thenReturn(Optional.empty());

    var response =
        userService.updateProfile(user.getId(), new UpdateProfileRequest("  Updated Name  "));

    assertThat(response.fullName()).isEqualTo("Updated Name");
    assertThat(user.getFullName()).isEqualTo("Updated Name");
    assertThat(user.getEmail()).isEqualTo(email);
    assertThat(user.getRole()).isEqualTo(role);
    assertThat(user.getPasswordHash()).isEqualTo(passwordHash);
    verify(users, never()).save(user);
  }

  @Test
  void getProfileRejectsUnknownUser() {
    UUID unknownUserId = UUID.randomUUID();
    when(users.findById(unknownUserId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> userService.getProfile(unknownUserId))
        .isInstanceOf(NoSuchElementException.class)
        .hasMessage("user not found");
  }

  private User user() {
    Instant now = Instant.now();
    return new User(
        UUID.randomUUID(), "user@fluxpay.test", "bcrypt-hash", "USER", "Original Name", now, now);
  }

  private KycCase kycCase(UUID userId, KycStatus status) {
    Instant now = Instant.now();
    return new KycCase(
        UUID.randomUUID(), userId, status, KycDocumentType.PAN, "ABCDE1234F", now, now);
  }
}
