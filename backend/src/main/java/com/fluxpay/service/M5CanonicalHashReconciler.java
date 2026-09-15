package com.fluxpay.service;

import com.fluxpay.repository.M5PolicyRepository;
import java.time.Duration;
import org.springframework.stereotype.Service;

/** Explicit administrative operation, never a startup listener or automatic repair. */
@Service
public class M5CanonicalHashReconciler {
  private final M5PolicyRepository repository;
  public M5CanonicalHashReconciler(M5PolicyRepository repository) {this.repository=repository;}
  public int reconcile() {
    return repository.reconcileCanonicalHashes(new M5WorkDeadline(Duration.ofSeconds(30),"POLICY_RECONCILIATION_TIMEOUT"));
  }
}
