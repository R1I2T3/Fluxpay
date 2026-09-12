package com.fluxpay.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.kafka.listener.MessageListenerContainer;

class TimelineKafkaConfigurationTest {
  @Test
  void infrastructureFailuresRemainRetryableBeyondDefaultExhaustion() {
    for (var failure :
        new RuntimeException[] {
          new DataAccessResourceFailureException("database offline"),
          new IllegalStateException("DLT publish failed")
        }) {
      var handler = new TimelineKafkaConfiguration().timelineErrorHandler();
      var record = new ConsumerRecord<>("payout.completed", 0, 1L, "P-001", "payload");
      var consumer = mock(Consumer.class);
      var container = mock(MessageListenerContainer.class);
      when(container.isRunning()).thenReturn(true);
      for (int i = 0; i < 12; i++) {
        assertThat(handler.handleOne(failure, record, consumer, container)).isFalse();
      }
    }
  }

  @Test
  void bootInstallsTheHandlerOnListenerContainers() {
    new org.springframework.boot.test.context.runner.ApplicationContextRunner()
        .withConfiguration(
            org.springframework.boot.autoconfigure.AutoConfigurations.of(
                org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration.class))
        .withUserConfiguration(TimelineKafkaConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              var factory =
                  context.getBean(
                      org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
                          .class);
              assertThat(factory.createContainer("payout.completed").getCommonErrorHandler())
                  .isSameAs(
                      context.getBean(
                          org.springframework.kafka.listener.DefaultErrorHandler.class));
            });
  }
}
