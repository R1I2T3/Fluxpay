package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Payment;
import com.fluxpay.beans.PaymentOperation;
import com.fluxpay.beans.PaymentPurpose;
import com.fluxpay.beans.PayoutAttempt;
import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.AcceptedQuote;
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
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.repository.PayoutAttemptRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Reconciliation replays the serialized reservation snapshot. Catalogue active flags are never
 * reapplied, so archived routes and providers still reconcile exactly once.
 */
class PayoutReconcilerTest {
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");
  private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void archivedSnapshotReconcilesWithoutActiveFlags() throws Exception {
    Fixture fixture = new Fixture();
    TransferRail bank = mockBankRail(TransferRailResult.completed("BANK-ref", BigDecimal.ZERO));
    PayoutReconciler reconciler =
        new PayoutReconciler(
            fixture.operations,
            fixture.attempts,
            fixture.payments,
            mapper,
            new RailRegistry(List.of(bank)),
            fixture.finalization);

    var result = reconciler.reconcile(fixture.payment.id(), "reconcile-cid");

    assertThat(result.status()).isEqualTo("COMPLETED");
    ArgumentCaptor<TransferRailCommand> delivered =
        ArgumentCaptor.forClass(TransferRailCommand.class);
    verify(bank).execute(delivered.capture());
    assertThat(delivered.getValue()).isEqualTo(fixture.reserved.command());
    ArgumentCaptor<PayoutReservationService.Reserved> finalized =
        ArgumentCaptor.forClass(PayoutReservationService.Reserved.class);
    verify(fixture.finalization).finish(finalized.capture(), any(), any(), any());
    assertThat(finalized.getValue()).isEqualTo(fixture.reserved);
  }

  @ParameterizedTest
  @ValueSource(strings = {"missing", "invalid", "wrong-payment"})
  void invalidDurableReservationNeverContactsRail(String kind) throws Exception {
    Fixture fixture = new Fixture();
    String snapshot =
        switch (kind) {
          case "missing" -> null;
          case "invalid" -> "{}";
          default ->
              fixture.snapshot.replace(
                  fixture.payment.id().toString(), UUID.randomUUID().toString());
        };
    var operation = fixture.operation(snapshot);
    when(fixture.operations.findByPaymentIdAndStatus(fixture.payment.id(), "IN_PROGRESS"))
        .thenReturn(snapshot == null ? List.of() : List.of(operation));
    TransferRail bank = mockBankRail(TransferRailResult.completed("BANK-ref", BigDecimal.ZERO));
    PayoutReconciler reconciler =
        new PayoutReconciler(
            fixture.operations,
            fixture.attempts,
            fixture.payments,
            mapper,
            new RailRegistry(List.of(bank)),
            fixture.finalization);

    assertThatThrownBy(() -> reconciler.reconcile(fixture.payment.id(), "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_RESERVATION_UNAVAILABLE"));
    verify(bank, never()).execute(any());
    verify(fixture.finalization, never()).finish(any(), any(), any(), any());
  }

  @Test
  void uncertainReplayKeepsMoneyAndOperationPending() {
    Fixture fixture = new Fixture();
    TransferRail bank =
        mockBankRail(TransferRailResult.uncertain("TIMEOUT", "Unknown", BigDecimal.ZERO));
    PayoutReconciler reconciler =
        new PayoutReconciler(
            fixture.operations,
            fixture.attempts,
            fixture.payments,
            mapper,
            new RailRegistry(List.of(bank)),
            fixture.finalization);

    assertThatThrownBy(() -> reconciler.reconcile(fixture.payment.id(), "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_PENDING_RECONCILIATION"));
    verify(fixture.finalization, never()).finish(any(), any(), any(), any());
  }

  @Test
  void thrownRailFailureRequiresReconciliation() {
    Fixture fixture = new Fixture();
    TransferRail bank = mock(TransferRail.class);
    when(bank.type()).thenReturn(RailType.BANK_NETWORK);
    when(bank.supportedDestinations())
        .thenReturn(java.util.Set.of(DestinationType.EXTERNAL_ACCOUNT));
    when(bank.execute(any())).thenThrow(new IllegalStateException("connection closed"));
    PayoutReconciler reconciler =
        new PayoutReconciler(
            fixture.operations,
            fixture.attempts,
            fixture.payments,
            mapper,
            new RailRegistry(List.of(bank)),
            fixture.finalization);

    assertThatThrownBy(() -> reconciler.reconcile(fixture.payment.id(), "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.code()).isEqualTo("PAYOUT_PENDING_RECONCILIATION"));
    verify(fixture.finalization, never()).finish(any(), any(), any(), any());
  }

  @Test
  void missingRailSurfacesServiceUnavailable() {
    Fixture fixture = new Fixture();
    PayoutReconciler reconciler =
        new PayoutReconciler(
            fixture.operations,
            fixture.attempts,
            fixture.payments,
            mapper,
            new RailRegistry(List.of()),
            fixture.finalization);

    assertThatThrownBy(() -> reconciler.reconcile(fixture.payment.id(), "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> {
              assertThat(error.status().value()).isEqualTo(503);
              assertThat(error.code()).isEqualTo("TRANSFER_RAIL_UNAVAILABLE");
            });
    verify(fixture.finalization, never()).finish(any(), any(), any(), any());
  }

  @Test
  void incompatibleDestinationRejectsSerializedSnapshot() {
    Fixture fixture = new Fixture();
    TransferRail internalOnly = mock(TransferRail.class);
    when(internalOnly.type()).thenReturn(RailType.BANK_NETWORK);
    when(internalOnly.supportedDestinations())
        .thenReturn(java.util.Set.of(DestinationType.INTERNAL_WALLET));
    PayoutReconciler reconciler =
        new PayoutReconciler(
            fixture.operations,
            fixture.attempts,
            fixture.payments,
            mapper,
            new RailRegistry(List.of(internalOnly)),
            fixture.finalization);

    assertThatThrownBy(() -> reconciler.reconcile(fixture.payment.id(), "cid"))
        .isInstanceOfSatisfying(
            BusinessException.class,
            error -> assertThat(error.code()).isEqualTo("INVALID_TRANSFER_ROUTE"));
    verify(internalOnly, never()).execute(any());
    verify(fixture.finalization, never()).finish(any(), any(), any(), any());
  }

  private static TransferRail mockBankRail(TransferRailResult result) {
    TransferRail bank = mock(TransferRail.class);
    when(bank.type()).thenReturn(RailType.BANK_NETWORK);
    when(bank.supportedDestinations())
        .thenReturn(java.util.Set.of(DestinationType.EXTERNAL_ACCOUNT));
    when(bank.execute(any())).thenReturn(result);
    return bank;
  }

  /** Funded PROCESSING payment with one PROCESSING attempt and a serialized rail reservation. */
  private final class Fixture {
    final UUID user = UUID.randomUUID();
    final UUID quoteId = UUID.randomUUID();
    final UUID attemptId = UUID.randomUUID();
    final UUID routeId = UUID.randomUUID();
    final UUID providerId = UUID.randomUUID();
    final Recipient recipient =
        new Recipient(
            UUID.randomUUID(),
            user,
            "Recipient",
            "account",
            "Bank",
            "IN",
            "INR",
            RecipientStatus.ACTIVE,
            NOW);
    final Payment payment;
    final PayoutAttempt attempt;
    final PayoutReservationService.Reserved reserved;
    final String snapshot;
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PayoutAttemptRepository attempts = mock(PayoutAttemptRepository.class);
    final PaymentOperationRepository operations = mock(PaymentOperationRepository.class);
    final PayoutFinalizationService finalization = mock(PayoutFinalizationService.class);

    Fixture() {
      payment =
          new Payment(
              UUID.randomUUID(),
              user,
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
      attempt = PayoutAttempt.initiated(attemptId, payment.id().toString(), 1, routeId, NOW);
      attempt.markProcessing();
      var provider =
          new TransferProviderSnapshot(providerId, "ARCHIVED_BANK", RailType.BANK_NETWORK);
      var route =
          new TransferRouteSnapshot(
              routeId, "ARCHIVED_INR", DestinationType.EXTERNAL_ACCOUNT, providerId);
      var destination = new ExternalAccountDestination("acct", "Bank", "IN", "INR");
      var quote =
          new AcceptedQuote(
              quoteId,
              "ARCHIVED_INR",
              new BigDecimal("5.0000"),
              new BigDecimal("95.0000"),
              new BigDecimal("80.000000"),
              new BigDecimal("7600.0000"));
      reserved =
          new PayoutReservationService.Reserved(
              user,
              payment.id(),
              attemptId,
              1,
              quote,
              provider,
              route,
              destination,
              new TransferRailCommand(
                  payment.id(),
                  attemptId,
                  user,
                  new BigDecimal("100.0000"),
                  "USD",
                  new BigDecimal("7600.0000"),
                  "INR",
                  provider,
                  route,
                  destination,
                  1,
                  new BigDecimal("5.0000"),
                  new BigDecimal("80.000000"),
                  "payout:" + attemptId));
      try {
        snapshot = mapper.writeValueAsString(reserved);
      } catch (Exception e) {
        throw new AssertionError(e);
      }
      when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
      when(attempts.findFirstByPaymentIdOrderByAttemptNumberDesc(payment.id().toString()))
          .thenReturn(Optional.of(attempt));
      when(operations.findByPaymentIdAndStatus(payment.id(), "IN_PROGRESS"))
          .thenReturn(List.of(operation(snapshot)));
      when(finalization.finish(any(), any(), any(), any()))
          .thenAnswer(
              invocation ->
                  new PayoutApi.OutcomeResponse(
                      1, "ARCHIVED_INR", "COMPLETED", "ref", null, List.of(), false, "event"));
    }

    PaymentOperation operation(String reservation) {
      var operation =
          new PaymentOperation(
              UUID.randomUUID(),
              user,
              PaymentOperation.Namespace.PUBLIC,
              "SUBMIT",
              "key",
              "{}",
              null,
              null,
              payment.id(),
              NOW);
      if (reservation != null) {
        operation.capturePayoutReservation(reservation);
      }
      return operation;
    }
  }
}
