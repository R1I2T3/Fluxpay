package com.fluxpay.service;
import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.beans.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M3ReviewReceiptTest {
  @Test void approvalRequiresNewQuotePreservesCounterAndExpiresExactlyAt900Seconds() throws Exception {
    var p=payment(); var now=Instant.parse("2026-09-13T10:00:00Z");
    p.quoted(p.nextQuoteGeneration(),now);
    UUID assessment=UUID.randomUUID(), caseId=UUID.randomUUID();
    p.bindReview(assessment,caseId,"a".repeat(64),"ref",now);
    p.acceptReview(UUID.randomUUID(),now);
    assertEquals(PaymentLifecycleStatus.DRAFT,p.status()); assertNull(p.currentQuoteGeneration());
    assertEquals(2,p.nextQuoteGeneration());
    assertTrue(p.hasApproval("a".repeat(64),now.plusSeconds(899)));
    assertFalse(p.hasApproval("a".repeat(64),now.plusSeconds(900)));
    assertFalse(p.hasApproval("b".repeat(64),now.plusSeconds(1)));
    p.consumeApproval(now.plusSeconds(2));
    assertFalse(p.hasApproval("a".repeat(64),now.plusSeconds(3)));
  }
  static Payment payment() {
    var now=Instant.parse("2026-09-13T10:00:00Z"); var user=UUID.randomUUID();
    var r=new Recipient(UUID.randomUUID(),user,"Synthetic","synthetic","Bank","IN","INR",RecipientStatus.ACTIVE,now);
    return new Payment(UUID.randomUUID(),user,UUID.randomUUID(),r,new BigDecimal("10"),"USD","INR",PaymentPurpose.FAMILY_SUPPORT,QuoteRoute.BALANCED,"{\"country\":\"IN\",\"currency\":\"INR\"}",now);
  }
}
