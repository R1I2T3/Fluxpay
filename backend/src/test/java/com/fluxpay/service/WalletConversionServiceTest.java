package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.dto.FxSnapshot;
import com.fluxpay.dto.WalletConvertRequest;
import com.fluxpay.dto.WalletConvertResponse;
import com.fluxpay.repository.WalletOperationRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WalletConversionServiceTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final String KEY = "convert-1";
  private static final String NORMALIZED =
      "{\"from\":\"USD\",\"to\":\"INR\",\"amount\":\"100.0000\"}";
  private static final Instant FETCHED_AT = Instant.parse("2026-09-11T01:02:03Z");
  private static final FxSnapshot FX =
      new FxSnapshot("USD", "INR", new BigDecimal("83.50"), FETCHED_AT, false, true);

  private WalletOperationRepository operations;
  private WalletPostingService posting;
  private FxQuoteService quotes;
  private WalletConversionService service;

  @BeforeEach
  void setUp() {
    operations = mock(WalletOperationRepository.class);
    posting = mock(WalletPostingService.class);
    quotes = mock(FxQuoteService.class);
    service = new WalletConversionService(operations, posting, quotes, new ObjectMapper());
  }

  @Test
  void calculatesTheLiteralFeeNetAndCreditFromOneSnapshot() {
    WalletConvertResponse expected = response();
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", KEY))
        .thenReturn(Optional.empty());
    when(quotes.snapshot("USD", "INR")).thenReturn(FX);
    when(posting.convert(
            eq(USER_ID),
            eq("USD"),
            eq("INR"),
            eq(new BigDecimal("100.0000")),
            eq(new BigDecimal("0.5000")),
            eq(new BigDecimal("99.5000")),
            eq(new BigDecimal("8308.2500")),
            eq(FX),
            eq(NORMALIZED),
            eq(KEY)))
        .thenReturn(expected);

    WalletConvertResponse actual =
        service.convert(USER_ID, new WalletConvertRequest(" usd ", "inr", "100"), KEY);

    assertEquals(expected, actual);
    verify(quotes).snapshot("USD", "INR");
  }

  @Test
  void completedReplayReturnsTheStoredSnapshotWithoutCallingFx() throws Exception {
    WalletConvertResponse expected = response();
    WalletOperation completed =
        new WalletOperation(USER_ID, "CONVERT", KEY, NORMALIZED, expected.journalReference());
    completed.complete(new ObjectMapper().writeValueAsString(expected));
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", KEY))
        .thenReturn(Optional.of(completed));

    WalletConvertResponse replay =
        service.convert(USER_ID, new WalletConvertRequest("USD", "INR", "100.0000"), KEY);

    assertEquals(expected, replay);
    verifyNoInteractions(quotes, posting);
  }

  @Test
  void changedPayloadCannotReuseACompletedKey() {
    WalletOperation completed =
        new WalletOperation(USER_ID, "CONVERT", KEY, NORMALIZED, "M2-FX-completed");
    completed.complete("{}");
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", KEY))
        .thenReturn(Optional.of(completed));

    assertThrows(
        LedgerIdempotencyConflictException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("USD", "EUR", "100"), KEY));
    verifyNoInteractions(quotes, posting);
  }

  @Test
  void invalidRequestsAreRejectedBeforeFx() {
    assertThrows(
        IllegalArgumentException.class,
        () -> service.convert(null, new WalletConvertRequest("USD", "INR", "1"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("USD", "USD", "1"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("GBP", "INR", "1"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("USD", "INR", "1.00000"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("USD", "INR", "0"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("USD", "INR", "1"), " "));
    verifyNoInteractions(quotes, posting);
  }

  @Test
  void fxFailureLeavesPostingUntouched() {
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", KEY))
        .thenReturn(Optional.empty());
    when(quotes.snapshot("USD", "INR")).thenThrow(new FxUnavailableException());

    assertThrows(
        FxUnavailableException.class,
        () -> service.convert(USER_ID, new WalletConvertRequest("USD", "INR", "100"), KEY));
    verifyNoInteractions(posting);
  }

  @Test
  void operationRaceRetriesPostingWithTheSameSnapshot() {
    WalletConvertResponse expected = response();
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "CONVERT", KEY))
        .thenReturn(Optional.empty(), Optional.empty());
    when(quotes.snapshot("USD", "INR")).thenReturn(FX);
    when(posting.convert(
            eq(USER_ID),
            eq("USD"),
            eq("INR"),
            eq(new BigDecimal("100.0000")),
            eq(new BigDecimal("0.5000")),
            eq(new BigDecimal("99.5000")),
            eq(new BigDecimal("8308.2500")),
            eq(FX),
            eq(NORMALIZED),
            eq(KEY)))
        .thenThrow(new OperationRaceException())
        .thenReturn(expected);

    assertEquals(
        expected, service.convert(USER_ID, new WalletConvertRequest("USD", "INR", "100"), KEY));
    verify(quotes).snapshot("USD", "INR");
  }

  private static WalletConvertResponse response() {
    return new WalletConvertResponse(
        "22222222-2222-2222-2222-222222222222",
        "33333333-3333-3333-3333-333333333333",
        "USD",
        "INR",
        "100.0000",
        "0.5000",
        "99.5000",
        "8308.2500",
        "83.50",
        FETCHED_AT.toString(),
        false,
        true,
        "M2-FX-44444444-4444-4444-4444-444444444444");
  }
}
