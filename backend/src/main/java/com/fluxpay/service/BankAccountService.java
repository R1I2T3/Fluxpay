package com.fluxpay.service;

import com.fluxpay.dto.*;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.BankAccountRepository;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class BankAccountService {
  private final WalletOperationService operations;
  private final BankAccountPostingService posting;
  private final BankAccountRepository accounts;
  private final WalletRequestNormalizer normalize;

  public BankAccountService(
      WalletOperationService operations,
      BankAccountPostingService posting,
      BankAccountRepository accounts,
      WalletRequestNormalizer normalize) {
    this.operations = operations;
    this.posting = posting;
    this.accounts = accounts;
    this.normalize = normalize;
  }

  public BankAccountResponse link(UUID user, BankLinkRequest request, String key) {
    normalize.user(user);
    WalletOperationService.requireKey(key);
    if (request == null) throw new IllegalArgumentException("Request body is required");
    String bankName = request.bankName() == null ? "" : request.bankName().trim();
    if (bankName.isBlank()
        || bankName.length() > 80
        || bankName.chars().anyMatch(Character::isISOControl))
      throw new IllegalArgumentException("Bank name must contain 1 to 80 printable characters");
    if (request.accountLast4() == null || !request.accountLast4().matches("[0-9]{4}"))
      throw new IllegalArgumentException("accountLast4 must contain exactly four decimal digits");
    BankLinkRequest normalized =
        new BankLinkRequest(
            bankName, request.accountLast4(), normalize.currency(request.currency()));
    return operations.execute(
        user,
        "BANK_LINK",
        key,
        normalize.json(normalized),
        BankAccountResponse.class,
        canonical -> posting.link(user, normalized, canonical, key));
  }

  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public java.util.List<BankAccountResponse> list(UUID user) {
    normalize.user(user);
    return accounts.findByUserId(user).stream()
        .map(account -> new BankAccountResponse(
            account.getId().toString(), account.getBankName(), account.getAccountLast4(),
            account.getCurrency(), account.getStatus()))
        .toList();
  }

  public WalletResponse withdraw(UUID user, WalletWithdrawRequest request, String key) {
    normalize.user(user);
    WalletOperationService.requireKey(key);
    if (request == null || request.bankAccountId() == null)
      throw new IllegalArgumentException("Bank account is required");
    String currency = normalize.currency(request.currency());
    BigDecimal amount = normalize.amount(request.amount(), currency);
    String note = normalize.note(request.note());
    var normalized =
        new WalletWithdrawRequest(request.bankAccountId(), currency, amount.toPlainString(), note);
    return operations.execute(
        user,
        "WITHDRAW",
        key,
        normalize.json(normalized),
        WalletResponse.class,
        canonical ->
            posting.withdraw(
                user, request.bankAccountId(), currency, amount, note, canonical, key));
  }

  public WalletResponse topup(UUID user, UUID bank, BankTopupRequest request, String key) {
    normalize.user(user);
    WalletOperationService.requireKey(key);
    if (request == null || bank == null)
      throw new IllegalArgumentException("Bank account and request body are required");
    String currency =
        normalize.currency(
            accounts
                .findById(bank)
                .orElseThrow(
                    () ->
                        new BusinessException(
                            HttpStatus.NOT_FOUND,
                            "BANK_ACCOUNT_NOT_FOUND",
                            "Bank account not found"))
                .getCurrency());
    BigDecimal amount = normalize.amount(request.amount(), currency);
    String note = normalize.note(request.note());
    String normalized =
        normalize.json(
            Map.of(
                "bankAccountId",
                bank.toString(),
                "currency",
                currency,
                "amount",
                amount.toPlainString(),
                "note",
                note));
    return operations.execute(
        user,
        "BANK_TOPUP",
        key,
        normalized,
        WalletResponse.class,
        canonical -> posting.topup(user, bank, currency, amount, note, canonical, key));
  }
}
