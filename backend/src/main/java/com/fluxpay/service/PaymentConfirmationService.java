package com.fluxpay.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.*;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;

/** Committed replay and assessment preparation before the atomic mutation worker. */
@Service
public class PaymentConfirmationService {
  private final PaymentRepository payments;
  private final M3PaymentOperationRepository operations;
  private final M3PaymentCompliancePort compliance;
  private M3ConfirmationWorker worker;
  private final ObjectMapper objectMapper;
  private java.util.function.Function<PlatformTransactionManager,M3ConfirmationWorker> legacyWorker;
  public PaymentConfirmationService(PaymentRepository payments,M3PaymentOperationRepository operations,
      M3PaymentCompliancePort compliance,M3ConfirmationWorker worker,ObjectMapper objectMapper) {
    this.payments=payments; this.operations=operations; this.compliance=compliance;
    this.worker=worker; this.objectMapper=objectMapper;
  }
  /** Explicit compatibility for the default runtime, excluded by integrated configuration. */
  @Autowired
  public PaymentConfirmationService(PaymentRepository payments,PaymentQuoteRepository quotes,
      RecipientRepository recipients,KycGate kyc,ComplianceAssessor compliance,M3PostingPort posting,
      Clock clock,M3PaymentOperationRepository operations,OutboxEventRepository outboxEvents,
      M3OutboxDeliveryRepository deliveries,ObjectMapper objectMapper) {
    this(payments,operations,new LegacyM3ComplianceAdapter(compliance),null,objectMapper);
    legacyWorker=manager -> new M3ConfirmationWorker(payments,quotes,recipients,kyc,posting,clock,
        operations,outboxEvents,deliveries,objectMapper,new LegacyM3DispositionAdapter(),null,manager);
  }
  @Autowired
  public void configureLegacyTransactionManager(PlatformTransactionManager manager) {
    if(legacyWorker!=null) { worker=legacyWorker.apply(manager); legacyWorker=null; }
  }
  @Transactional(propagation=Propagation.NOT_SUPPORTED)
  public PaymentResponse confirm(UUID userId,UUID paymentId,ConfirmPaymentRequest request,String clientKey) {
    String normalized=normalizedConfirmRequest(userId,paymentId,request);
    var prior=operations.findByUserIdAndOperationTypeAndClientKey(userId,"CONFIRM",clientKey);
    if(prior.isPresent()) return replayChecked(prior.get(),normalized);
    M3PaymentAssessment assessment;
    try {
      var payment=payments.findByIdAndSenderId(paymentId,userId)
        .orElseThrow(() -> new M3BusinessException(HttpStatus.NOT_FOUND,"PAYMENT_NOT_FOUND","Payment not found."));
      if(payment.flowVersion()!=1 || payment.status()!=PaymentLifecycleStatus.QUOTED)
        throw conflict("INVALID_PAYMENT_STATE","Only current quoted payments can be confirmed.");
      if(worker==null) throw new IllegalStateException("Confirmation transaction manager is required");
      assessment=compliance.assess(M3PaymentFacts.from(payment));
    } catch(RuntimeException preparationFailure) {
      // Another confirmation may commit after our first lookup, changing the payment
      // state or activating M5 review before preparation finishes. Its saved outcome wins.
      return recoverCommittedWinner(userId,clientKey,normalized,preparationFailure);
    }
    PaymentResponse result;
    try { result=worker.confirm(userId,paymentId,request,clientKey,normalized,assessment); }
    catch(DataIntegrityViolationException race) {
      return recoverCommittedWinner(userId,clientKey,normalized,race);
    }
    if(result.status()==PaymentLifecycleStatus.REJECTED) throw new M3BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,"PAYMENT_BLOCKED","This payment was blocked by compliance.");
    return result;
  }
  @Transactional(propagation=Propagation.NOT_SUPPORTED)
  public PaymentResponse confirm(UUID userId,UUID paymentId,ConfirmPaymentRequest request) {
    return confirm(userId,paymentId,request,"legacy-"+UUID.randomUUID());
  }
  private PaymentResponse replayChecked(M3PaymentOperation op,String normalized) {
    if(!normalized.equals(op.normalizedRequest())) throw conflict("IDEMPOTENCY_CONFLICT","Idempotency key was already used with a different request.");
    return replay(op);
  }
  private PaymentResponse recoverCommittedWinner(UUID userId,String clientKey,String normalized,RuntimeException failure) {
    var winner=operations.findByUserIdAndOperationTypeAndClientKey(userId,"CONFIRM",clientKey);
    if(winner.isPresent()) return replayChecked(winner.get(),normalized);
    throw failure;
  }
  private static M3BusinessException conflict(String code,String message) {
    return new M3BusinessException(HttpStatus.CONFLICT,code,message);
  }
  private String normalizedConfirmRequest(
      UUID userId, UUID paymentId, ConfirmPaymentRequest request) {
    try {
      var node = objectMapper.createObjectNode();
      node.put("operationType", "CONFIRM");
      node.put("paymentId", paymentId.toString());
      node.put("quoteId", request.quoteId().toString());
      node.put("userId", userId.toString());
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Confirm request could not be normalized.");
    }
  }

  private PaymentResponse replay(M3PaymentOperation op) {
    if (op.outcomeStatus() == 422) {
      throw new M3BusinessException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "PAYMENT_BLOCKED",
          "This payment was blocked by compliance.");
    }
    try {
      return objectMapper.readValue(op.responseData(), PaymentResponse.class);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "IDEMPOTENCY_REPLAY_FAILED",
          "Stored idempotency response could not be read.");
    }
  }

}
