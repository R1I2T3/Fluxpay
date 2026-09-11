package com.fluxpay.service;
import com.fluxpay.repository.M5ScreeningStore;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
public class M5ReviewDeliveryService {
  public M5ReviewDeliveryService(M5ScreeningStore store,M5ReviewDecisionSink sink,
      PlatformTransactionManager transactions,Clock clock) {}
  public int deliverPending(int limit) { throw new UnsupportedOperationException(); }
}
