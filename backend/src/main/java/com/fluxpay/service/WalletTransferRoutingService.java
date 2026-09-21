package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.ConversionCalculation;
import com.fluxpay.domain.ConversionMath;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.InternalWalletDestination;
import com.fluxpay.domain.RouteOutcome;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.TransferProviderSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.TransferRouteSnapshot;
import com.fluxpay.dto.TransferRoutingContext;
import com.fluxpay.dto.WalletTransferRequest;
import com.fluxpay.dto.WalletTransferResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.UserRepository;
import com.fluxpay.repository.WalletOperationRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Single-call wallet-to-wallet transfer routed through the internal ledger rail. The service builds
 * an internal routing context, requests BALANCED ranking, executes the winning route exactly once
 * via the rail, and records its terminal outcome. Routing metadata is persisted in the operation
 * response snapshot by the posting primitive, so idempotent replay returns the original decision
 * without re-executing. Journal construction stays in {@link WalletPostingService}.
 */
@Service
public class WalletTransferRoutingService {
  private final WalletOperationService operations;
  private final WalletOperationRepository operationRows;
  private final UserRepository users;
  private final FxQuoteService quotes;
  private final FxQuoteValidator validator;
  private final ConversionMath math;
  private final WalletRequestNormalizer normalize;
  private final WalletRepository wallets;
  private final ObjectMapper mapper;
  private final SmartRoutingService routing;
  private final RailRegistry rails;
  private final RouteOutcomeRecorder outcomes;

  public WalletTransferRoutingService(
      WalletOperationService operations,
      WalletOperationRepository operationRows,
      UserRepository users,
      FxQuoteService quotes,
      FxQuoteValidator validator,
      ConversionMath math,
      WalletRequestNormalizer normalize,
      WalletRepository wallets,
      ObjectMapper mapper,
      SmartRoutingService routing,
      RailRegistry rails,
      RouteOutcomeRecorder outcomes) {
    this.operations = Objects.requireNonNull(operations, "operations must not be null");
    this.operationRows = Objects.requireNonNull(operationRows, "operationRows must not be null");
    this.users = Objects.requireNonNull(users, "users must not be null");
    this.quotes = Objects.requireNonNull(quotes, "quotes must not be null");
    this.validator = Objects.requireNonNull(validator, "validator must not be null");
    this.math = Objects.requireNonNull(math, "math must not be null");
    this.normalize = Objects.requireNonNull(normalize, "normalize must not be null");
    this.wallets = Objects.requireNonNull(wallets, "wallets must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    this.routing = Objects.requireNonNull(routing, "routing must not be null");
    this.rails = Objects.requireNonNull(rails, "rails must not be null");
    this.outcomes = Objects.requireNonNull(outcomes, "outcomes must not be null");
  }

  public WalletTransferResponse transfer(UUID user, WalletTransferRequest request, String key) {
    normalize.user(user);
    WalletOperationService.requireKey(key);
    if (request == null) throw new IllegalArgumentException("Request body is required");
    String email =
        request.toEmail() == null ? null : request.toEmail().trim().toLowerCase(Locale.ROOT);
    if ((request.toUserId() == null) == (email == null) || (email != null && email.isBlank()))
      throw new IllegalArgumentException("Exactly one recipient selector is required");
    String from = normalize.currency(request.fromCurrency());
    String to = normalize.currency(request.toCurrency());
    String mode =
        request.amountMode() == null ? "" : request.amountMode().trim().toUpperCase(Locale.ROOT);
    if (!mode.equals("SOURCE") && !mode.equals("TARGET"))
      throw new IllegalArgumentException("amountMode must be SOURCE or TARGET");
    BigDecimal amount = normalize.amount(request.amount(), mode.equals("SOURCE") ? from : to);
    String note = normalize.note(request.note());
    WalletTransferRequest canonicalRequest =
        new WalletTransferRequest(
            request.toUserId(), email, from, to, amount.toPlainString(), mode, note);
    UUID attemptId =
        UUID.nameUUIDFromBytes(("wallet-p2p:" + user + ":" + key).getBytes(StandardCharsets.UTF_8));
    String operationKey = "payout:" + attemptId;
    String normalized = normalize.json(canonicalRequest);
    AtomicReference<PreparedTransfer> prepared = new AtomicReference<>();
    return operations.execute(
        user,
        "TRANSFER",
        operationKey,
        normalized,
        WalletTransferResponse.class,
        canonical -> {
          if (prepared.get() == null) {
            prepared.set(prepare(user, request.toUserId(), email, from, to, amount, mode));
          }
          return executeRouted(user, prepared.get(), canonical, attemptId, operationKey);
        });
  }

  private PreparedTransfer prepare(
      UUID user,
      UUID recipientSelector,
      String email,
      String from,
      String to,
      BigDecimal amount,
      String mode) {
    UUID recipient =
        (recipientSelector != null
                ? users.findById(recipientSelector)
                : users.findByCanonicalEmail(email))
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "RECIPIENT_NOT_FOUND", "Recipient does not exist"))
            .getId();
    if (user.equals(recipient) && from.equals(to)) {
      throw new IllegalArgumentException("Same-user same-currency transfer is a no-op");
    }
    ConversionCalculation calculation;
    FxSnapshot quote;
    BigDecimal targetCredit;
    if (from.equals(to)) {
      calculation =
          new ConversionCalculation(
              amount, BigDecimal.ZERO.setScale(4), amount, amount, BigDecimal.ONE);
      quote = null;
      targetCredit = amount;
    } else {
      quote = validator.accept(quotes.snapshot(from, to), from, to);
      calculation =
          mode.equals("SOURCE")
              ? math.calculate(from, to, amount, quote.rate())
              : math.calculateTarget(from, to, amount, quote.rate());
      targetCredit = mode.equals("TARGET") ? amount : calculation.credit();
    }
    TransferRoutingContext context =
        new TransferRoutingContext(
            DestinationType.INTERNAL_WALLET,
            null,
            to,
            mode.equals("SOURCE") ? amount : calculation.gross(),
            quote == null ? BigDecimal.ONE : quote.rate());
    return new PreparedTransfer(recipient, from, to, calculation, targetCredit, quote, context);
  }

  private WalletTransferResponse executeRouted(
      UUID user,
      PreparedTransfer prepared,
      String normalizedRequest,
      UUID attemptId,
      String operationKey) {
    UUID recipient = prepared.recipient();
    String from = prepared.from();
    String to = prepared.to();
    ConversionCalculation calculation = prepared.calculation();
    BigDecimal targetCredit = prepared.targetCredit();
    FxSnapshot quote = prepared.quote();
    TransferRoutingContext context = prepared.context();
    var recommendation = routing.recommend(context, RoutePreference.BALANCED);
    var winner = recommendation.recommended();
    TransferRoute route = winner.quote().route();
    if (route.getDestinationType() != DestinationType.INTERNAL_WALLET) {
      throw invalidRoute(route);
    }
    TransferRail rail =
        rails.requireCompatible(route.provider().getRailType(), DestinationType.INTERNAL_WALLET);
    // The destination wallet id is informational for the rail (posting resolves or creates
    // the recipient wallet); fall back to a random id when the wallet does not exist yet.
    UUID recipientWallet =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(recipient, to, WalletAccountRole.CUSTOMER)
            .map(Wallet::getId)
            .orElseGet(UUID::randomUUID);
    TransferRailCommand command =
        new TransferRailCommand(
            UUID.randomUUID(),
            attemptId,
            user,
            calculation.gross(),
            from,
            targetCredit,
            to,
            new TransferProviderSnapshot(
                route.provider().getId(),
                route.provider().getProviderCode(),
                route.provider().getRailType()),
            new TransferRouteSnapshot(
                route.getId(),
                route.getRouteCode(),
                route.getDestinationType(),
                route.provider().getId()),
            new InternalWalletDestination(recipient, recipientWallet, to),
            1,
            calculation.fee(),
            calculation.rate(),
            operationKey,
            normalizedRequest);
    TransferRailResult result;
    try {
      result = rail.execute(command);
    } catch (RuntimeException failed) {
      outcomes.record(route.getId(), "wallet:p2p:" + command.transferId(), RouteOutcome.FAILED);
      throw failed;
    }
    if (result == null || result.outcome() == TransferRailResult.Outcome.UNCERTAIN) {
      throw new IllegalStateException("Internal ledger delivery must settle synchronously");
    }
    if (result.outcome() == TransferRailResult.Outcome.FAILED) {
      outcomes.record(route.getId(), "wallet:p2p:" + command.transferId(), RouteOutcome.FAILED);
      throw new BusinessException(
          HttpStatus.INTERNAL_SERVER_ERROR,
          "WALLET_TRANSFER_FAILED",
          "Internal ledger execution failed: " + result.errorCode());
    }
    outcomes.record(route.getId(), result.providerRef(), RouteOutcome.COMPLETED);
    return operationRows
        .findByUserIdAndOperationTypeAndClientKey(user, "TRANSFER", operationKey)
        .map(row -> snapshot(row.getResponseSnapshot()))
        .orElseGet(
            () ->
                fallback(
                    route,
                    winner.quote().effectiveReliability(),
                    user,
                    recipient,
                    from,
                    to,
                    calculation,
                    targetCredit,
                    quote,
                    result.providerRef()));
  }

  private WalletTransferResponse snapshot(String stored) {
    try {
      return mapper.readValue(stored, WalletTransferResponse.class);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Stored wallet operation response is invalid", e);
    }
  }

  private WalletTransferResponse fallback(
      TransferRoute route,
      BigDecimal effectiveReliability,
      UUID user,
      UUID recipient,
      String from,
      String to,
      ConversionCalculation calculation,
      BigDecimal targetCredit,
      FxSnapshot quote,
      String journalReference) {
    UUID sourceWallet =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(user, from, WalletAccountRole.CUSTOMER)
            .map(Wallet::getId)
            .orElseThrow(
                () -> new IllegalStateException("Source wallet is missing after transfer"));
    UUID targetWallet =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(recipient, to, WalletAccountRole.CUSTOMER)
            .map(Wallet::getId)
            .orElseThrow(
                () -> new IllegalStateException("Target wallet is missing after transfer"));
    return new WalletTransferResponse(
        sourceWallet.toString(),
        targetWallet.toString(),
        from,
        to,
        calculation.gross().toPlainString(),
        calculation.fee().toPlainString(),
        calculation.net().toPlainString(),
        targetCredit.toPlainString(),
        quote == null ? null : calculation.rate().toPlainString(),
        quote == null ? null : FxQuoteValidator.quoteId(quote),
        journalReference,
        route.provider().getProviderCode(),
        route.getRouteCode(),
        route.provider().getRailType(),
        effectiveReliability);
  }

  private static BusinessException invalidRoute(TransferRoute route) {
    return new BusinessException(
        HttpStatus.BAD_REQUEST,
        "INVALID_TRANSFER_ROUTE",
        "Route " + route.getRouteCode() + " does not support internal wallet transfer.");
  }

  private record PreparedTransfer(
      UUID recipient,
      String from,
      String to,
      ConversionCalculation calculation,
      BigDecimal targetCredit,
      FxSnapshot quote,
      TransferRoutingContext context) {}
}
