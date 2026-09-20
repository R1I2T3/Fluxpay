package com.fluxpay.service;

import com.fluxpay.domain.ConversionCalculation;
import com.fluxpay.domain.ConversionMath;
import com.fluxpay.dto.*;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.UserRepository;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WalletTransferService {
  private final WalletOperationService operations;
  private final WalletPostingService posting;
  private final UserRepository users;
  private final FxQuoteService quotes;
  private final FxQuoteValidator validator;
  private final ConversionMath math;
  private final WalletRequestNormalizer normalize;

  public WalletTransferService(
      WalletOperationService operations,
      WalletPostingService posting,
      UserRepository users,
      FxQuoteService quotes,
      FxQuoteValidator validator,
      ConversionMath math,
      WalletRequestNormalizer normalize) {
    this.operations = operations;
    this.posting = posting;
    this.users = users;
    this.quotes = quotes;
    this.validator = validator;
    this.math = math;
    this.normalize = normalize;
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
    AtomicReference<FxSnapshot> accepted = new AtomicReference<>();
    return operations.execute(
        user,
        "TRANSFER",
        key,
        normalize.json(canonicalRequest),
        WalletTransferResponse.class,
        canonical -> {
          UUID recipient =
              (request.toUserId() != null
                      ? users.findById(request.toUserId())
                      : users.findByCanonicalEmail(email))
                  .orElseThrow(
                      () ->
                          new BusinessException(
                              HttpStatus.NOT_FOUND,
                              "RECIPIENT_NOT_FOUND",
                              "Recipient does not exist"))
                  .getId();
          if (user.equals(recipient) && from.equals(to))
            throw new IllegalArgumentException("Same-user same-currency transfer is a no-op");
          if (from.equals(to))
            return posting.transfer(
                user,
                recipient,
                from,
                to,
                new ConversionCalculation(
                    amount, BigDecimal.ZERO.setScale(4), amount, amount, BigDecimal.ONE),
                amount,
                null,
                note,
                canonical,
                key);
          if (accepted.get() == null)
            accepted.set(validator.accept(quotes.snapshot(from, to), from, to));
          FxSnapshot quote = accepted.get();
          ConversionCalculation calculation =
              mode.equals("SOURCE")
                  ? math.calculate(from, to, amount, quote.rate())
                  : math.calculateTarget(from, to, amount, quote.rate());
          return posting.transfer(
              user,
              recipient,
              from,
              to,
              calculation,
              mode.equals("TARGET") ? amount : calculation.credit(),
              quote,
              note,
              canonical,
              key);
        });
  }
}
