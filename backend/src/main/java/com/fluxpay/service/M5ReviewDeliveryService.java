package com.fluxpay.service;
import com.fluxpay.repository.M5ScreeningStore;
import com.fluxpay.beans.M5ReviewDecision;
import com.fluxpay.dto.M5DeliveryAck;
import java.util.Objects;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
public class M5ReviewDeliveryService {
  private final M5ScreeningStore store;
  private final M5ReviewDecisionSink sink;
  private final TransactionTemplate tx;
  private final Clock clock;
  public M5ReviewDeliveryService(M5ScreeningStore store,M5ReviewDecisionSink sink,
      PlatformTransactionManager transactions,Clock clock) {
    this.store=Objects.requireNonNull(store); this.sink=Objects.requireNonNull(sink);
    this.clock=Objects.requireNonNull(clock); tx=new TransactionTemplate(transactions);
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(5);
  }
  public int deliverPending(int limit) {
    if(limit<1 || limit>100) throw new IllegalArgumentException("Delivery batch must be 1..100");
    var pending=tx.execute(ignored -> store.pending(clock.instant(),limit));
    for(var value:pending) {
      M5DeliveryAck ack=null;
      try { ack=sink.deliver(value.command()); } catch(RuntimeException unavailable) {
        // No provider exception text is stored: it may contain credentials or personal data.
      }
      final var result=ack;
      tx.executeWithoutResult(ignored -> {
        var current=store.decision(value.command().decisionId(),true).orElseThrow();
        if(!"PENDING".equals(current.deliveryState())) return;
        boolean valid=result!=null && value.command().decisionId().equals(result.decisionId())
            && ("ACKNOWLEDGED".equals(result.status()) || "CONFLICT".equals(result.status()));
        // A terminal reply identifies the immutable command, even if another worker has
        // advanced its retry counter. Only stale failures must avoid consuming another retry.
        if(!valid && current.retryCount()!=value.retryCount()) return;
        int retries=current.retryCount()+(valid?0:1);
        String state=valid?result.status():"PENDING";
        store.updateDelivery(new M5ReviewDecision(current.command(),state,retries,
            valid?current.nextAttemptAt():clock.instant().plusSeconds(Math.min(3600,30L << Math.min(retries-1,7))),
            valid?("CONFLICT".equals(state)?"STALE_REVIEW":null):"DELIVERY_UNAVAILABLE"));
      });
    }
    return pending.size();
  }
}
