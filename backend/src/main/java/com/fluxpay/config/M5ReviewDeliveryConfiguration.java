package com.fluxpay.config;

import com.fluxpay.service.M5ReviewDeliveryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;

@Profile("m5-m3-integration") @Configuration(proxyBeanMethods=false) @EnableScheduling
@ConditionalOnProperty(name="m5.review-delivery.enabled",havingValue="true",matchIfMissing=true)
public class M5ReviewDeliveryConfiguration {
  private static final Logger LOG=LoggerFactory.getLogger(M5ReviewDeliveryConfiguration.class);
  private final M5ReviewDeliveryService delivery;
  public M5ReviewDeliveryConfiguration(M5ReviewDeliveryService delivery) {this.delivery=delivery;}
  @Scheduled(fixedDelayString="${m5.review-delivery.delay-ms:10000}",initialDelayString="${m5.review-delivery.delay-ms:10000}")
  public void deliverBatch() {
    try {delivery.deliverPending(20);}
    catch(RuntimeException unavailable) {LOG.warn("M5 review delivery batch failed; durable pending decisions remain available for retry");}
  }
}
