package com.fluxpay.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** One atomic mutation. The facade translates intentional BLOCK only after this transaction commits. */
public class M3ConfirmationWorker {
  private final PaymentRepository payments;
  private final PaymentQuoteRepository quotes;
  private final RecipientRepository recipients;
  private final KycGate kyc;
  private final M3PostingPort posting;
  private final Clock clock;
  private final M3PaymentOperationRepository operations;
  private final OutboxEventRepository outboxEvents;
  private final M3OutboxDeliveryRepository deliveries;
  private final ObjectMapper objectMapper;
  private final M5PaymentDispositionPort dispositions;
  private final M3ReviewDecisionRepository decisions;
  private final TransactionTemplate tx;
  public M3ConfirmationWorker(PaymentRepository payments,PaymentQuoteRepository quotes,
      RecipientRepository recipients,KycGate kyc,M3PostingPort posting,Clock clock,
      M3PaymentOperationRepository operations,OutboxEventRepository outboxEvents,
      M3OutboxDeliveryRepository deliveries,ObjectMapper objectMapper,
      M5PaymentDispositionPort dispositions,M3ReviewDecisionRepository decisions,
      PlatformTransactionManager manager) {
    this.payments=payments; this.quotes=quotes; this.recipients=recipients; this.kyc=kyc;
    this.posting=posting; this.clock=clock; this.operations=operations; this.outboxEvents=outboxEvents;
    this.deliveries=deliveries; this.objectMapper=objectMapper; this.dispositions=dispositions; this.decisions=decisions;
    tx=new TransactionTemplate(Objects.requireNonNull(manager));
    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW); tx.setTimeout(15);
  }
  public PaymentResponse confirm(UUID userId,UUID paymentId,ConfirmPaymentRequest request,
      String clientKey,String normalized,M3PaymentAssessment assessment) {
    return tx.execute(s -> mutate(userId,paymentId,request,clientKey,normalized,assessment));
  }
  private PaymentResponse mutate(UUID userId,UUID paymentId,ConfirmPaymentRequest request,
      String clientKey,String normalized,M3PaymentAssessment assessment) {
    // Insertion is the operation claim. A unique loser rolls back before the facade reads its winner.
    var operation=new M3PaymentOperation(UUID.randomUUID(),userId,"CONFIRM",clientKey,normalized,0,"{}",paymentId,clock.instant());
    operations.saveAndFlush(operation);
    var payment=payments.lockOwned(paymentId,userId).orElseThrow(() -> notFound("PAYMENT_NOT_FOUND","Payment not found."));
    if(payment.flowVersion()!=1) throw conflict("LEGACY_PAYMENT","Legacy payments cannot be modified.");
    if(payment.status()!=PaymentLifecycleStatus.QUOTED) throw conflict("INVALID_PAYMENT_STATE","Only quoted payments can be confirmed.");
    if(assessment==null || assessment.verdict()==null || !M3PaymentFacts.from(payment).equals(assessment.facts()))
      throw conflict("STALE_ASSESSMENT","Payment facts changed after screening.");
    var recipient=recipients.lockOwned(payment.recipientId(),userId).orElseThrow(() -> notFound("RECIPIENT_NOT_FOUND","Recipient not found."));
    if(!recipient.eligible() || recipient.version()!=payment.recipientVersion())
      throw conflict("RECIPIENT_CHANGED","Recipient details changed; create a new draft.");
    var quote=quotes.findByIdAndPaymentId(request.quoteId(),paymentId).orElseThrow(() -> notFound("QUOTE_NOT_FOUND","Quote not found."));
    if(!Objects.equals(payment.currentQuoteGeneration(),quote.generation())) throw conflict("QUOTE_SUPERSEDED","Select a quote from the current generation.");
    requireLiveQuote(quote);
    if(!kyc.isVerified(userId)) throw new M3BusinessException(HttpStatus.FORBIDDEN,"KYC_NOT_VERIFIED","KYC verification is required.");
    dispositions.lockAndValidate(assessment);
    Instant now=clock.instant();
    if(assessment.verdict()==ScreeningVerdict.BLOCK) {
      payment.reject(now); dispositions.record(assessment,"BLOCKED",null);
      return finish(operation,payment,422);
    }
    boolean receipt=payment.hasApproval(assessment.paymentFingerprint(),now);
    M3ReviewDecision accepted=null;
    if(receipt) {
      accepted=decisions==null?null:decisions.findById(payment.approvalDecisionId()).orElse(null);
      receipt=accepted!=null && accepted.authorizes(payment,assessment.paymentFingerprint(),now);
    }
    if(assessment.verdict()==ScreeningVerdict.REVIEW && !receipt) {
      UUID reference=UUID.randomUUID();
      payment.bindReview(assessment.assessmentId(),assessment.caseId(),assessment.paymentFingerprint(),reference.toString(),now);
      dispositions.record(assessment,"REVIEW_REQUIRED",reference);
      int sequence=payment.nextEventSequence(); UUID eventId=UUID.randomUUID();
      persistOutbox(eventId,payment,sequence,"payment.review.requested",reviewPayload(eventId,payment,sequence,reference.toString(),now),now);
      return finish(operation,payment,202);
    }
    requireLiveQuote(quote);
    var accounts=posting.postApprovedPayment(payment.id(),userId,payment.sourceWalletId(),
        payment.sourceCurrency(),payment.sourceAmount(),quote.feeAmount(),quote.expiresAt());
    now=clock.instant();
    if(receipt) {
      if(!accepted.authorizes(payment,assessment.paymentFingerprint(),now)) throw conflict("APPROVAL_EXPIRED","Approval receipt expired during confirmation.");
      accepted.consume(now); payment.consumeApproval(now);
    }
    payment.recordPosting(postingSnapshot(payment,accounts,quote),now);
    payment.selectAndProcess(quote.id(),now);
    dispositions.record(assessment,"PROCEED",null);
    int sequence=payment.nextEventSequence(); UUID eventId=UUID.randomUUID();
    persistOutbox(eventId,payment,sequence,"payment.initiated",initiatedPayload(eventId,payment,quote,sequence,now),now);
    return finish(operation,payment,200);
  }
  private void requireLiveQuote(PaymentQuote quote) {
    if(!clock.instant().isBefore(quote.expiresAt())) throw new M3BusinessException(HttpStatus.GONE,"QUOTE_EXPIRED","The selected quote has expired.");
  }
  private PaymentResponse finish(M3PaymentOperation operation,Payment payment,int status) {
    var result=response(payment);
    try { operation.complete(status,objectMapper.writeValueAsString(result)); }
    catch(Exception e) { throw new M3BusinessException(HttpStatus.INTERNAL_SERVER_ERROR,"IDEMPOTENCY_STORE_FAILED","Idempotency response could not be stored."); }
    operations.saveAndFlush(operation);
    return result;
  }
  private void persistOutbox(
      UUID eventId, Payment payment, int sequence, String topic, String payload, Instant now) {
    OutboxEvent event = new OutboxEvent(eventId, topic, payload, now);
    outboxEvents.save(event);
    M3OutboxDelivery delivery = new M3OutboxDelivery(eventId, payment.id(), sequence, now);
    deliveries.save(delivery);
  }

  private String initiatedPayload(UUID eventId, Payment payment, PaymentQuote quote, int sequence, Instant now) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("eventId", eventId.toString());
      node.put("eventType", "payment.initiated.v1");
      node.put("aggregateSequence", sequence);
      node.put("paymentId", payment.id().toString());
      node.put("status", PaymentLifecycleStatus.PROCESSING.name());
      node.put("selectedQuoteId", quote.id().toString());
      node.put("senderId", payment.senderId().toString());
      node.put("walletId", payment.sourceWalletId().toString());
      node.put("sourceAmount", payment.sourceAmount().toPlainString());
      node.put("feeAmount", quote.feeAmount().toPlainString());
      node.put("netAmount", payment.sourceAmount().subtract(quote.feeAmount()).toPlainString());
      node.put("sourceCurrency", payment.sourceCurrency());
      node.put("payoutCurrency", payment.payoutCurrency());
      node.put("offeredRate", quote.offeredRate().toPlainString());
      node.put("recipientAmount", quote.recipientAmount().toPlainString());
      node.put("occurredAt", now.toString());
      node.put("schemaVersion", "payment.initiated.v1");
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "OUTBOX_STORE_FAILED",
          "Initiated event could not be stored.");
    }
  }

  private String reviewPayload(UUID eventId, Payment payment, int sequence, String reviewReference, Instant now) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("eventId", eventId.toString());
      node.put("eventType", "payment.review.requested.v1");
      node.put("aggregateSequence", sequence);
      node.put("paymentId", payment.id().toString());
      node.put("status", PaymentLifecycleStatus.UNDER_REVIEW.name());
      node.put("reviewReference", reviewReference);
      node.put("assessmentId", payment.reviewAssessmentId().toString());
      node.put("caseId", payment.reviewCaseId().toString());
      node.put("paymentFingerprint", payment.reviewPaymentFingerprint());
      node.put("senderId", payment.senderId().toString());
      node.put("walletId", payment.sourceWalletId().toString());
      node.put("sourceAmount", payment.sourceAmount().toPlainString());
      node.put("sourceCurrency", payment.sourceCurrency());
      node.put("payoutCurrency", payment.payoutCurrency());
      node.put("occurredAt", now.toString());
      node.put("schemaVersion", "payment.review.requested.v1");
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "OUTBOX_STORE_FAILED",
          "Review event could not be stored.");
    }
  }

  private String postingSnapshot(Payment payment, M3PostingAccounts accounts, PaymentQuote quote) {
    try {
      ObjectNode node = objectMapper.createObjectNode();
      node.put("customerWalletId", accounts.customerWalletId().toString());
      node.put("clearingWalletId", accounts.clearingWalletId().toString());
      node.put("feeWalletId", accounts.feeWalletId().toString());
      node.put("currency", payment.sourceCurrency());
      node.put("gross", payment.sourceAmount().toPlainString());
      node.put("fee", quote.feeAmount().toPlainString());
      node.put("net", payment.sourceAmount().subtract(quote.feeAmount()).toPlainString());
      var keys = objectMapper.createArrayNode();
      keys.add("m3:" + payment.id() + ":customer");
      keys.add("m3:" + payment.id() + ":clearing");
      keys.add("m3:" + payment.id() + ":fee");
      node.set("keys", keys);
      return objectMapper.writeValueAsString(node);
    } catch (Exception e) {
      throw new M3BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "POSTING_SNAPSHOT_FAILED",
          "Posting snapshot could not be recorded.");
    }
  }

  private PaymentResponse response(Payment p) {
    return new PaymentResponse(
        p.id(),
        p.sourceWalletId(),
        p.recipientId(),
        p.sourceAmount().toPlainString(),
        p.sourceCurrency(),
        p.payoutCurrency(),
        p.status(),
        p.selectedQuoteId(),
        p.createdAt(),
        false);
  }

  private M3BusinessException notFound(String c, String m) {
    return new M3BusinessException(HttpStatus.NOT_FOUND, c, m);
  }

  private M3BusinessException conflict(String c, String m) {
    return new M3BusinessException(HttpStatus.CONFLICT, c, m);
  }
}
