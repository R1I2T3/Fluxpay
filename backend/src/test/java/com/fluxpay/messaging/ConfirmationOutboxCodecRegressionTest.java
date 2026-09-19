package com.fluxpay.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.OutboxDelivery;
import com.fluxpay.beans.OutboxEvent;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.PayoutRoute;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.common.contracts.PostingPort;
import com.fluxpay.common.enums.ScreeningVerdict;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.ConfirmPaymentRequest;
import com.fluxpay.dto.PostingAccounts;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.OutboxEventRepository;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutRouteRepository;
import com.fluxpay.repository.RecipientRepository;
import com.fluxpay.service.PaymentConfirmationService;
import com.fluxpay.service.PaymentOperationService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfirmationOutboxCodecRegressionTest {
  static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  @Test
  void confirmationStoredEventDecodesThroughCanonicalCodec() {
    UUID user = UUID.randomUUID();
    UUID paymentId = UUID.randomUUID();
    UUID wallet = UUID.randomUUID();
    UUID quoteId = UUID.randomUUID();
    var recipient =
        new Recipient(
            UUID.randomUUID(), user, "A", "acct", "Bank", "IN", "INR", RecipientStatus.ACTIVE, NOW);
    var payment =
        new Payment(
            paymentId,
            user,
            wallet,
            recipient,
            new BigDecimal("100.0000"),
            "USD",
            "INR",
            PaymentPurpose.FAMILY_SUPPORT,
            RoutePreference.BALANCED,
            "{}",
            NOW);
    payment.quoted(1, NOW);
    var quote =
        new PaymentQuote(
            quoteId,
            paymentId,
            1,
            "BANK",
            new BigDecimal("80"),
            BigDecimal.ZERO,
            new BigDecimal("80"),
            new BigDecimal("5.0000"),
            new BigDecimal("7600.0000"),
            240,
            true,
            NOW,
            NOW.plusSeconds(900));
    var route =
        PayoutRoute.seed(UUID.randomUUID(), "BANK", "Bank", "Bank", "STANDARD", "5", "0", 1, "99");

    var payments = mock(PaymentRepository.class);
    var quotes = mock(PaymentQuoteRepository.class);
    var recipients = mock(RecipientRepository.class);
    var routes = mock(PayoutRouteRepository.class);
    when(payments.lockOwned(paymentId, user)).thenReturn(Optional.of(payment));
    when(quotes.findByIdAndPaymentId(quoteId, paymentId)).thenReturn(Optional.of(quote));
    when(routes.findByCode("BANK")).thenReturn(Optional.of(route));
    when(recipients.lockOwned(recipient.id(), user)).thenReturn(Optional.of(recipient));
    var kyc = mock(KycGate.class);
    when(kyc.isVerified(user)).thenReturn(true);
    var compliance = mock(ComplianceAssessor.class);
    when(compliance.assessDetailed(any(com.fluxpay.common.contracts.ComplianceScreeningContext.class)))
        .thenReturn(
            new com.fluxpay.common.contracts.ComplianceAssessment(
                ScreeningVerdict.APPROVE,
                com.fluxpay.common.enums.ComplianceRisk.LOW,
                List.of(),
                "Proceed with payment processing."));
    var posting = mock(PostingPort.class);
    when(posting.postApprovedPayment(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(new PostingAccounts(wallet, UUID.randomUUID(), UUID.randomUUID()));

    var events = mock(OutboxEventRepository.class);
    var deliveries = mock(OutboxDeliveryRepository.class);
    var capturedEvents = new java.util.ArrayList<OutboxEvent>();
    var capturedDeliveries = new java.util.ArrayList<OutboxDelivery>();
    when(events.save(any()))
        .thenAnswer(
            c -> {
              capturedEvents.add(c.getArgument(0));
              return c.getArgument(0);
            });
    when(deliveries.save(any()))
        .thenAnswer(
            c -> {
              capturedDeliveries.add(c.getArgument(0));
              return c.getArgument(0);
            });

    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    var operations =
        new PaymentOperationService(
            mock(PaymentOperationRepository.class),
            mapper,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(org.springframework.transaction.PlatformTransactionManager.class));
    // Bypass operation coordination by stubbing execute to run the work directly.
    var opsSpy = spy(operations);

    var service =
        new PaymentConfirmationService(
            payments,
            quotes,
            recipients,
            kyc,
            compliance,
            posting,
            Clock.fixed(NOW, ZoneOffset.UTC),
            mock(PaymentOperationService.class),
            events,
            deliveries,
            mapper,
            routes);

    // Use a real operation service path via direct confirmPayment reflection is complex;
    // instead drive the service with a stubbed operation coordinator that runs the lambda.
    var opCoordinator = mock(PaymentOperationService.class);
    // Rebuild service with stubbed coordinator that executes the supplied work.
    service =
        new PaymentConfirmationService(
            payments,
            quotes,
            recipients,
            kyc,
            compliance,
            posting,
            Clock.fixed(NOW, ZoneOffset.UTC),
            opCoordinator,
            events,
            deliveries,
            mapper,
            routes);
    when(opCoordinator.execute(
            any(), any(), any(), any(), any(), eq(com.fluxpay.dto.PaymentResponse.class), any()))
        .thenAnswer(
            c -> {
              @SuppressWarnings("unchecked")
              java.util.function.Supplier<
                      PaymentOperationService.Result<com.fluxpay.dto.PaymentResponse>>
                  work = c.getArgument(6);
              var r = work.get();
              return r;
            });

    // Ensure MDC correlation is present for the confirmation path.
    org.slf4j.MDC.put("correlationId", "corr-confirm-1");
    try {
      service.confirm(user, paymentId, new ConfirmPaymentRequest(quoteId), "key-1");
    } finally {
      org.slf4j.MDC.remove("correlationId");
    }

    assertThat(capturedEvents).hasSize(1);
    assertThat(capturedDeliveries).hasSize(1);
    OutboxEvent stored = capturedEvents.get(0);
    OutboxDelivery delivery = capturedDeliveries.get(0);

    var codec = new EventEnvelopeCodec(mapper);
    PaymentEventEnvelope envelope = codec.read(stored.payload());

    assertThat(envelope.eventType()).isEqualTo("payment.initiated");
    assertThat(stored.topic()).isEqualTo("payment.initiated");
    assertThat(envelope.eventId()).isEqualTo(stored.id().toString());
    assertThat(envelope.eventId()).isEqualTo(delivery.eventId().toString());
    assertThat(envelope.correlationId()).isNotBlank();
    assertThat(envelope.payload().get("schemaVersion")).isEqualTo(1);
    assertThat(envelope.payload().get("aggregateSequence")).isEqualTo(delivery.aggregateSequence());
    assertThat(envelope.paymentId()).isEqualTo(paymentId.toString());
  }
}
