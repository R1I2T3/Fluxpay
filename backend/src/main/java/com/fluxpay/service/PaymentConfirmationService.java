package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.*;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.*;
import com.fluxpay.repository.*;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentConfirmationService {
  private final PaymentRepository payments; private final PaymentQuoteRepository quotes; private final RecipientRepository recipients; private final KycGate kyc; private final ComplianceAssessor compliance; private final M3PostingPort posting; private final Clock clock;
  public PaymentConfirmationService(PaymentRepository payments, PaymentQuoteRepository quotes, RecipientRepository recipients, KycGate kyc, ComplianceAssessor compliance, M3PostingPort posting, Clock clock) { this.payments=payments; this.quotes=quotes; this.recipients=recipients; this.kyc=kyc; this.compliance=compliance; this.posting=posting; this.clock=clock; }
  @Transactional public PaymentResponse confirm(UUID userId, UUID paymentId, ConfirmPaymentRequest request) {
    Payment payment=payments.lockOwned(paymentId,userId).orElseThrow(()->notFound("PAYMENT_NOT_FOUND","Payment not found."));
    if(payment.status()!=PaymentLifecycleStatus.QUOTED) throw conflict("INVALID_PAYMENT_STATE","Only quoted payments can be confirmed.");
    PaymentQuote quote=quotes.findByIdAndPaymentId(request.quoteId(),paymentId).orElseThrow(()->notFound("QUOTE_NOT_FOUND","Quote not found."));
    if(!Objects.equals(payment.currentQuoteGeneration(),quote.generation())) throw conflict("QUOTE_SUPERSEDED","Select a quote from the current quote generation.");
    if(!Instant.now(clock).isBefore(quote.expiresAt())) throw new M3BusinessException(HttpStatus.GONE,"QUOTE_EXPIRED","The selected quote has expired.");
    Recipient recipient=recipients.lockOwned(payment.recipientId(),userId).orElseThrow(()->notFound("RECIPIENT_NOT_FOUND","Recipient not found."));
    if(!recipient.eligible() || recipient.version()!=payment.recipientVersion()) throw conflict("RECIPIENT_CHANGED","Recipient details changed; create a new draft.");
    if(!kyc.isVerified(userId)) throw new M3BusinessException(HttpStatus.FORBIDDEN,"KYC_NOT_VERIFIED","KYC verification is required.");
    ScreeningVerdict verdict=compliance.assess(userId,payment.sourceAmount(),payment.sourceCurrency());
    if(verdict==ScreeningVerdict.BLOCK) throw new M3BusinessException(HttpStatus.UNPROCESSABLE_ENTITY,"PAYMENT_BLOCKED","This payment was blocked by compliance.");
    if(verdict==ScreeningVerdict.REVIEW){ payment.underReview(Instant.now(clock)); return response(payment); }
    posting.postApprovedPayment(payment.id(),userId,payment.sourceWalletId(),payment.sourceCurrency(),payment.sourceAmount(),quote.feeAmount(),quote.expiresAt());
    payment.selectAndProcess(quote.id(),Instant.now(clock)); return response(payment);
  }
  private PaymentResponse response(Payment p){return new PaymentResponse(p.id(),p.sourceWalletId(),p.recipientId(),p.sourceAmount(),p.sourceCurrency(),p.payoutCurrency(),p.status(),p.selectedQuoteId(),null,false);} private M3BusinessException notFound(String c,String m){return new M3BusinessException(HttpStatus.NOT_FOUND,c,m);} private M3BusinessException conflict(String c,String m){return new M3BusinessException(HttpStatus.CONFLICT,c,m);}
}
