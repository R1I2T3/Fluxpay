package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.*;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.transaction.annotation.Transactional;

class M3ConfirmationBoundaryTest {
  @Test void preparationFailureWithoutCommittedWinnerKeepsItsOriginalError() {
    var payments=mock(PaymentRepository.class); var operations=mock(M3PaymentOperationRepository.class);
    var p=M3ReviewReceiptTest.payment(); p.quoted(1,p.createdAt());
    when(payments.findByIdAndSenderId(p.id(),p.senderId())).thenReturn(Optional.of(p));
    var failure=new com.fluxpay.config.M5ApiException(409,"ACTIVE_REVIEW","A different operation owns the review");
    var service=new PaymentConfirmationService(payments,operations,facts -> { throw failure; },mock(M3ConfirmationWorker.class),new ObjectMapper());
    assertSame(failure,assertThrows(com.fluxpay.config.M5ApiException.class,
        () -> service.confirm(p.senderId(),p.id(),new ConfirmPaymentRequest(UUID.randomUUID()),"no-winner")));
  }
  @ParameterizedTest
  @CsvSource({"PAYMENT_PROCESSING,false","PAYMENT_REVIEW,false","SCREENING_REVIEW,false",
      "PAYMENT_PROCESSING,true","PAYMENT_REVIEW,true","SCREENING_REVIEW,true"})
  void concurrentWinnerDuringPreparationReplaysOnlyTheIdenticalNormalizedRequest(String scenario,boolean changedRequest) throws Exception {
    var payments=mock(PaymentRepository.class); var operations=mock(M3PaymentOperationRepository.class);
    var worker=mock(M3ConfirmationWorker.class); var json=new ObjectMapper().findAndRegisterModules();
    var p=M3ReviewReceiptTest.payment(); UUID quote=UUID.randomUUID(); Instant now=Instant.parse("2026-09-13T10:00:00Z");
    p.quoted(p.nextQuoteGeneration(),now);
    var status=scenario.equals("PAYMENT_PROCESSING")?PaymentLifecycleStatus.PROCESSING:PaymentLifecycleStatus.UNDER_REVIEW;
    var response=new PaymentResponse(p.id(),p.sourceWalletId(),p.recipientId(),"10","USD","INR",status,status==PaymentLifecycleStatus.PROCESSING?quote:null,now,false);
    String normalized="{\"operationType\":\"CONFIRM\",\"paymentId\":\""+p.id()+"\",\"quoteId\":\""+(changedRequest?UUID.randomUUID():quote)+"\",\"userId\":\""+p.senderId()+"\"}";
    var winner=new M3PaymentOperation(UUID.randomUUID(),p.senderId(),"CONFIRM","same-key",normalized,status==PaymentLifecycleStatus.PROCESSING?200:202,json.writeValueAsString(response),p.id(),now);
    var committed=new AtomicReference<M3PaymentOperation>();
    when(operations.findByUserIdAndOperationTypeAndClientKey(p.senderId(),"CONFIRM","same-key"))
        .thenAnswer(call -> Optional.ofNullable(committed.get()));
    when(payments.findByIdAndSenderId(p.id(),p.senderId())).thenAnswer(call -> {
      if(scenario.startsWith("PAYMENT_")) {
        // Winner commits after the facade's first operation read but before its payment read.
        if(status==PaymentLifecycleStatus.PROCESSING) p.selectAndProcess(quote,now);
        else p.underReview("synthetic-review",now);
        committed.set(winner);
      }
      return Optional.of(p);
    });
    M3PaymentCompliancePort compliance=facts -> {
      // Winner commits after this request has observed QUOTED, before M5 publication.
      committed.set(winner);
      throw new com.fluxpay.config.M5ApiException(409,"ACTIVE_REVIEW","Concurrent confirmation activated review");
    };
    var service=new PaymentConfirmationService(payments,operations,compliance,worker,json);
    if(changedRequest) {
      assertEquals("IDEMPOTENCY_CONFLICT",assertThrows(com.fluxpay.config.M3BusinessException.class,
          () -> service.confirm(p.senderId(),p.id(),new ConfirmPaymentRequest(quote),"same-key")).code());
    } else {
      assertEquals(response,service.confirm(p.senderId(),p.id(),new ConfirmPaymentRequest(quote),"same-key"));
    }
    verifyNoInteractions(worker);
  }
  @Test void committedReplayPrecedesPaymentLockAndExpiredQuoteChecks() throws Exception {
    var payments=mock(PaymentRepository.class); var operations=mock(M3PaymentOperationRepository.class);
    var compliance=mock(ComplianceAssessor.class); var quotes=mock(PaymentQuoteRepository.class);
    var json=new ObjectMapper().findAndRegisterModules();
    UUID user=UUID.randomUUID(), payment=UUID.randomUUID(), quote=UUID.randomUUID();
    String normalized="{\"operationType\":\"CONFIRM\",\"paymentId\":\""+payment+"\",\"quoteId\":\""+quote+"\",\"userId\":\""+user+"\"}";
    var response=new PaymentResponse(payment,UUID.randomUUID(),UUID.randomUUID(),"10","USD","EUR",PaymentLifecycleStatus.UNDER_REVIEW,null,Instant.EPOCH,false);
    when(operations.findByUserIdAndOperationTypeAndClientKey(user,"CONFIRM","key"))
      .thenReturn(Optional.of(new M3PaymentOperation(UUID.randomUUID(),user,"CONFIRM","key",normalized,202,json.writeValueAsString(response),payment,Instant.EPOCH)));
    var service=new PaymentConfirmationService(payments,quotes,mock(RecipientRepository.class),mock(KycGate.class),compliance,mock(M3PostingPort.class),Clock.systemUTC(),operations,mock(OutboxEventRepository.class),mock(M3OutboxDeliveryRepository.class),json);
    assertEquals(response,service.confirm(user,payment,new ConfirmPaymentRequest(quote),"key"));
    verifyNoInteractions(payments,quotes,compliance);
  }
  @Test void businessFailuresMustNotOptOutOfMutationRollback() throws Exception {
    var annotation=PaymentConfirmationService.class.getMethod("confirm",UUID.class,UUID.class,ConfirmPaymentRequest.class,String.class).getAnnotation(Transactional.class);
    assertTrue(annotation==null || annotation.noRollbackFor().length==0,"Financial mutation must roll back business failures");
  }
}
