package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.LedgerTransactionCategory;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.domain.ConversionCalculation;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.exception.OperationRaceException;
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

  private final SystemAccountService systemAccounts;
  private final WalletRepository wallets;
  private final WalletOperationRepository operations;
  private final LedgerJournalService journals;
  private final ObjectMapper objectMapper;
  private final FxQuoteValidator quoteValidator;

  public WalletPostingService(
      SystemAccountService systemAccounts,
      WalletRepository wallets,
      WalletOperationRepository operations,
      LedgerJournalService journals,
      ObjectMapper objectMapper,
      FxQuoteValidator quoteValidator) {
    this.systemAccounts = systemAccounts;
    this.wallets = wallets;
    this.operations = operations;
    this.journals = journals;
    this.objectMapper = objectMapper;
    this.quoteValidator = quoteValidator;
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public WalletResponse receiveDemo(
      UUID userId, String currency, BigDecimal amount, String normalizedRequest, String clientKey) {
    Wallet clearing = systemAccounts.require(currency, WalletAccountRole.DEMO_CLEARING);
    if (operations
        .findByUserIdAndOperationTypeAndClientKey(userId, OPERATION_TYPE, clientKey)
        .isPresent()) {
      throw new OperationRaceException();
    }

    UUID operationId = UUID.randomUUID();
    String journalReference = "wallet:demo:" + operationId;
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

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public WalletConvertResponse convert(
      UUID userId,
      String from,
      String to,
      ConversionCalculation calculation,
      FxSnapshot snapshot,
      String quoteId,
      String normalizedRequest,
      String clientKey) {
    BigDecimal sourceAmount = calculation.gross();
    BigDecimal fee = calculation.fee();
    BigDecimal netAmount = calculation.net();
    BigDecimal creditedAmount = calculation.credit();
    if (calculation.rate().compareTo(snapshot.rate()) != 0) {
      throw new IllegalArgumentException("Calculated FX rate does not match the accepted quote");
    }
    Wallet sourceClearing = systemAccounts.require(from, WalletAccountRole.FX_CLEARING);
    Wallet targetClearing = systemAccounts.require(to, WalletAccountRole.FX_CLEARING);
    Optional<Wallet> feeRevenue =
        fee.signum() > 0
            ? Optional.of(systemAccounts.require(from, WalletAccountRole.FEE_REVENUE))
            : Optional.empty();
    if (operations
        .findByUserIdAndOperationTypeAndClientKey(userId, "CONVERT", clientKey)
        .isPresent()) {
      throw new OperationRaceException();
    }

    UUID operationId = UUID.randomUUID();
    String journalReference = "wallet:fx:" + operationId;
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

    List<LedgerJournalLine> lines = new ArrayList<>();
    lines.add(
        line(
            source,
            "DEBIT",
            sourceAmount,
            from,
            operationId,
            "source",
            "FX conversion gross debit",
            calculation.rate(),
            quoteId));
    lines.add(
        line(
            sourceClearing,
            "CREDIT",
            netAmount,
            from,
            operationId,
            "source-clearing",
            "FX source clearing credit",
            calculation.rate(),
            quoteId));
    if (fee.signum() > 0) {
      lines.add(
          line(
              feeRevenue.orElseThrow(),
              "CREDIT",
              fee,
              from,
              operationId,
              "fee",
              "FX conversion fee",
              calculation.rate(),
              quoteId));
    }
    lines.add(
        line(
            targetClearing,
            "DEBIT",
            creditedAmount,
            to,
            operationId,
            "target-clearing",
            "FX target clearing debit",
            calculation.rate(),
            quoteId));
    lines.add(
        line(
            target,
            "CREDIT",
            creditedAmount,
            to,
            operationId,
            "target",
            "FX conversion credit",
            calculation.rate(),
            quoteId));
    String sourceKey = operationId + ":source";
    journals.postReserved(
        journalReference,
        LedgerTransactionCategory.SELF_TRANSFER,
        lines,
        new WalletReservation(source.getId(), sourceKey, from, sourceAmount),
        () -> quoteValidator.accept(snapshot, from, to));

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
            journalReference);
    operation.complete(snapshot(response));
    operations.saveAndFlush(operation);
    return response;
  }

  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
  public com.fluxpay.dto.WalletTransferResponse transfer(
      UUID userId,
      UUID recipientId,
      String from,
      String to,
      ConversionCalculation calculation,
      BigDecimal targetCredit,
      FxSnapshot quote,
      String note,
      String normalizedRequest,
      String clientKey) {
    if (operations
        .findByUserIdAndOperationTypeAndClientKey(userId, "TRANSFER", clientKey)
        .isPresent()) throw new OperationRaceException();
    UUID id = UUID.randomUUID();
    String reference = "wallet:p2p:" + id;
    WalletOperation operation =
        new WalletOperation(id, userId, "TRANSFER", clientKey, normalizedRequest, reference);
    operations.saveAndFlush(operation);
    Wallet source =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(userId, from, WalletAccountRole.CUSTOMER)
            .orElseThrow(
                () -> new IllegalArgumentException("Source customer wallet not found for " + from));
    Wallet target =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(recipientId, to, WalletAccountRole.CUSTOMER)
            .orElseGet(
                () ->
                    wallets.saveAndFlush(new Wallet(recipientId, to, WalletAccountRole.CUSTOMER)));
    List<LedgerJournalLine> lines = new ArrayList<>();
    BigDecimal rate = quote == null ? null : calculation.rate();
    String quoteId = quote == null ? null : FxQuoteValidator.quoteId(quote);
    lines.add(line(source, "DEBIT", calculation.gross(), from, id, "source", note, rate, quoteId));
    if (quote != null) {
      if (calculation.rate().compareTo(quote.rate()) != 0)
        throw new IllegalArgumentException("Calculated FX rate does not match accepted quote");
      lines.add(
          line(
              systemAccounts.require(from, WalletAccountRole.FX_CLEARING),
              "CREDIT",
              calculation.net(),
              from,
              id,
              "source-clearing",
              note,
              rate,
              quoteId));
      if (calculation.fee().signum() > 0)
        lines.add(
            line(
                systemAccounts.require(from, WalletAccountRole.FEE_REVENUE),
                "CREDIT",
                calculation.fee(),
                from,
                id,
                "fee",
                note,
                rate,
                quoteId));
      BigDecimal clearing =
          calculation.net().multiply(rate).setScale(4, java.math.RoundingMode.HALF_UP);
      lines.add(
          line(
              systemAccounts.require(to, WalletAccountRole.FX_CLEARING),
              "DEBIT",
              clearing,
              to,
              id,
              "target-clearing",
              note,
              rate,
              quoteId));
      BigDecimal difference = clearing.subtract(targetCredit);
      if (difference.signum() != 0)
        lines.add(
            line(
                systemAccounts.require(to, WalletAccountRole.FX_GAIN_LOSS),
                difference.signum() > 0 ? "CREDIT" : "DEBIT",
                difference.abs(),
                to,
                id,
                "rounding",
                note,
                rate,
                quoteId));
    }
    lines.add(line(target, "CREDIT", targetCredit, to, id, "target", note, rate, quoteId));
    LedgerTransactionCategory category =
        userId.equals(recipientId)
            ? LedgerTransactionCategory.SELF_TRANSFER
            : LedgerTransactionCategory.WALLET_TO_WALLET;
    journals.postReserved(
        reference,
        category,
        lines,
        new WalletReservation(source.getId(), id + ":source", from, calculation.gross()),
        () -> {
          if (quote != null) quoteValidator.accept(quote, from, to);
        });
    var response =
        new com.fluxpay.dto.WalletTransferResponse(
            source.getId().toString(),
            target.getId().toString(),
            from,
            to,
            calculation.gross().toPlainString(),
            calculation.fee().toPlainString(),
            calculation.net().toPlainString(),
            targetCredit.toPlainString(),
            rate == null ? null : rate.toPlainString(),
            quoteId,
            reference);
    try {
      operation.complete(objectMapper.writeValueAsString(response));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Could not store transfer response", e);
    }
    operations.saveAndFlush(operation);
    return response;
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

  private static LedgerJournalLine line(
      Wallet wallet,
      String entryType,
      BigDecimal amount,
      String currency,
      UUID operationId,
      String role,
      String narration,
      BigDecimal rate,
      String quoteId) {
    return new LedgerJournalLine(
        wallet.getId(),
        entryType,
        amount,
        currency,
        operationId + ":" + role,
        narration,
        rate,
        quoteId);
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
