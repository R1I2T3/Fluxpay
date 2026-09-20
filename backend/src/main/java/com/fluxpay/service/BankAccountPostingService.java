package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.KycGate;
import com.fluxpay.dto.*;
import com.fluxpay.exception.*;
import com.fluxpay.repository.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class BankAccountPostingService {
  private final BankAccountRepository accounts;
  private final WalletRepository wallets;
  private final WalletOperationRepository operations;
  private final WalletTopupRepository topups;
  private final LedgerJournalService journals;
  private final SystemAccountService system;
  private final KycGate kyc;
  private final Clock clock;
  private final WalletRequestNormalizer normalize;

  public BankAccountPostingService(
      BankAccountRepository accounts,
      WalletRepository wallets,
      WalletOperationRepository operations,
      WalletTopupRepository topups,
      LedgerJournalService journals,
      SystemAccountService system,
      KycGate kyc,
      Clock clock,
      WalletRequestNormalizer normalize) {
    this.accounts = accounts;
    this.wallets = wallets;
    this.operations = operations;
    this.topups = topups;
    this.journals = journals;
    this.system = system;
    this.kyc = kyc;
    this.clock = clock;
    this.normalize = normalize;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public BankAccountResponse link(
      UUID user, BankLinkRequest request, String canonical, String key) {
    WalletOperation operation = begin(user, "BANK_LINK", "wallet:bank:", canonical, key);
    BankAccount account =
        accounts.save(
            new BankAccount(
                user, request.bankName(), request.accountLast4(), request.currency(), "VERIFIED"));
    BankAccountResponse response =
        new BankAccountResponse(
            account.getId().toString(),
            account.getBankName(),
            account.getAccountLast4(),
            account.getCurrency(),
            account.getStatus());
    complete(operation, response);
    return response;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WalletResponse withdraw(
      UUID user,
      UUID bank,
      String currency,
      BigDecimal amount,
      String note,
      String canonical,
      String key) {
    requireBank(user, bank, currency);
    WalletOperation operation = begin(user, "WITHDRAW", "wallet:withdraw:", canonical, key);
    Wallet customer =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(user, currency, WalletAccountRole.CUSTOMER)
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Customer wallet not found"));
    Wallet clearing = system.require(currency, WalletAccountRole.PAYOUT_CLEARING);
    List<LedgerJournalLine> lines = lines(operation, customer, clearing, amount, currency, note);
    journals.postReserved(
        operation.getJournalReference(),
        LedgerTransactionCategory.SEND_MONEY,
        lines,
        new WalletReservation(customer.getId(), operation.getId() + ":debit", currency, amount),
        () -> requireBank(user, bank, currency));
    WalletResponse response = response(customer, operation);
    complete(operation, response);
    return response;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public WalletResponse topup(
      UUID user,
      UUID bank,
      String currency,
      BigDecimal amount,
      String note,
      String canonical,
      String key) {
    requireBank(user, bank, currency);
    requireKyc(user);
    WalletOperation operation = begin(user, "BANK_TOPUP", "wallet:topup:", canonical, key);
    Wallet clearing = system.require(currency, WalletAccountRole.DEMO_CLEARING);
    Wallet customer =
        wallets
            .findByUserIdAndCurrencyAndAccountRole(user, currency, WalletAccountRole.CUSTOMER)
            .orElseGet(
                () -> wallets.saveAndFlush(new Wallet(user, currency, WalletAccountRole.CUSTOMER)));
    journals.postLocked(
        operation.getJournalReference(),
        LedgerTransactionCategory.WALLET_TOPUP,
        lines(operation, clearing, customer, amount, currency, note),
        () -> {
          requireBank(user, bank, currency);
          requireKyc(user);
          Instant start =
              clock
                  .instant()
                  .atZone(ZoneOffset.UTC)
                  .toLocalDate()
                  .atStartOfDay(ZoneOffset.UTC)
                  .toInstant();
          BigDecimal posted =
              topups.sumCreditsForDay(
                  customer.getId(),
                  LedgerTransactionCategory.WALLET_TOPUP,
                  start,
                  start.plusSeconds(86400));
          if (posted.add(amount).compareTo(new BigDecimal("10000.00")) > 0)
            throw new BusinessException(
                HttpStatus.CONFLICT,
                "TOPUP_CAP_EXCEEDED",
                "Daily top-up cap of 10000.00 exceeded for " + currency);
        });
    WalletResponse response = response(customer, operation);
    complete(operation, response);
    return response;
  }

  private BankAccount requireBank(UUID user, UUID id, String currency) {
    BankAccount account =
        accounts
            .findById(id)
            .filter(bank -> bank.getUserId().equals(user))
            .orElseThrow(
                () ->
                    new BusinessException(
                        HttpStatus.NOT_FOUND, "BANK_ACCOUNT_NOT_FOUND", "Bank account not found"));
    if (!"VERIFIED".equals(account.getStatus()))
      throw new BusinessException(
          HttpStatus.CONFLICT, "BANK_ACCOUNT_NOT_VERIFIED", "Bank account must be verified");
    if (!currency.equals(account.getCurrency()))
      throw new BusinessException(
          HttpStatus.BAD_REQUEST,
          "BANK_CURRENCY_MISMATCH",
          "Bank account currency does not match wallet currency");
    return account;
  }

  private void requireKyc(UUID user) {
    if (!kyc.isVerified(user))
      throw new BusinessException(
          HttpStatus.FORBIDDEN, "KYC_REQUIRED", "Verified KYC is required for top-up");
  }

  private WalletOperation begin(
      UUID user, String type, String prefix, String canonical, String key) {
    if (operations.findByUserIdAndOperationTypeAndClientKey(user, type, key).isPresent())
      throw new OperationRaceException();
    UUID id = UUID.randomUUID();
    return operations.saveAndFlush(
        new WalletOperation(id, user, type, key, canonical, prefix + id));
  }

  private void complete(WalletOperation operation, Object response) {
    operation.complete(normalize.json(response));
    operations.saveAndFlush(operation);
  }

  private static List<LedgerJournalLine> lines(
      WalletOperation operation,
      Wallet debit,
      Wallet credit,
      BigDecimal amount,
      String currency,
      String note) {
    return List.of(
        new LedgerJournalLine(
            debit.getId(), "DEBIT", amount, currency, operation.getId() + ":debit", note),
        new LedgerJournalLine(
            credit.getId(), "CREDIT", amount, currency, operation.getId() + ":credit", note));
  }

  private static WalletResponse response(Wallet wallet, WalletOperation operation) {
    return new WalletResponse(
        wallet.getId().toString(),
        wallet.getCurrency(),
        wallet.getBalance().toPlainString(),
        wallet.getHeldBalance().toPlainString(),
        wallet.getAvailableBalance().toPlainString(),
        operation.getJournalReference());
  }
}
