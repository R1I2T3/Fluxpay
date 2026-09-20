package com.fluxpay.adapter.transfer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.TransferRoute;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.domain.ConversionCalculation;
import com.fluxpay.domain.DestinationType;
import com.fluxpay.domain.InternalWalletDestination;
import com.fluxpay.domain.RailType;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.TransferRailCommand;
import com.fluxpay.dto.TransferRailResult;
import com.fluxpay.dto.WalletTransferResponse;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.TransferRouteRepository;
import com.fluxpay.service.RouteReliabilityService;
import com.fluxpay.service.WalletPostingService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Internal-ledger transfer rail for wallet-to-wallet delivery. It supports only internal wallet
 * destinations and delegates money movement to the already-tested {@link
 * WalletPostingService#transfer} primitive with the command's idempotency key, so repeating an
 * identical command replays the same journal instead of posting twice. Journal construction stays
 * in the posting service; this rail only reconstructs its inputs from the command and attaches the
 * winning route's metadata to the persisted response snapshot.
 */
@Component
public class InternalLedgerTransferRail implements TransferRail {
  private final WalletPostingService posting;
  private final TransferRouteRepository routes;
  private final RouteReliabilityService reliability;
  private final Clock clock;
  private final ObjectMapper mapper;

  public InternalLedgerTransferRail(
      WalletPostingService posting,
      TransferRouteRepository routes,
      RouteReliabilityService reliability,
      Clock clock,
      ObjectMapper mapper) {
    this.posting = Objects.requireNonNull(posting, "posting must not be null");
    this.routes = Objects.requireNonNull(routes, "routes must not be null");
    this.reliability = Objects.requireNonNull(reliability, "reliability must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
  }

  @Override
  public RailType type() {
    return RailType.INTERNAL_LEDGER;
  }

  @Override
  public Set<DestinationType> supportedDestinations() {
    return Set.of(DestinationType.INTERNAL_WALLET);
  }

  @Override
  public TransferRailResult execute(TransferRailCommand command) {
    Objects.requireNonNull(command, "command must not be null");
    if (!(command.destination() instanceof InternalWalletDestination destination)) {
      throw invalidRoute("Internal ledger rail supports only internal wallet destinations.");
    }
    TransferRoute route =
        routes
            .findById(command.route().id())
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "WALLET_ROUTE_UNAVAILABLE",
                        "Internal transfer route is no longer configured."));
    if (route.provider() == null || route.provider().getRailType() != RailType.INTERNAL_LEDGER) {
      throw invalidRoute(
          "Route " + route.getRouteCode() + " is not bound to the internal ledger rail.");
    }
    BigDecimal effective =
        reliability.effectiveFor(List.of(route)).get(route.getId()).effectiveReliability();
    ConversionCalculation calculation =
        new ConversionCalculation(
            command.sourceAmount(),
            command.customerFee(),
            command.sourceAmount().subtract(command.customerFee()),
            command.recipientAmount(),
            command.offeredRate());
    FxSnapshot quote =
        command.sourceCurrency().equals(command.targetCurrency())
            ? null
            : new FxSnapshot(
                command.sourceCurrency(),
                command.targetCurrency(),
                command.offeredRate(),
                clock.instant(),
                false);
    WalletTransferResponse response =
        posting.transfer(
            command.senderUserId(),
            destination.userId(),
            command.sourceCurrency(),
            command.targetCurrency(),
            calculation,
            command.recipientAmount(),
            quote,
            "Internal ledger wallet transfer via " + route.getRouteCode(),
            canonicalRequest(
                mapper,
                command.senderUserId(),
                destination.userId(),
                command.sourceCurrency(),
                command.targetCurrency(),
                command.sourceAmount(),
                command.customerFee(),
                command.recipientAmount(),
                command.offeredRate()),
            command.idempotencyKey(),
            new WalletPostingService.TransferRouting(
                route.provider().getProviderCode(),
                route.getRouteCode(),
                RailType.INTERNAL_LEDGER,
                effective));
    return TransferRailResult.completed(response.journalReference(), BigDecimal.ZERO);
  }

  /**
   * Stable request identity shared by the routing service and this rail. Only stable money fields
   * participate: the rail-assigned transfer and attempt ids identify delivery, not the request, and
   * the user note is narration-only journal text owned by the rail.
   */
  public static String canonicalRequest(
      ObjectMapper mapper,
      UUID sender,
      UUID recipient,
      String from,
      String to,
      BigDecimal gross,
      BigDecimal fee,
      BigDecimal credit,
      BigDecimal rate) {
    Objects.requireNonNull(mapper, "mapper must not be null");
    Map<String, String> body = new TreeMap<>();
    body.put("kind", "wallet-p2p");
    body.put("sender", Objects.requireNonNull(sender, "sender must not be null").toString());
    body.put(
        "recipient", Objects.requireNonNull(recipient, "recipient must not be null").toString());
    body.put("from", Objects.requireNonNull(from, "from must not be null"));
    body.put("to", Objects.requireNonNull(to, "to must not be null"));
    body.put("gross", Objects.requireNonNull(gross, "gross must not be null").toPlainString());
    body.put("fee", Objects.requireNonNull(fee, "fee must not be null").toPlainString());
    body.put("credit", Objects.requireNonNull(credit, "credit must not be null").toPlainString());
    body.put("rate", Objects.requireNonNull(rate, "rate must not be null").toPlainString());
    try {
      return mapper.writeValueAsString(body);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not canonicalize wallet transfer command", e);
    }
  }

  private static BusinessException invalidRoute(String message) {
    return new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_TRANSFER_ROUTE", message);
  }
}
