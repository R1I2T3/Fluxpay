package com.fluxpay.service;

import com.fluxpay.config.M5ComplianceSettings;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.dto.M5PaymentSnapshot;
import com.fluxpay.dto.M5RuleResult;
import com.fluxpay.dto.M5RiskReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Locale;
import java.util.TreeMap;
import java.util.TreeSet;

public class M5ComplianceRulesEngine {
  private final M5ComplianceSettings settings;
  private final String configHash;
  public M5ComplianceRulesEngine(M5ComplianceSettings settings) {
    this.settings = settings;
    var thresholds = new TreeMap<String, String>();
    settings.highValueThresholds().forEach((key,value) -> thresholds.put(key,value.stripTrailingZeros().toPlainString()));
    this.configHash = hash(thresholds + "|" + settings.dayZone().getId() + "|" + settings.recipientTodayMode()
        + "|" + new TreeSet<>(settings.highRiskCountries()));
  }
  public M5RuleResult evaluate(M5PaymentSnapshot s) {
    if (s == null || s.paymentId() == null || s.senderId() == null || s.walletId() == null
        || s.recipientId() == null || s.sourceAmount() == null || s.sourceCurrency() == null
        || s.payoutCurrency() == null || s.recipientSnapshot() == null || s.recipientVersion() < 0
        || !s.purposeAvailable() || s.kycVerified() == null || s.priorCompletedPayment() == null
        || s.recipientTodayCount() == null || s.recipientTodayCount() < 0 || s.recipientCountry() == null
        || s.recipientCountry().isBlank() || s.observedAt() == null || s.historyCutoff() == null
        || s.recipientDayStart() == null || s.dayZone() == null || s.recipientTodayMode() == null) {
      throw new M5ApiException(503,"PAYMENT_DATA_UNAVAILABLE","A complete authoritative payment observation is required");
    }
    if (s.sourceAmount().signum() <= 0 || s.sourceAmount().scale() > 4
        || !settings.highValueThresholds().containsKey(s.sourceCurrency())) {
      throw new M5ApiException(400,"VALIDATION","Amount must be positive with at most four decimal places and a supported currency");
    }
    if (!s.dayZone().equals(settings.dayZone().getId()) || !s.recipientTodayMode().equals(settings.recipientTodayMode())
        || !s.historyCutoff().equals(s.observedAt()) || !s.recipientDayStart().equals(
            s.observedAt().atZone(settings.dayZone()).toLocalDate().atStartOfDay(settings.dayZone()).toInstant())) {
      throw new M5ApiException(503,"PAYMENT_DATA_UNAVAILABLE","History observation does not match configured cutoff and day policy");
    }
    var reasons = new ArrayList<M5RiskReason>();
    if (!s.kycVerified()) reasons.add(new M5RiskReason("R1","HIGH","KYC_UNVERIFIED","Sender KYC is not verified"));
    if (!s.priorCompletedPayment()) reasons.add(new M5RiskReason("R2","MEDIUM","FIRST_TO_RECIPIENT","No earlier completed payment to this recipient"));
    if (s.recipientTodayCount() >= 1) reasons.add(new M5RiskReason("R3","MEDIUM","RECIPIENT_TODAY","Another qualifying payment occurred during the configured day"));
    if (s.sourceAmount().compareTo(settings.highValueThresholds().get(s.sourceCurrency())) > 0)
      reasons.add(new M5RiskReason("R4","MEDIUM","HIGH_VALUE","Amount exceeds the configured source-currency threshold"));
    if (s.purpose() == null || s.purpose().strip().codePointCount(0,s.purpose().strip().length()) < 10)
      reasons.add(new M5RiskReason("R5","MEDIUM","SHORT_PURPOSE","Purpose is missing or shorter than ten characters"));
    if (settings.highRiskCountries().contains(s.recipientCountry().toUpperCase(Locale.ROOT)))
      reasons.add(new M5RiskReason("R6","HIGH","HIGH_RISK_DEST","Recipient country matches the configured demo risk list"));
    String risk = reasons.stream().anyMatch(r -> r.severity().equals("HIGH")) ? "HIGH" : reasons.size() >= 2 ? "MEDIUM" : "LOW";
    String action = switch (risk) { case "HIGH" -> "Hold payment and route to manual review";
      case "MEDIUM" -> "Request additional purpose info / verify recipient"; default -> "Auto-approve eligible"; };
    return new M5RuleResult(risk,risk.equals("LOW") ? "APPROVE" : "REVIEW",
        risk.equals("LOW") ? "PROCESSING" : "UNDER_REVIEW",action,reasons,"m5-rules-v1",configHash);
  }
  private static String hash(String value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
    catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
  }
}
