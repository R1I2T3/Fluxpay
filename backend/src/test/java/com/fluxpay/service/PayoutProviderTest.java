package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PaymentQuote;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.beans.TransferProvider;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.LedgerWriter;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.ExternalAccountDestination;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.PayoutApi;
import com.fluxpay.dto.TransferProviderSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.TransferRouteSnapshot;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.PaymentQuoteRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import com.fluxpay.repository.RecipientRepository;
import com.fluxpay.repository.TransferRouteOutcomeRepository;
import com.fluxpay.repository.TransferRouteRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Rail-binding execution checks. Two catalogue providers share one code-shipped bank rail; each
 * reservation freezes distinct provider context into its durable rail command.
 */
class PayoutProviderTest {
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  private static final UUID USER_ID = UUID.randomUUID();
  private static final UUID HDFC_PAYMENT = UUID.randomUUID();
  private static final UUID SBI_PAYMENT = UUID.randomUUID();

  static TransferRail mockBankRail() {
    TransferRail bank = mock(TransferRail.class);
    when(bank.type()).thenReturn(RailType.BANK_NETWORK);
    when(bank.supportedDestinations())
        .thenReturn(java.util.Set.of(DestinationType.EXTERNAL_ACCOUNT));
    when(bank.execute(any()))
        .thenAnswer(
            invocation -> {
              TransferRailCommand command = invocation.getArgument(0);
              return TransferRailResult.completed(
                  "BANK-" + command.attemptId(), command.customerFee());
            });
    return bank;
  }

  @Test
  void twoProvidersUseOneBankRailWithDistinctContext() {
    TransferRail bank = mockBankRail();
    RailRegistry rails = new RailRegistry(List.of(bank));
    service(rails, HDFC_PAYMENT, "HDFC_BANK", "HDFC_INR")
        .perform(USER_ID, "hdfc-key", "SUBMIT", HDFC_PAYMENT, "HDFC_INR", null, "cid");
    service(rails, SBI_PAYMENT, "SBI_BANK", "SBI_INR")
        .perform(USER_ID, "sbi-key", "SUBMIT", SBI_PAYMENT, "SBI_INR", null, "cid");

    ArgumentCaptor<TransferRailCommand> commands =
        ArgumentCaptor.forClass(TransferRailCommand.class);
    verify(bank, times(2)).execute(commands.capture());
    assertThat(commands.getAllValues())
        .extracting(c -> c.provider().code())
        .containsExactly("HDFC_BANK", "SBI_BANK");
    assertThat(commands.getAllValues())
        .extracting(c -> c.route().code())
        .containsExactly("HDFC_INR", "SBI_INR");
    assertThat(commands.getAllValues())
        .extracting(TransferRailCommand::idempotencyKey)
        .allMatch(key -> key.startsWith("payout:"));
  }

  @Test
  void inactiveCatalogueFailsBeforeReservation() {
    TransferRail bank = mockBankRail();
    RailRegistry rails = new RailRegistry(List.of(bank));
    Fixture fixture = new Fixture(HDFC_PAYMENT, "HDFC_BANK", "HDFC_INR");
    fixture.route.update("5", "0", 240, "99.5", false);
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    PayoutExecutionService execution =
        new PayoutExecutionService(
            operations(),
            fixture.reservations(attempts, rails),
            mock(PayoutFinalizationService.class),
            rails);

    assertThatThrownBy(
            () ->
                execution.perform(USER_ID, "key", "SUBMIT", HDFC_PAYMENT, "HDFC_INR", null, "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("PAYOUT_ROUTE_UNAVAILABLE");
            });
    verify(attempts, never()).saveAndFlush(any());
    verify(bank, never()).execute(any());
  }

  @Test
  void missingRailFailsWith503BeforeReservingAnAttempt() {
    Fixture fixture = new Fixture(HDFC_PAYMENT, "HDFC_BANK", "HDFC_INR");
    RailRegistry rails = new RailRegistry(List.of());
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    PayoutExecutionService execution =
        new PayoutExecutionService(
            operations(),
            fixture.reservations(attempts, rails),
            mock(PayoutFinalizationService.class),
            rails);

    assertThatThrownBy(
            () ->
                execution.perform(USER_ID, "key", "SUBMIT", HDFC_PAYMENT, "HDFC_INR", null, "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE");
            });
    verify(attempts, never()).saveAndFlush(any());
  }

  @Test
  void incompatibleDestinationFailsWithoutReservingAnAttempt() {
    TransferRail internalOnly = mock(TransferRail.class);
    when(internalOnly.type()).thenReturn(RailType.BANK_NETWORK);
    when(internalOnly.supportedDestinations())
        .thenReturn(java.util.Set.of(DestinationType.INTERNAL_WALLET));
    Fixture fixture = new Fixture(HDFC_PAYMENT, "HDFC_BANK", "HDFC_INR");
    RailRegistry rails = new RailRegistry(List.of(internalOnly));
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    PayoutExecutionService execution =
        new PayoutExecutionService(
            operations(),
            fixture.reservations(attempts, rails),
            mock(PayoutFinalizationService.class),
            rails);

    assertThatThrownBy(
            () ->
                execution.perform(USER_ID, "key", "SUBMIT", HDFC_PAYMENT, "HDFC_INR", null, "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
    verify(attempts, never()).saveAndFlush(any());
    verify(internalOnly, never()).execute(any());
  }

  @Test
  void completedAndFailedOutcomesAreRecordedByAttemptKey() {
    for (TransferRailResult result :
        List.of(
            TransferRailResult.completed("BANK-ref", new BigDecimal("5.0000")),
            TransferRailResult.failed("DECLINED", "Rejected", new BigDecimal("5.0000")))) {
      Fixture fixture = new Fixture(UUID.randomUUID(), "HDFC_BANK", "HDFC_INR");
      PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
      when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
      when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(fixture.payment.id().toString()))
          .thenReturn(Optional.empty());
      PayoutReservationService.Reserved reserved =
          fixture
              .reservations(attempts, new RailRegistry(List.of(mockBankRail())))
              .reserve(USER_ID, fixture.payment.id(), "SUBMIT", "HDFC_INR", null, "cid");
      ArgumentCaptor<com.fluxpay.beans.PayoutAttempt> saved =
          ArgumentCaptor.forClass(com.fluxpay.beans.PayoutAttempt.class);
      verify(attempts).saveAndFlush(saved.capture());
      when(attempts.findById(reserved.attemptId())).thenReturn(Optional.of(saved.getValue()));
      when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(fixture.payment.id().toString()))
          .thenReturn(Optional.of(saved.getValue()));
      TransferRouteOutcomeRepository outcomes = mock(TransferRouteOutcomeRepository.class);
      when(outcomes.findByExecutionReference(any())).thenReturn(Optional.empty());
      when(outcomes.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
      var payments = mock(PaymentRepository.class);
      when(payments.lockOwned(fixture.payment.id(), USER_ID))
          .thenReturn(Optional.of(fixture.payment));
      var operations = mock(PaymentOperationService.class);
      when(operations.completedResponse(any(), any())).thenReturn(Optional.empty());
      PayoutFinalizationService finalization =
          new PayoutFinalizationService(
              payments,
              attempts,
              operations,
              mock(PayoutOutboxService.class),
              Clock.fixed(NOW, ZoneOffset.UTC),
              new RouteOutcomeRecorder(outcomes, Clock.fixed(NOW, ZoneOffset.UTC)));

      ArgumentCaptor<com.fluxpay.beans.TransferRouteOutcome> recorded =
          ArgumentCaptor.forClass(com.fluxpay.beans.TransferRouteOutcome.class);
      finalization.finish(reserved, UUID.randomUUID(), result, "cid");
      verify(outcomes).saveAndFlush(recorded.capture());
      assertThat(recorded.getValue().executionReference())
          .isEqualTo("payout:" + reserved.attemptId());
      assertThat(recorded.getValue().routeId()).isEqualTo(reserved.route().id());
      assertThat(recorded.getValue().outcome().name()).isEqualTo(result.outcome().name());
    }
  }

  @Test
  void uncertainOutcomeRecordsNothing() {
    TransferRouteOutcomeRepository outcomes = mock(TransferRouteOutcomeRepository.class);
    var finalization =
        new PayoutFinalizationService(
            mock(PaymentRepository.class),
            mock(PayoutAttemptRepository.class),
            mock(PaymentOperationService.class),
            mock(PayoutOutboxService.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new RouteOutcomeRecorder(outcomes, Clock.fixed(NOW, ZoneOffset.UTC)));
    var reserved =
        new PayoutReservationService.Reserved(
            USER_ID,
            HDFC_PAYMENT,
            UUID.randomUUID(),
            1,
            new com.fluxpay.domain.AcceptedQuote(
                UUID.randomUUID(),
                "HDFC_INR",
                new BigDecimal("5.0000"),
                new BigDecimal("95.0000"),
                new BigDecimal("80.000000"),
                new BigDecimal("7600.0000")),
            new TransferProviderSnapshot(UUID.randomUUID(), "HDFC_BANK", RailType.BANK_NETWORK),
            new TransferRouteSnapshot(
                UUID.randomUUID(), "HDFC_INR", DestinationType.EXTERNAL_ACCOUNT, UUID.randomUUID()),
            new ExternalAccountDestination("acct", "Bank", "IN", "INR"),
            null);

    assertThatThrownBy(
            () ->
                finalization.finish(
                    reserved,
                    UUID.randomUUID(),
                    TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO),
                    "cid"))
        .isInstanceOf(IllegalStateException.class);
    verify(outcomes, never()).saveAndFlush(any());
    verify(outcomes, never()).findByExecutionReference(any());
  }

  @Test
  void railCommandRejectsMissingOrMismatchedAttemptIdentity() {
    var attempt = UUID.randomUUID();
    var provider =
        new TransferProviderSnapshot(UUID.randomUUID(), "HDFC_BANK", RailType.BANK_NETWORK);
    var routeId = UUID.randomUUID();
    var route =
        new TransferRouteSnapshot(
            routeId, "HDFC_INR", DestinationType.EXTERNAL_ACCOUNT, provider.id());
    var destination = new ExternalAccountDestination("acct", "Bank", "IN", "INR");
    assertThatThrownBy(
            () ->
                new TransferRailCommand(
                    HDFC_PAYMENT,
                    attempt,
                    USER_ID,
                    BigDecimal.TEN,
                    "USD",
                    BigDecimal.ONE,
                    "INR",
                    provider,
                    route,
                    destination,
                    1,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    "arbitrary-key"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new TransferRailCommand(
                    HDFC_PAYMENT,
                    null,
                    USER_ID,
                    BigDecimal.TEN,
                    "USD",
                    BigDecimal.ONE,
                    "INR",
                    provider,
                    route,
                    destination,
                    1,
                    BigDecimal.ONE,
                    BigDecimal.ONE,
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private PayoutExecutionService service(
      RailRegistry rails, UUID paymentId, String providerCode, String routeCode) {
    Fixture fixture = new Fixture(paymentId, providerCode, routeCode);
    PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    when(attempts.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(paymentId.toString()))
        .thenReturn(Optional.empty());
    PayoutFinalizationService finalization = mock(PayoutFinalizationService.class);
    when(finalization.finish(any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new PayoutApi.OutcomeResponse(
                    1, routeCode, "COMPLETED", "ref", null, List.of(), false, "event"));
    return new PayoutExecutionService(
        operations(), fixture.reservations(attempts, rails), finalization, rails);
  }

  private PaymentOperationService operations() {
    PaymentOperationService operations = mock(PaymentOperationService.class);
    when(operations.reserve(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              Runnable validate = invocation.getArgument(6);
              validate.run();
              return new PaymentOperationService.Reservation<>(UUID.randomUUID(), null, null);
            });
    return operations;
  }

  /** Funded PROCESSING payment reaching the catalogue and rail capability checks. */
  private static final class Fixture {
    final UUID quoteId = UUID.randomUUID();
    final Recipient recipient =
        new Recipient(
            UUID.randomUUID(),
            USER_ID,
            "Recipient",
            "account",
            "Bank",
            "IN",
            "INR",
            RecipientStatus.ACTIVE,
            NOW);
    final Payment payment;
    final TransferProvider provider;
    final TransferRoute route;
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    final TransferRouteRepository routes = mock(TransferRouteRepository.class);
    final RecipientRepository recipients = mock(RecipientRepository.class);
    final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

    Fixture(UUID paymentId, String providerCode, String routeCode) {
      payment =
          new Payment(
              paymentId,
              USER_ID,
              UUID.randomUUID(),
              recipient,
              new BigDecimal("100.0000"),
              "USD",
              "INR",
              PaymentPurpose.FAMILY_SUPPORT,
              RoutePreference.CHEAPEST,
              QuoteEntryPointsTest.snapshot("IN", "INR"),
              NOW);
      payment.quoted(1, NOW);
      payment.selectAndProcess(quoteId, NOW);
      payment.recordPosting(
          "{\"customerWalletId\":\"%s\",\"clearingWalletId\":\"%s\",\"feeWalletId\":\"%s\","
                  .formatted(payment.sourceWalletId(), UUID.randomUUID(), UUID.randomUUID())
              + "\"currency\":\"USD\",\"gross\":\"100.0000\",\"net\":\"95.0000\",\"fee\":\"5.0000\","
              + "\"originalJournalReference\":\"payment:"
              + payment.id()
              + "\"}",
          NOW);
      provider =
          TransferProvider.create(
              UUID.randomUUID(),
              providerCode,
              providerCode,
              RailType.BANK_NETWORK,
              true,
              false,
              NOW);
      route =
          TransferRoute.create(
              UUID.randomUUID(),
              provider,
              routeCode,
              routeCode,
              DestinationType.EXTERNAL_ACCOUNT,
              "IN",
              "INR",
              new BigDecimal("5.0000"),
              new BigDecimal("0.000000"),
              240,
              new BigDecimal("99.00"),
              null,
              null,
              true,
              false,
              NOW);
      when(payments.lockOwned(payment.id(), USER_ID)).thenReturn(Optional.of(payment));
      when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
      when(recipients.findByIdAndUserId(recipient.id(), USER_ID))
          .thenReturn(Optional.of(recipient));
      when(routes.findByRouteCode(routeCode)).thenReturn(Optional.of(route));
      when(routes.findById(route.getId())).thenReturn(Optional.of(route));
      when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(payment.id(), 1))
          .thenReturn(
              List.of(
                  new PaymentQuote(
                      quoteId,
                      payment.id(),
                      1,
                      routeCode,
                      new BigDecimal("80"),
                      BigDecimal.ZERO,
                      new BigDecimal("80"),
                      new BigDecimal("5"),
                      new BigDecimal("7600"),
                      1,
                      true,
                      NOW,
                      NOW.plusSeconds(300))));
    }

    PayoutReservationService reservations(PayoutAttemptRepository attempts, RailRegistry rails) {
      return new PayoutReservationService(
          payments,
          new DbPaymentReader(payments, recipients, new ObjectMapper()),
          attempts,
          routes,
          new SelectedQuoteService(payments, quotes, clock, routes),
          clock,
          mock(LedgerWriter.class),
          mock(PayoutOutboxService.class),
          rails);
    }
  }
}
