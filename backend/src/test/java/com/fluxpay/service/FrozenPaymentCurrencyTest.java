package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.domain.QuotePricingPolicy;
import com.fluxpay.domain.RailType;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Exercises the real reader through both quote APIs and selected-quote payout submission. */
class FrozenPaymentCurrencyTest {
  private static final Instant NOW = Instant.parse("2026-09-13T10:00:00Z");

  @Test
  void recipientCurrencyEditAfterSelectionCannotChangeSubmittedCurrency() {
    Fixture f = new Fixture();
    var quoted = f.quotesAt(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    f.payment.selectAndProcess(quoted.recommendedQuoteId(), NOW);
    f.editRecipientCurrency();
    List<PayoutCmd> submitted = new ArrayList<>();
    PayoutProvider provider =
        new PayoutProvider() {
          public String code() {
            return "STANDARD_BANK";
          }

          public PayoutResult submit(PayoutCmd command) {
            submitted.add(command);
            return PayoutResult.ok("bank-reference", command.customerFee());
          }
        };
    Clock clock = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
    f.payment.recordPosting(
        """
        {"customerWalletId":"%s","clearingWalletId":"%s","feeWalletId":"%s","currency":"USD","gross":"100.0000","net":"95.0000","fee":"5.0000","originalJournalReference":"payment:%s"}
        """
            .formatted(
                f.payment.sourceWalletId(), UUID.randomUUID(), UUID.randomUUID(), f.payment.id()),
        NOW);
    var reservations =
        new PayoutReservationService(
            f.payments,
            f.reader,
            mock(PayoutAttemptRepository.class),
            f.routes,
            new SelectedQuoteService(f.payments, f.quotes, clock, f.routes),
            clock,
            mock(com.fluxpay.common.contracts.LedgerWriter.class),
            mock(PayoutOutboxService.class));
    var reserved =
        reservations.reserve(
            f.user, f.payment.id(), "SUBMIT", "STANDARD_BANK", null, "frozen", code -> true);
    provider.submit(reserved.command());

    assertThat(submitted).hasSize(1);
    PayoutCmd command = submitted.get(0);
    assertThat(command.sourceCurrency()).isEqualTo("USD");
    assertThat(command.targetCurrency()).isEqualTo("INR");
    assertThat(command.amount()).isEqualByComparingTo("100.0000");
    assertThat(command.customerFee()).isEqualByComparingTo("5.0000");
    assertThat(command.offeredRate()).isEqualByComparingTo("80.000000");
    assertThat(command.recipientAmount()).isEqualByComparingTo("7600.0000");
    assertThat(f.recipient.currency()).isEqualTo("EUR");
  }

  @Test
  void bothQuoteEntryPointsKeepPersistedPairWhenRecipientCurrencyChanges() {
    Fixture f = new Fixture();
    f.quotesAt(NOW).createOrCurrent(f.user, f.payment.id(), "quote-key");
    f.editRecipientCurrency();

    // Expiry forces fresh pricing through both entry points rather than reusing a cached quote.
    var generated =
        f.quotesAt(NOW.plusSeconds(900))
            .createOrCurrent(f.user, f.payment.id(), "quote-next-generation");
    var recommendation =
        new RouteCatalogService(f.reader, f.fx, f.routes, f.reliability, f.smart)
            .recommend(f.payment.id().toString(), RoutePreference.CHEAPEST, "currency");

    assertThat(generated.quotes().get(0).offeredRate()).isEqualTo("80.000000");
    assertThat(generated.quotes().get(0).recipientAmount()).isEqualTo("7600.0000");
    assertThat(recommendation.quotes().get(0).quote().offeredRate())
        .isEqualByComparingTo("80.000000");
    assertThat(recommendation.quotes().get(0).quote().recipientAmount())
        .isEqualByComparingTo("7600.0000");
    assertThat(f.fxPairs).containsExactly("USD/INR", "USD/INR", "USD/INR");
    PaymentSnapshot snapshot = f.reader.get(f.payment.id().toString());
    assertThat(snapshot.sourceCurrency()).isEqualTo("USD");
    assertThat(snapshot.targetCurrency()).isEqualTo("INR");
    assertThat(snapshot.destination().country()).isEqualTo("IN");
    assertThat(snapshot.destination().currency()).isEqualTo("INR");
    assertThat(f.recipient.currency()).isEqualTo("EUR");
  }

  private static class Fixture {
    final UUID user = UUID.randomUUID();
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
    final Payment payment =
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
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    final TransferRouteRepository routes = mock(TransferRouteRepository.class);
    final TransferRouteOutcomeRepository outcomes = mock(TransferRouteOutcomeRepository.class);
    final RoutePricingService pricing = new RoutePricingService(new QuotePricingPolicy());
    final RouteRecommender ranking = new RouteRecommender();
    final RouteEligibilityService eligibility =
        new RouteEligibilityService(new RailRegistry(List.of(QuoteEntryPointsTest.fakeRail())));
    final RouteReliabilityService reliability = new RouteReliabilityService(outcomes);
    final SmartRoutingService smart =
        new SmartRoutingService(routes, eligibility, reliability, pricing, ranking);
    final List<String> fxPairs = new ArrayList<>();
    final FxRateProvider fx =
        (source, target) -> {
          fxPairs.add(source + "/" + target);
          if (source.equals("USD") && target.equals("INR")) return new BigDecimal("80.000000");
          if (source.equals("USD") && target.equals("EUR")) return new BigDecimal("0.900000");
          throw new IllegalArgumentException("Unexpected currency pair");
        };
    final DbPaymentReader reader;

    Fixture() {
      when(payments.findById(payment.id())).thenReturn(Optional.of(payment));
      when(payments.lockOwned(payment.id(), user)).thenReturn(Optional.of(payment));
      var recipients = mock(RecipientRepository.class);
      when(recipients.findByIdAndUserId(recipient.id(), user)).thenReturn(Optional.of(recipient));
      reader =
          new DbPaymentReader(
              payments, recipients, new com.fasterxml.jackson.databind.ObjectMapper());
      TransferProvider provider =
          TransferProvider.create(
              UUID.randomUUID(), "TEST_BANK", "Test Bank", RailType.BANK_NETWORK, true, false, NOW);
      var route =
          QuoteEntryPointsTest.external(
              "STANDARD_BANK", "IN", "INR", "5", "0", 240, "99.5", provider);
      when(outcomes.countByRouteIds(any())).thenReturn(List.of());
      when(routes.findByRouteCode("STANDARD_BANK")).thenReturn(Optional.of(route));
      when(routes.findAllByOrderByRouteCodeAsc()).thenReturn(List.of(route));
      when(routes.findByActiveTrueOrderByRouteCodeAsc()).thenReturn(List.of(route));
      when(quotes.saveAll(any()))
          .thenAnswer(
              call -> {
                List<PaymentQuote> saved = call.getArgument(0);
                when(quotes.findByPaymentIdAndGenerationOrderByRouteAsc(
                        payment.id(), saved.get(0).generation()))
                    .thenReturn(saved);
                return saved;
              });
    }

    QuoteService quotesAt(Instant instant) {
      return new QuoteService(
          payments,
          quotes,
          fx,
          Clock.fixed(instant, ZoneOffset.UTC),
          smart,
          new PaymentOperationService(
              mock(PaymentOperationRepository.class),
              new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules(),
              Clock.systemUTC(),
              mock(org.springframework.transaction.PlatformTransactionManager.class)),
          mock(PaymentRecoveryEligibility.class));
    }

    void editRecipientCurrency() {
      recipient.update(
          "Recipient", "account", "Bank", "DE", "EUR", RecipientStatus.ACTIVE, NOW.plusSeconds(1));
    }
  }
}
