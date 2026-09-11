package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fluxpay.config.M5ApiException;
import com.fluxpay.config.M5ComplianceSettings;
import com.fluxpay.dto.M5PaymentSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class M5ComplianceRulesUnitTest {
  static final Instant NOW = Instant.parse("2026-09-10T18:30:00Z");
  static final UUID PAYMENT = UUID.fromString("a5000000-0000-0000-0000-000000000001");
  static M5ComplianceSettings settings() {
    return new M5ComplianceSettings(Map.of("USD",new BigDecimal("1000"),"EUR",new BigDecimal("920"),"INR",new BigDecimal("83500")),
        ZoneId.of("Asia/Kolkata"),"ALL_ATTEMPTS",Set.of("KP","IR","SY","MM","RU","BY"));
  }
  static M5PaymentSnapshot snapshot(String amount, String currency, String purpose, boolean available,
      Boolean kyc, Boolean prior, Long count, String country) {
    return new M5PaymentSnapshot(PAYMENT, UUID.fromString("a5000000-0000-0000-0000-000000000002"),
        UUID.fromString("a5000000-0000-0000-0000-000000000003"), UUID.fromString("a5000000-0000-0000-0000-000000000004"),
        new BigDecimal(amount), currency, "INR", purpose, available,"Synthetic recipient",1,country,
        kyc,prior,count,NOW,NOW,NOW,"Asia/Kolkata","ALL_ATTEMPTS");
  }
  final M5ComplianceRulesEngine engine = new M5ComplianceRulesEngine(settings());

  @ParameterizedTest @CsvSource({"USD,1000,false","USD,1000.0001,true","EUR,920,false","EUR,921,true","INR,83500,false","INR,83500.0001,true"})
  void highValueUsesStrictSourceCurrencyThreshold(String currency,String amount,boolean highValue) {
    var result=assertDoesNotThrow(() -> engine.evaluate(snapshot(amount,currency,"Family support",true,true,true,0L,"IN")));
    assertEquals(highValue ? List.of("HIGH_VALUE") : List.of(),result.reasons().stream().map(r -> r.code()).toList());
    assertEquals("LOW",result.risk());
    assertEquals("APPROVE",result.screeningVerdict());
  }
  @Test void evaluatesAllRulesInLiteralOrderWithHighPrecedence() {
    var result=assertDoesNotThrow(() -> engine.evaluate(snapshot("1001","USD",null,true,false,false,1L,"ru")));
    assertEquals(List.of("R1","R2","R3","R4","R5","R6"),result.reasons().stream().map(r -> r.rule()).toList());
    assertEquals(List.of("KYC_UNVERIFIED","FIRST_TO_RECIPIENT","RECIPIENT_TODAY","HIGH_VALUE","SHORT_PURPOSE","HIGH_RISK_DEST"),result.reasons().stream().map(r -> r.code()).toList());
    assertEquals("HIGH",result.risk());
    assertEquals("UNDER_REVIEW",result.status());
    assertEquals("REVIEW",result.screeningVerdict());
  }
  @Test void twoMediumReasonsRequireReview() {
    var result=assertDoesNotThrow(() -> engine.evaluate(snapshot("10","USD","123456789",true,true,false,0L,"IN")));
    assertEquals("MEDIUM",result.risk());
    assertEquals(List.of("FIRST_TO_RECIPIENT","SHORT_PURPOSE"),result.reasons().stream().map(r -> r.code()).toList());
  }
  @Test void trimmedTenCharacterPurposePassesAndKnownNullIsOneVisibleMedium() {
    assertEquals(List.of(),assertDoesNotThrow(() -> engine.evaluate(snapshot("10","USD"," 1234567890 ",true,true,true,0L,"IN"))).reasons());
    var result=engine.evaluate(snapshot("10","USD",null,true,true,true,0L,"IN"));
    assertEquals("SHORT_PURPOSE",result.reasons().get(0).code());
    assertEquals("LOW",result.risk());
  }
  @ParameterizedTest @CsvSource({"0","-1","1.00001"})
  void invalidAmountsCannotPublish(String amount) {
    var error=assertThrows(M5ApiException.class,() -> engine.evaluate(snapshot(amount,"USD","Family support",true,true,true,0L,"IN")));
    assertEquals(400,error.status());
  }
  @Test void unavailableInputNeverDefaultsToPassing() {
    for (var snapshot:List.of(snapshot("10","USD",null,false,true,true,0L,"IN"),snapshot("10","USD","Family support",true,null,true,0L,"IN"),snapshot("10","USD","Family support",true,true,null,0L,"IN"),snapshot("10","USD","Family support",true,true,true,null,"IN"),snapshot("10","USD","Family support",true,true,true,0L,null))) {
      assertEquals("PAYMENT_DATA_UNAVAILABLE",assertThrows(M5ApiException.class,() -> engine.evaluate(snapshot)).code());
    }
  }
  @Test void unsupportedCurrencyIsValidation() {
    assertEquals("VALIDATION",assertThrows(M5ApiException.class,() -> engine.evaluate(snapshot("10","GBP","Family support",true,true,true,0L,"IN"))).code());
  }
}
