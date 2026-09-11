package com.fluxpay.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.Wallet;
import com.fluxpay.beans.WalletAccountRole;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.config.M2DemoFundingConfig;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.repository.WalletOperationRepository;
import com.fluxpay.repository.WalletRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WalletPostingService {
  private static final String OPERATION_TYPE = "RECEIVE_DEMO";

  private final M2DemoFundingConfig config;
  private final WalletRepository wallets;
  private final WalletOperationRepository operations;
  private final LedgerJournalService journals;
  private final ObjectMapper objectMapper;

  public WalletPostingService(
      M2DemoFundingConfig config,
      WalletRepository wallets,
      WalletOperationRepository operations,
      LedgerJournalService journals,
      ObjectMapper objectMapper) {
    this.config = config;
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

  private String snapshot(WalletResponse response) {
    try {
      return objectMapper.writeValueAsString(response);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Could not store demo-funding response", exception);
    }
  }
}
