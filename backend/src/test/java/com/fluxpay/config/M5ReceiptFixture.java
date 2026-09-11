package com.fluxpay.config;

import com.fluxpay.dto.M5DeliveryAck;
import com.fluxpay.dto.M5ReviewCommand;
import com.fluxpay.service.M5ReviewDecisionSink;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Executable M3 handoff model only; no production payment adapter or money movement. */
public final class M5ReceiptFixture implements M5ReviewDecisionSink {
    public enum Confirmation { PROCEED, REVIEW_REQUIRED, BLOCKED, RECHECK_FAILED }
    private record ActiveReview(UUID reference, String fingerprint) { }
    private record Receipt(String fingerprint, Instant expiresAt) { }
    private final Clock clock;
    private final Map<UUID, ActiveReview> active = new HashMap<>();
    private final Map<UUID, Receipt> receipts = new HashMap<>();
    private final Map<UUID, M5ReviewCommand> acknowledged = new HashMap<>();

    public M5ReceiptFixture(Clock clock) { this.clock = clock; }

    public synchronized void activate(UUID payment, UUID reference, String fingerprint) {
        active.put(payment, new ActiveReview(reference, fingerprint));
        receipts.remove(payment);
    }

    @Override public synchronized M5DeliveryAck deliver(M5ReviewCommand command) {
        var previous = acknowledged.get(command.decisionId());
        if (previous != null) {
            return new M5DeliveryAck(command.decisionId(), previous.equals(command) ? "ACKNOWLEDGED" : "CONFLICT");
        }
        var expected = active.get(command.paymentId());
        if (expected == null || !expected.reference().equals(command.reviewReference())
                || !expected.fingerprint().equals(command.paymentFingerprint())
                || !Set.of("APPROVE", "REJECT").contains(command.decision())) {
            return new M5DeliveryAck(command.decisionId(), "CONFLICT");
        }
        // This synchronized mutation stands in for M3's one atomic database commit.
        // The real adapter must persist these effects and dedup state durably.
        if (command.decision().equals("APPROVE")) {
            receipts.put(command.paymentId(), new Receipt(command.paymentFingerprint(),
                clock.instant().plus(Duration.ofMinutes(15))));
        } else {
            receipts.remove(command.paymentId());
        }
        active.remove(command.paymentId());
        acknowledged.put(command.decisionId(), command);
        return new M5DeliveryAck(command.decisionId(), "ACKNOWLEDGED");
    }

    public synchronized Confirmation confirm(UUID payment, String fingerprint, String freshVerdict,
            boolean kycValid, boolean recipientValid, boolean balanceSufficient) {
        if (!Set.of("APPROVE", "REVIEW", "BLOCK").contains(freshVerdict)) {
            throw new IllegalArgumentException("A fresh screening verdict is mandatory");
        }
        if (freshVerdict.equals("BLOCK")) return Confirmation.BLOCKED;
        if (!kycValid || !recipientValid || !balanceSufficient) return Confirmation.RECHECK_FAILED;
        var receipt = receipts.get(payment);
        boolean valid = receipt != null && receipt.fingerprint().equals(fingerprint)
            && clock.instant().isBefore(receipt.expiresAt());
        if (freshVerdict.equals("APPROVE") || valid) {
            if (valid) receipts.remove(payment);
            return Confirmation.PROCEED;
        }
        return Confirmation.REVIEW_REQUIRED;
    }

    public synchronized int effects() { return acknowledged.size(); }
}
