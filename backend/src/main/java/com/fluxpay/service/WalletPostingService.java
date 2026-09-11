package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.config.M2DemoFundingConfig;
import com.fluxpay.config.M2FxConfig;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.repository.WalletOperationRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletPostingService {
  private static final String OPERATION_TYPE = "RECEIVE_DEMO";

  private final M2DemoFundingConfig config;
  private final M2FxConfig fxConfig;
  private final WalletRepository wallets;
  private final WalletOperationRepository operations;
  private final LedgerJournalService journals;
  private final ObjectMapper objectMapper;

  public WalletPostingService(
      M2DemoFundingConfig config,
      M2FxConfig fxConfig,
      WalletRepository wallets,
      WalletOperationRepository operations,
      LedgerJournalService journals,
      ObjectMapper objectMapper) {
    this.config = config;
    this.fxConfig = fxConfig;
    this.wallets = wallets;
    this.operations = operations;
    this.journals = journals;
    this.objectMapper = objectMapper;
  }

  @Transactional
  public WalletResponse receiveDemo(
      UUID userId, String currency, BigDecimal amount, String normalizedRequest, String clientKey) {
    if (operations
        .findByUserIdAndOperationTypeAndClientKey(userId, OPERATION_TYPE, clientKey)
        .isPresent()) {
      throw new OperationRaceException();
    }

    UUID operationId = UUID.randomUUID();
    String journalReference = "M2-DEMO-" + operationId;
    WalletOperation operation =
        new WalletOperation(
            operationId, userId, OPERATION_TYPE, clientKey, normalizedRequest, journalReference);
    operations.saveAndFlush(operation);

    Wallet customer =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(userId, currency, WalletAccountRole.CUSTOMER)
            .orElseGet(
                () ->
                    wallets.saveAndFlush(new Wallet(userId, currency, WalletAccountRole.CUSTOMER)));

    UUID systemUserId = config.getSystemUserId();
    if (systemUserId == null) {
      throw new DemoClearingWalletNotFoundException(currency);
    }
    Wallet clearing =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(
                systemUserId, currency, WalletAccountRole.DEMO_CLEARING)
            .orElseThrow(() -> new DemoClearingWalletNotFoundException(currency));

    journals.post(
        journalReference,
        List.of(
            new LedgerJournalLine(
                clearing.getId(),
                "DEBIT",
                amount,
                currency,
                operationId + ":clearing",
                "Demo funding source"),
            new LedgerJournalLine(
                customer.getId(),
                "CREDIT",
                amount,
                currency,
                operationId + ":customer",
                "Demo funding credit")));

    WalletResponse response =
        new WalletResponse(
            customer.getId().toString(),
            customer.getCurrency(),
            customer.getBalance().toPlainString(),
            customer.getHeldBalance().toPlainString(),
            customer.getAvailableBalance().toPlainString(),
            journalReference);
    operation.complete(snapshot(response));
    operations.saveAndFlush(operation);
    return response;
  }

  @Transactional
  public WalletConvertResponse convert(
      UUID userId,
      String from,
      String to,
      BigDecimal sourceAmount,
      BigDecimal fee,
      BigDecimal netAmount,
      BigDecimal creditedAmount,
      FxSnapshot snapshot,
      String normalizedRequest,
      String clientKey) {
    if (operations
        .findByUserIdAndOperationTypeAndClientKey(userId, "CONVERT", clientKey)
        .isPresent()) {
      throw new OperationRaceException();
    }

    UUID operationId = UUID.randomUUID();
    String journalReference = "M2-FX-" + operationId;
    WalletOperation operation =
        new WalletOperation(
            operationId, userId, "CONVERT", clientKey, normalizedRequest, journalReference);
    operations.saveAndFlush(operation);

    Wallet source =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(userId, from, WalletAccountRole.CUSTOMER)
            .orElseThrow(
                () -> new IllegalArgumentException("Source customer wallet not found for " + from));
    Wallet target =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(userId, to, WalletAccountRole.CUSTOMER)
            .orElseGet(
                () -> wallets.saveAndFlush(new Wallet(userId, to, WalletAccountRole.CUSTOMER)));

    UUID systemUserId = fxConfig.getSystemUserId();
    if (systemUserId == null) {
      throw new FxSystemWalletNotFoundException(from, WalletAccountRole.FX_CLEARING.name());
    }
    Wallet sourceClearing = systemWallet(systemUserId, from, WalletAccountRole.FX_CLEARING);
    Wallet targetClearing = systemWallet(systemUserId, to, WalletAccountRole.FX_CLEARING);
    Optional<Wallet> feeRevenue =
        fee.signum() > 0
            ? Optional.of(systemWallet(systemUserId, from, WalletAccountRole.FEE_REVENUE))
            : Optional.empty();

    List<UUID> walletIds = new ArrayList<>();
    walletIds.add(source.getId());
    walletIds.add(target.getId());
    walletIds.add(sourceClearing.getId());
    walletIds.add(targetClearing.getId());
    feeRevenue.map(Wallet::getId).ifPresent(walletIds::add);
    wallets.findAllByIdForUpdate(walletIds);

    List<LedgerJournalLine> lines = new ArrayList<>();
    lines.add(
        line(
            source,
            "DEBIT",
            sourceAmount,
            from,
            operationId,
            "source",
            "FX conversion gross debit"));
    lines.add(
        line(
            sourceClearing,
            "CREDIT",
            netAmount,
            from,
            operationId,
            "source-clearing",
            "FX source clearing credit"));
    if (fee.signum() > 0) {
      lines.add(
          line(
              feeRevenue.orElseThrow(),
              "CREDIT",
              fee,
              from,
              operationId,
              "fee",
              "FX conversion fee"));
    }
    lines.add(
        line(
            targetClearing,
            "DEBIT",
            creditedAmount,
            to,
            operationId,
            "target-clearing",
            "FX target clearing debit"));
    lines.add(
        line(target, "CREDIT", creditedAmount, to, operationId, "target", "FX conversion credit"));
    journals.post(journalReference, lines);

    WalletConvertResponse response =
        new WalletConvertResponse(
            source.getId().toString(),
            target.getId().toString(),
            from,
            to,
            sourceAmount.toPlainString(),
            fee.toPlainString(),
            netAmount.toPlainString(),
            creditedAmount.toPlainString(),
            snapshot.rate().toPlainString(),
            snapshot.fetchedAt().toString(),
            snapshot.stale(),
            snapshot.mock(),
            journalReference);
    operation.complete(snapshot(response));
    operations.saveAndFlush(operation);
    return response;
  }

  private Wallet systemWallet(UUID userId, String currency, WalletAccountRole role) {
    return wallets
        .findByUserIdAndCurrencyAndAccountRole(userId, currency, role)
        .orElseThrow(() -> new FxSystemWalletNotFoundException(currency, role.name()));
  }

  private static LedgerJournalLine line(
      Wallet wallet,
      String entryType,
      BigDecimal amount,
      String currency,
      UUID operationId,
      String role,
      String narration) {
    return new LedgerJournalLine(
        wallet.getId(), entryType, amount, currency, operationId + ":" + role, narration);
  }

  private String snapshot(WalletResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not store demo-funding response", exception);
    }
  }

  private String snapshot(WalletConvertResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not store wallet-conversion response", exception);
    }
  }
}
