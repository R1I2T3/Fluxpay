package com.fluxpay.service;

import com.fluxpay.config.M5ReceiptFixture;
import com.fluxpay.dto.M5ReviewCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static com.fluxpay.config.M5ReceiptFixture.Confirmation.*;

/** These tests specify the local integration contract, not the absent real M3 adapter. */
class M5ReceiptContractTest {
    private final MutableClock clock = new MutableClock();
    private final M5ReceiptFixture receiver = new M5ReceiptFixture(clock);
    private final UUID payment = UUID.fromString("55000000-0000-0000-0000-000000000001");
    private final UUID reference = UUID.fromString("55000000-0000-0000-0000-000000000002");
    private final String fingerprint = "a".repeat(64);

    @Test void approvalReceiptIsOneUseAndExactDecisionReplayDoesNotCreateAnother() {
        var command = approved();
        assertThat(receiver.deliver(command).status()).isEqualTo("ACKNOWLEDGED");
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, true)).isEqualTo(PROCEED);
        receiver.deliver(command);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, true)).isEqualTo(REVIEW_REQUIRED);
        assertThat(receiver.effects()).isEqualTo(1);
    }

    @Test void receiptExpiresAtFifteenMinutesAndReplayDoesNotExtendIt() {
        var command = approved();
        receiver.deliver(command);
        clock.now = clock.now.plusSeconds(14 * 60);
        receiver.deliver(command);
        clock.now = clock.now.plusSeconds(60);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, true)).isEqualTo(REVIEW_REQUIRED);
    }

    @Test void receiptCanOverrideReviewImmediatelyBeforeExpiryButNeverFreshBlock() {
        receiver.deliver(approved());
        clock.now = clock.now.plusSeconds(899);
        assertThat(receiver.confirm(payment, fingerprint, "BLOCK", true, true, true)).isEqualTo(BLOCKED);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, true)).isEqualTo(PROCEED);
    }

    @Test void fingerprintAndAuthoritativeRechecksRemainMandatory() {
        receiver.deliver(approved());
        assertThat(receiver.confirm(payment, "b".repeat(64), "REVIEW", true, true, true)).isEqualTo(REVIEW_REQUIRED);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", false, true, true)).isEqualTo(RECHECK_FAILED);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, false, true)).isEqualTo(RECHECK_FAILED);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, false)).isEqualTo(RECHECK_FAILED);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, true)).isEqualTo(PROCEED);
    }

    @Test void staleReferenceOrChangedCommandCannotAcknowledgeAnEffect() {
        var old = approved();
        receiver.activate(payment, UUID.randomUUID(), fingerprint);
        assertThat(receiver.deliver(old).status()).isEqualTo("CONFLICT");
        assertThat(receiver.effects()).isZero();

        var valid = approved();
        receiver.deliver(valid);
        var changed = new M5ReviewCommand(valid.decisionId(), valid.caseId(), valid.assessmentId(),
            payment, reference, fingerprint, "REJECT", valid.reviewerId(), valid.decidedAt(), "Changed");
        assertThat(receiver.deliver(changed).status()).isEqualTo("CONFLICT");
        assertThat(receiver.effects()).isEqualTo(1);
    }

    @Test void rejectionCannotCreateAnApprovalReceiptAndOrdinaryApproveNeedsNone() {
        var base = approved();
        var rejected = new M5ReviewCommand(base.decisionId(), base.caseId(), base.assessmentId(),
            payment, reference, fingerprint, "REJECT", base.reviewerId(), base.decidedAt(), "Synthetic rejection");
        receiver.deliver(rejected);
        assertThat(receiver.confirm(payment, fingerprint, "REVIEW", true, true, true)).isEqualTo(REVIEW_REQUIRED);
        assertThat(receiver.confirm(payment, fingerprint, "APPROVE", true, true, true)).isEqualTo(PROCEED);
    }

    private M5ReviewCommand approved() {
        receiver.activate(payment, reference, fingerprint);
        return new M5ReviewCommand(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            payment, reference, fingerprint, "APPROVE", UUID.randomUUID(), clock.instant(), "Synthetic approval");
    }

    private static class MutableClock extends Clock {
        Instant now = Instant.parse("2026-09-11T00:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
