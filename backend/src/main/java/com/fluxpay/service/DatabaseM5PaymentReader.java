package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.config.M5ComplianceSettings;
import com.fluxpay.dto.M5PaymentSnapshot;
import com.fluxpay.repository.M5PaymentObservationRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Builds risk inputs from one database observation without changing upstream records. */
public final class DatabaseM5PaymentReader implements M5PaymentReader {
  private final M5PaymentObservationRepository observations;
  private final ObjectMapper json;
  private final M5ComplianceSettings settings;
  private final Clock clock;

  public DatabaseM5PaymentReader(M5PaymentObservationRepository observations, ObjectMapper json,
      M5ComplianceSettings settings, Clock clock) {
    this.observations = observations;
    this.json = json;
    this.settings = settings;
    this.clock = clock;
  }

  @Override
  public M5PaymentSnapshot readForAssessment(UUID paymentId) {
    requireId(paymentId);
    Instant now = clock.instant();
    Instant dayStart = now.atZone(settings.dayZone()).toLocalDate()
        .atStartOfDay(settings.dayZone()).toInstant();
    var p = observations.observe(paymentId, dayStart, now, settings.recipientTodayMode())
        .orElseThrow(DatabaseM5PaymentReader::notFound);
    if (p.senderId() == null || p.walletId() == null || p.recipientId() == null
        || !p.senderId().equals(p.walletOwner()) || !p.senderId().equals(p.recipientOwner())
        || p.flowVersion() == null || p.flowVersion() != 1
        || p.amount() == null || p.sourceCurrency() == null || p.payoutCurrency() == null
        || !p.sourceCurrency().matches("[A-Z]{3}") || !p.payoutCurrency().matches("[A-Z]{3}")
        || !p.sourceCurrency().equals(p.walletCurrency())
        || p.recipientSnapshot() == null || p.recipientVersion() == null || p.recipientVersion() < 0
        || p.currentRecipientVersion() == null || p.currentRecipientVersion() < p.recipientVersion()
        || p.createdAt() == null || p.createdAt().isAfter(now)) {
      throw unavailable();
    }
    JsonNode snapshot;
    try {
      snapshot = json.readTree(p.recipientSnapshot());
    } catch (JsonProcessingException malformed) {
      throw unavailable();
    }
    if (snapshot == null || !snapshot.isObject() || !snapshot.path("country").isTextual()
        || !snapshot.path("currency").isTextual()
        || !p.payoutCurrency().equals(snapshot.path("currency").textValue())) {
      throw unavailable();
    }
    String country = snapshot.path("country").textValue().strip().toUpperCase(Locale.ROOT);
    if (!country.matches("[A-Z]{2}")) throw unavailable();

    // M1's absent case is the known NONE/not-submitted state, not a missing database query.
    // A failed query or an unrecognized stored status must still fail closed.
    boolean verified = false;
    if (p.kycId() != null) {
      if (p.kycStatus() == null || !Set.of("VERIFIED", "PENDING", "REJECTED").contains(p.kycStatus()))
        throw unavailable();
      verified = p.kycStatus().equals("VERIFIED");
    }

    // M3 stores a purpose category. Preserve it exactly; it is not a narrative explanation.
    return new M5PaymentSnapshot(p.paymentId(), p.senderId(), p.walletId(), p.recipientId(),
        p.amount(), p.sourceCurrency(), p.payoutCurrency(), p.purpose(), true,
        p.recipientSnapshot(), p.recipientVersion(), country, verified, p.priorCompletedCount() > 0,
        p.recipientTodayCount(), now, now, dayStart, settings.dayZone().getId(), settings.recipientTodayMode());
  }

  @Override
  public UUID ownerOf(UUID paymentId) {
    requireId(paymentId);
    var owner = observations.owner(paymentId).orElseThrow(DatabaseM5PaymentReader::notFound);
    if (owner.walletOwner() == null || (owner.declaredSender() != null
        && !owner.declaredSender().equals(owner.walletOwner()))) throw unavailable();
    return owner.walletOwner();
  }

  private static void requireId(UUID paymentId) {
    if (paymentId == null) throw new M5ApiException(400, "VALIDATION", "Payment ID is required");
  }

  private static M5ApiException unavailable() {
    return new M5ApiException(503, "PAYMENT_DATA_UNAVAILABLE", "A complete authoritative payment observation is required");
  }

  private static M5ApiException notFound() {
    return new M5ApiException(404, "PAYMENT_NOT_FOUND", "Payment was not found");
  }
}
