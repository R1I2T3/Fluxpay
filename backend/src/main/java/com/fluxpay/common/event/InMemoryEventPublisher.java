package com.fluxpay.common.event;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
@Component @ConditionalOnMissingBean(EventPublisher.class)
public class InMemoryEventPublisher implements EventPublisher {
  private static final Logger log = LoggerFactory.getLogger(InMemoryEventPublisher.class);
  @Override public void publish(String topic, Object payload, String correlationId) {
    log.info("event topic={} correlationId={} payload={}", topic, correlationId, payload);
  }
}
