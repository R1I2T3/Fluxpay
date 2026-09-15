package com.fluxpay.service;
import com.fluxpay.dto.*;
public final class M3M5ReviewDecisionSink implements M5ReviewDecisionSink {
  private final M3ReviewDecisionPort receiver;
  public M3M5ReviewDecisionSink(M3ReviewDecisionPort receiver) { this.receiver=receiver; }
  public M5DeliveryAck deliver(M5ReviewCommand c) {
    var ack=receiver.accept(new M3ReviewCommand(c.decisionId(),c.caseId(),c.assessmentId(),c.paymentId(),c.reviewReference(),c.paymentFingerprint(),c.decision(),c.reviewerId(),c.decidedAt(),c.reason()));
    return new M5DeliveryAck(ack.decisionId(),ack.status());
  }
}
