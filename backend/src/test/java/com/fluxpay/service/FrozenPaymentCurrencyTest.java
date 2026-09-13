package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.FxRateProvider;
import com.fluxpay.common.contracts.PayoutProvider;
import com.fluxpay.domain.QuotePricingPolicy;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.PayoutCmd;
import com.fluxpay.dto.PayoutResult;
import com.fluxpay.messaging.EventPublisher;
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
    var quoted = f.quotesAt(NOW).createOrCurrent(f.user, f.payment.id());
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
    var execution =
        new PayoutExecutionService(
            f.reader,
            f.routes,
            mock(PayoutAttemptRepository.class),
            mock(EventPublisher.class),
            List.of(provider),
            clock,
            new SelectedQuoteService(f.payments, f.quotes, clock, f.routes));

    execution.submit(f.payment.id().toString(), "STANDARD_BANK", "frozen-currency");

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
    f.quotesAt(NOW).createOrCurrent(f.user, f.payment.id());
    f.editRecipientCurrency();

    // Expiry forces fresh pricing through both entry points rather than reusing a cached quote.
    var generated = f.quotesAt(NOW.plusSeconds(900)).createOrCurrent(f.user, f.payment.id());
    var recommendation =
        new RouteCatalogService(
                f.reader, f.fx, f.ranking, f.routes, mock(RouteMetrics.class), f.pricing)
            .recommend(f.payment.id().toString(), RoutePreference.CHEAPEST, "currency");

    assertThat(generated.quotes().get(0).offeredRate()).isEqualTo("80.000000");
    assertThat(generated.quotes().get(0).recipientAmount()).isEqualTo("7600.0000");
    assertThat(recommendation.quotes().get(0).offeredRate()).isEqualByComparingTo("80.000000");
    assertThat(recommendation.quotes().get(0).recipientAmount()).isEqualByComparingTo("7600.0000");
    assertThat(f.fxPairs).containsExactly("USD/INR", "USD/INR", "USD/INR");
    PaymentSnapshot snapshot = f.reader.get(f.payment.id().toString());
    assertThat(snapshot.sourceCurrency()).isEqualTo("USD");
    assertThat(snapshot.targetCurrency()).isEqualTo("INR");
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
            "{}",
            NOW);
    final PaymentRepository payments = mock(PaymentRepository.class);
    final PaymentQuoteRepository quotes = mock(PaymentQuoteRepository.class);
    final PayoutRouteRepository routes = mock(PayoutRouteRepository.class);
    final RoutePricingService pricing = new RoutePricingService(new QuotePricingPolicy());
    final RouteRecommender ranking = new RouteRecommender();
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
      var route =
          PayoutRoute.seed(
              UUID.randomUUID(),
              "STANDARD_BANK",
              "Bank",
              "Bank",
              "STANDARD",
              "5",
              "0",
              240,
              "99.5");
      when(routes.findByCode("STANDARD_BANK")).thenReturn(Optional.of(route));
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
          payments, quotes, fx, Clock.fixed(instant, ZoneOffset.UTC), routes, pricing, ranking);
    }

    void editRecipientCurrency() {
      recipient.update(
          "Recipient", "account", "Bank", "DE", "EUR", RecipientStatus.ACTIVE, NOW.plusSeconds(1));
    }
  }
}
