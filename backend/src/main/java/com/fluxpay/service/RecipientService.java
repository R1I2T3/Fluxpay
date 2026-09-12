package com.fluxpay.service;

import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.config.M3BusinessException;
import com.fluxpay.dto.RecipientRequest;
import com.fluxpay.dto.RecipientResponse;
import com.fluxpay.repository.RecipientRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecipientService {
  private final RecipientRepository recipients;
  private final Clock clock;

  public RecipientService(RecipientRepository recipients, Clock clock) {
    this.recipients = recipients;
    this.clock = clock;
  }

  @Transactional
  public RecipientResponse create(UUID userId, RecipientRequest request) {
    String account = request.account().trim();
    String country = request.country().toUpperCase();
    String currency = request.currency().toUpperCase();
    if (recipients.existsByUserIdAndAccountAndCountry(userId, account, country)) {
      throw conflict(
          "DUPLICATE_RECIPIENT", "A recipient with this account and country already exists.");
    }
    Recipient r =
        new Recipient(
            UUID.randomUUID(),
            userId,
            request.name().trim(),
            account,
            request.bankName().trim(),
            country,
            currency,
            request.status() == null ? RecipientStatus.ACTIVE : request.status(),
            Instant.now(clock));
    try {
      return response(recipients.saveAndFlush(r));
    } catch (DataIntegrityViolationException e) {
      throw conflict(
          "DUPLICATE_RECIPIENT", "A recipient with this account and country already exists.");
    }
  }

  @Transactional(readOnly = true)
  public List<RecipientResponse> list(UUID userId) {
    return recipients.findByUserIdOrderByNameAsc(userId).stream().map(this::response).toList();
  }

  @Transactional
  public RecipientResponse update(UUID userId, UUID id, RecipientRequest request) {
    Recipient r =
        recipients
            .lockOwned(id, userId)
            .orElseThrow(() -> notFound("RECIPIENT_NOT_FOUND", "Recipient not found."));
    if (request.expectedVersion() == null || !request.expectedVersion().equals(r.version())) {
      throw conflict(
          "RECIPIENT_VERSION_CONFLICT", "The recipient was changed. Refresh and try again.");
    }
    String account = request.account().trim();
    String country = request.country().toUpperCase();
    String currency = request.currency().toUpperCase();
    if ((!r.account().equals(account) || !r.country().equals(country))
        && recipients.existsByUserIdAndAccountAndCountry(userId, account, country)) {
      throw conflict(
          "DUPLICATE_RECIPIENT", "A recipient with this account and country already exists.");
    }
    r.update(
        request.name().trim(),
        account,
        request.bankName().trim(),
        country,
        currency,
        request.status() == null ? r.status() : request.status(),
        Instant.now(clock));
    try {
      recipients.flush();
    } catch (DataIntegrityViolationException e) {
      throw conflict(
          "DUPLICATE_RECIPIENT", "A recipient with this account and country already exists.");
    }
    return response(r);
  }

  private RecipientResponse response(Recipient r) {
    return new RecipientResponse(
        r.id(),
        r.name(),
        r.account(),
        r.bankName(),
        r.country(),
        r.currency(),
        r.status(),
        r.version());
  }

  private M3BusinessException conflict(String c, String m) {
    return new M3BusinessException(HttpStatus.CONFLICT, c, m);
  }

  private M3BusinessException notFound(String c, String m) {
    return new M3BusinessException(HttpStatus.NOT_FOUND, c, m);
  }
}
