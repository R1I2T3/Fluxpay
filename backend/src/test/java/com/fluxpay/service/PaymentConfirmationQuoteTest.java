package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.*;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.*;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PaymentConfirmationQuoteTest extends DbPaymentEligibilityGateFixture {
  final UUID user = UUID.randomUUID();
  final Recipient recipient = recipient(user);
  final Payment payment =
      new Payment(
          UUID.randomUUID(),
          user,
          UUID.randomUUID(),
          recipient,
          new BigDecimal("100.0000"),
          "USD",
          "KES",
          PaymentPurpose.FAMILY_SUPPORT,
          RoutePreference.CHEAPEST,
          "{}",
          NOW);
  final PayoutRoute route =
      PayoutRoute.seed(
          UUID.randomUUID(), "STANDARD_BANK", "Bank", "Bank", "STANDARD", "20", "5", 5, "90");
  final PaymentRepository payments = mock(PaymentRepository.class);
  final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
  final PayoutRouteRepository routes = mock(PayoutRouteRepository.class);
  final PostingPort posting = mock(PostingPort.class);
  final PaymentQuote quote =
      new PaymentQuote(
          UUID.randomUUID(),
          payment.id(),
          1,
          "STANDARD_BANK",
          new BigDecimal("80"),
          BigDecimal.ZERO,
          new BigDecimal("80"),
          new BigDecimal("5.0000"),
          new BigDecimal("7600.0000"),
          240,
          true,
          NOW,
          NOW.plusSeconds(900));

  PaymentConfirmationService service(Instant time) {
    payment.quoted(1, NOW);
    when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
    when(quotes.findByIdAndPaymentId(quote.id(), payment.id())).thenReturn(Optional.of(quote));
    when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(route));
    var recipients = mock(RecipientRepository.class);
    when(recipients.lockOwned(recipient.id(), user)).thenReturn(Optional.of(recipient));
    var kyc = mock(KycGate.class);
    when(kyc.isVerified(user)).thenReturn(true);
    var compliance = mock(ComplianceAssessor.class);
    when(compliance.assess(user, payment.sourceAmount(), "USD"))
        .thenReturn(ScreeningVerdict.APPROVE);
    when(posting.postApprovedPayment(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new PostingAccounts(payment.sourceWalletId(), UUID.randomUUID(), UUID.randomUUID()));
    return new PaymentConfirmationService(
        payments,
        quotes,
        recipients,
        kyc,
        compliance,
        posting,
        Clock.fixed(time, ZoneOffset.UTC),
        mock(PaymentOperationRepository.class),
        mock(OutboxEventRepository.class),
        mock(OutboxDeliveryRepository.class),
        new ObjectMapper().findAndRegisterModules(),
        routes);
  }

  @Test
  void disabledRouteCannotBeConfirmed() {
    route.update("20", "5", 5, "90", false);
    var service = service(NOW);
    assertThatThrownBy(
            () -> service.confirm(user, payment.id(), new ConfirmPaymentRequest(quote.id()), "key"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("ROUTE_UNAVAILABLE"));
    verifyNoInteractions(posting);
  }

  @Test
  void confirmationKeepsAcceptedFeeAndRecipientDespiteRouteEdits() {
    service(NOW).confirm(user, payment.id(), new ConfirmPaymentRequest(quote.id()), "key");
    assertThat(payment.selectedQuoteId()).isEqualTo(quote.id());
    assertThat(payment.postingSnapshot()).contains("\"fee\":\"5.0000\"", "\"net\":\"95.0000\"");
    assertThat(quote.recipientAmount()).isEqualByComparingTo("7600.0000");
    verify(posting)
        .postApprovedPayment(
            payment.id(),
            user,
            payment.sourceWalletId(),
            "USD",
            new BigDecimal("100.0000"),
            new BigDecimal("5.0000"),
            NOW.plusSeconds(900));
  }

  @Test
  void expiredQuoteCannotBeConfirmedAtBoundary() {
    var service = service(NOW.plusSeconds(900));
    assertThatThrownBy(
            () -> service.confirm(user, payment.id(), new ConfirmPaymentRequest(quote.id()), "key"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("QUOTE_EXPIRED"));
    verifyNoInteractions(posting);
  }

  @Test
  void supersededQuoteCannotBeConfirmed() {
    var service = service(NOW);
    payment.quoted(2, NOW);
    assertThatThrownBy(
            () -> service.confirm(user, payment.id(), new ConfirmPaymentRequest(quote.id()), "key"))
        .isInstanceOfSatisfying(
            BusinessException.class, e -> assertThat(e.code()).isEqualTo("QUOTE_SUPERSEDED"));
    verifyNoInteractions(posting);
  }
}
