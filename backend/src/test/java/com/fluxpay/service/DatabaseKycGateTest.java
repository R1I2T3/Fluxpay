package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.repository.KycCaseRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class DatabaseKycGateTest {
  @Mock private KycCaseRepository kycCases;

  private DatabaseKycGate gate;

  @BeforeEach
  void setUp() {
    gate = new DatabaseKycGate(kycCases);
  }

  @Test
  void returnsFalseWhenNoKycCaseExists() {
    UUID userId = UUID.randomUUID();
    when(kycCases.findByUserId(userId)).thenReturn(Optional.empty());

    assertThat(gate.isVerified(userId)).isFalse();
  }

  @Test
  void returnsFalseForPendingCase() {
    assertThat(isVerified(KycStatus.PENDING)).isFalse();
  }

  @Test
  void returnsFalseForRejectedCase() {
    assertThat(isVerified(KycStatus.REJECTED)).isFalse();
  }

  @Test
  void returnsTrueOnlyForVerifiedCase() {
    assertThat(isVerified(KycStatus.VERIFIED)).isTrue();
  }

  private boolean isVerified(KycStatus status) {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    KycCase kycCase =
        new KycCase(UUID.randomUUID(), userId, status, KycDocumentType.PAN, "ABCDE1234F", now, now);
    when(kycCases.findByUserId(userId)).thenReturn(Optional.of(kycCase));
    return gate.isVerified(userId);
  }
}
