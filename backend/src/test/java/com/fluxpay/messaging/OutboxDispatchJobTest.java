package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class OutboxDispatchJobTest {
  @Test
  void dispatchInvokesRelayOnceWithConfiguredBatch() {
    var relay = mock(OutboxRelay.class);
    when(relay.relayOnce(50)).thenReturn(2);
    var job = new OutboxDispatchJob(relay, 50);
    job.dispatch();
    verify(relay).relayOnce(50);
  }

  @Test
  void dispatchIsScheduledWithConfigurableDelay() throws Exception {
    var method = OutboxDispatchJob.class.getMethod("dispatch");
    var scheduled = method.getAnnotation(Scheduled.class);
    assertThat(scheduled).isNotNull();
    assertThat(scheduled.fixedDelayString()).contains("fluxpay.outbox");
    assertThat(scheduled.initialDelayString())
        .isEqualTo("${fluxpay.outbox.dispatch-initial-delay-ms:0}");
  }
}
