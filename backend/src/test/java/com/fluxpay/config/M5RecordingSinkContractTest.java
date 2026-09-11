package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;

import com.fluxpay.dto.M5ReviewCommand;
import com.fluxpay.service.M5ReviewDecisionSink;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class M5RecordingSinkContractTest {
  private M5ReviewDecisionSink sink() {
    try { return (M5ReviewDecisionSink) Class.forName("com.fluxpay.config.M5RecordingReviewDecisionSink").getConstructor().newInstance(); }
    catch (ReflectiveOperationException missing) { return fail("The recording review sink has not been implemented"); }
  }
  private void mode(M5ReviewDecisionSink sink, String mode, UUID decision) throws Exception {
    sink.getClass().getMethod("configure", String.class, UUID.class).invoke(sink, mode, decision);
  }
  private int effects(M5ReviewDecisionSink sink) throws Exception {
    return (int) sink.getClass().getMethod("effectCount").invoke(sink);
  }
  private M5ReviewCommand command(String decision, UUID id) {
    return new M5ReviewCommand(id, UUID.fromString("50000000-0000-0000-0000-000000000201"),
        UUID.fromString("50000000-0000-0000-0000-000000000301"),
        UUID.fromString("50000000-0000-0000-0000-000000000101"),
        UUID.fromString("50000000-0000-0000-0000-000000000401"), "a".repeat(64), decision,
        UUID.fromString("50000000-0000-0000-0000-000000000001"), Instant.parse("2026-09-10T06:30:00Z"), "Synthetic review");
  }

  @Test void lostAcknowledgmentRetriesSameDecisionWithExactlyOneEffect() throws Exception {
    var sink = sink(); var command = command("APPROVE", UUID.randomUUID());
    mode(sink, "LOSE_ACK_ONCE", command.decisionId());
    assertThrows(RuntimeException.class, () -> sink.deliver(command));
    assertEquals(1, effects(sink));
    assertEquals("ACKNOWLEDGED", sink.deliver(command).status());
    assertEquals(command.decisionId(), sink.deliver(command).decisionId());
    assertEquals(1, effects(sink));
    assertEquals("CONFLICT", sink.deliver(command("REJECT", command.decisionId())).status());
    assertEquals(1, effects(sink));
  }

  @Test void staleReferenceProducesConflictWithoutEffect() throws Exception {
    var sink = sink(); var command = command("REJECT", UUID.randomUUID());
    mode(sink, "STALE_REFERENCE", command.decisionId());
    assertEquals("CONFLICT", sink.deliver(command).status());
    assertEquals(0, effects(sink));
  }
}
