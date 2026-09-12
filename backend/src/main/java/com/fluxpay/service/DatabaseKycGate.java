package com.fluxpay.service;

import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.repository.KycCaseRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Persistent KYC eligibility gate: only a committed VERIFIED case is eligible. */
@Component
public class DatabaseKycGate implements KycGate {
  private final KycCaseRepository kycCases;

  public DatabaseKycGate(KycCaseRepository kycCases) {
    this.kycCases = kycCases;
  }

  @Override
  public boolean isVerified(UUID userId) {
    return kycCases
        .findByUserId(userId)
        .map(kycCase -> kycCase.getStatus() == KycStatus.VERIFIED)
        .orElse(false);
  }
}
