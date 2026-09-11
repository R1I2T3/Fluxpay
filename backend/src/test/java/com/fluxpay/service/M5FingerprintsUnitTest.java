package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.dto.M5AssessmentRequest;
import org.junit.jupiter.api.Test;
import java.util.UUID;

class M5FingerprintsUnitTest {
  @Test void paymentHashIsCanonicalButDetectsImmutableChanges() {
    var a=M5ComplianceRulesUnitTest.snapshot("10.0","USD",null,true,true,true,0L,"IN");
    var b=M5ComplianceRulesUnitTest.snapshot("10.0000","USD",null,true,false,false,2L,"IN");
    String first=M5Fingerprints.payment(a);
    assertTrue(first.matches("[a-f0-9]{64}"));
    assertEquals(first,M5Fingerprints.payment(b));
    assertNotEquals(first,M5Fingerprints.payment(M5ComplianceRulesUnitTest.snapshot("10","USD","null",true,true,true,0L,"IN")));
    assertNotEquals(first,M5Fingerprints.payment(M5ComplianceRulesUnitTest.snapshot("11","USD",null,true,true,true,0L,"IN")));
  }
  @Test void requestHashIncludesSequenceAndAllIdentityFields() {
    var id=UUID.randomUUID(); var payment=UUID.randomUUID();
    String fingerprint="a".repeat(64);
    String first=M5Fingerprints.request(new M5AssessmentRequest(id,1,payment,fingerprint));
    assertTrue(first.matches("[a-f0-9]{64}"));
    assertNotEquals(first,M5Fingerprints.request(new M5AssessmentRequest(id,2,payment,fingerprint)));
    assertNotEquals(first,M5Fingerprints.request(new M5AssessmentRequest(UUID.randomUUID(),1,payment,fingerprint)));
    assertNotEquals(first,M5Fingerprints.request(new M5AssessmentRequest(id,1,UUID.randomUUID(),fingerprint)));
    assertNotEquals(first,M5Fingerprints.request(new M5AssessmentRequest(id,1,payment,"b".repeat(64))));
  }
}
