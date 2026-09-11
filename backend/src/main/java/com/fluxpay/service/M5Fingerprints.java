package com.fluxpay.service;

import com.fluxpay.dto.M5PaymentSnapshot;
import com.fluxpay.dto.M5AssessmentRequest;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;

public final class M5Fingerprints {
  private M5Fingerprints() {}
  private static final ObjectMapper JSON = new ObjectMapper();
  public static String payment(M5PaymentSnapshot s) {
    var fields = new LinkedHashMap<String,Object>();
    fields.put("paymentId",s.paymentId()); fields.put("senderId",s.senderId());
    fields.put("walletId",s.walletId()); fields.put("recipientId",s.recipientId());
    fields.put("recipientSnapshot",s.recipientSnapshot()); fields.put("recipientVersion",s.recipientVersion());
    fields.put("recipientCountry",s.recipientCountry());
    fields.put("sourceAmount",s.sourceAmount() == null ? null : s.sourceAmount().stripTrailingZeros().toPlainString());
    fields.put("sourceCurrency",s.sourceCurrency()); fields.put("payoutCurrency",s.payoutCurrency()); fields.put("purpose",s.purpose());
    return hash(fields);
  }
  public static String request(M5AssessmentRequest r) {
    var fields = new LinkedHashMap<String,Object>();
    fields.put("assessmentId",r.assessmentId()); fields.put("assessmentSequence",r.assessmentSequence());
    fields.put("paymentId",r.paymentId()); fields.put("expectedPaymentFingerprint",r.expectedPaymentFingerprint());
    return hash(fields);
  }
  private static String hash(Object value) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
        .digest(JSON.writeValueAsString(value).getBytes(StandardCharsets.UTF_8))); }
    catch (JsonProcessingException | java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot canonicalize M5 identity",e); }
  }
}
