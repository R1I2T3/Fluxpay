package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fluxpay.service.M5Fingerprints;
import com.fluxpay.service.M5PaymentReader;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M5FixtureReaderUnitTest {
  @Test void changingFixtureRiskInputsKeepsPaymentIdentityAndFingerprintImmutable() throws Exception {
    M5PaymentReader reader;
    try { reader = (M5PaymentReader) Class.forName("com.fluxpay.config.M5MockPaymentReader").getConstructor().newInstance(); }
    catch (ReflectiveOperationException missing) { reader = fail("The explicit synthetic payment reader is not implemented"); }
    UUID id = UUID.fromString("50000000-0000-0000-0000-000000000102");
    var before = reader.readForAssessment(id);
    assertEquals(false, before.priorCompletedPayment());
    assertEquals(1L, before.recipientTodayCount());
    reader.getClass().getMethod("scenario", UUID.class, String.class).invoke(reader, id, "LOW");
    var after = reader.readForAssessment(id);
    assertEquals(true, after.priorCompletedPayment());
    assertEquals(0L, after.recipientTodayCount());
    assertEquals(M5Fingerprints.payment(before), M5Fingerprints.payment(after));
    assertEquals(UUID.fromString("50000000-0000-0000-0000-000000000002"), reader.ownerOf(id));
    reader.getClass().getMethod("scenario", UUID.class, String.class).invoke(reader, id, "UNAVAILABLE");
    assertNull(reader.readForAssessment(id).kycVerified());
    assertEquals(reader.ownerOf(id), after.senderId());
  }
}
