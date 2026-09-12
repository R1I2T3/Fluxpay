package com.fluxpay.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.WalletOperation;
import com.fluxpay.config.M2DemoFundingConfig;
import com.fluxpay.dto.WalletReceiveRequest;
import com.fluxpay.dto.WalletResponse;
import com.fluxpay.repository.WalletOperationRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DemoFundingServiceTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID SYSTEM_USER_ID =
      UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final String KEY = "demo-funding-1";
  private static final String NORMALIZED = "{\"currency\":\"USD\",\"amount\":\"500.0000\"}";
  private static final String SNAPSHOT =
      "{\"walletId\":\"33333333-3333-3333-3333-333333333333\","
          + "\"currency\":\"USD\",\"balance\":\"500.0000\","
          + "\"heldBalance\":\"0.0000\",\"availableBalance\":\"500.0000\","
          + "\"journalReference\":\"M2-DEMO-44444444-4444-4444-4444-444444444444\"}";

  private WalletOperationRepository operations;
  private WalletPostingService posting;
  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    operations = mock(WalletOperationRepository.class);
    posting = mock(WalletPostingService.class);
    objectMapper = new ObjectMapper();
  }

  @Test
  void disabledFundingIsHiddenBeforeRequestValidation() {
    DemoFundingService service = service(false);

    assertThrows(
        DemoFundingDisabledException.class,
        () -> service.receiveDemo(USER_ID, new WalletReceiveRequest(null, null), null));
  }

  @Test
  void lowercaseCurrencyAndShortScaleReplayTheCanonicalCompletedRequest() {
    WalletOperation completed = completedOperation(NORMALIZED, SNAPSHOT);
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "RECEIVE_DEMO", KEY))
        .thenReturn(Optional.of(completed));

    WalletResponse result =
        service(true).receiveDemo(USER_ID, new WalletReceiveRequest(" usd ", "500"), KEY);

    assertEquals("33333333-3333-3333-3333-333333333333", result.walletId());
    assertEquals("USD", result.currency());
    assertEquals("500.0000", result.balance());
    assertEquals("500.0000", result.availableBalance());
    assertEquals("M2-DEMO-44444444-4444-4444-4444-444444444444", result.journalReference());
  }

  @Test
  void changedRequestCannotReuseACompletedOperationKey() {
    WalletOperation completed = completedOperation(NORMALIZED, SNAPSHOT);
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "RECEIVE_DEMO", KEY))
        .thenReturn(Optional.of(completed));

    assertThrows(
        LedgerIdempotencyConflictException.class,
        () -> service(true).receiveDemo(USER_ID, new WalletReceiveRequest("USD", "501.0000"), KEY));
  }

  @Test
  void newRequestReturnsThePostingResult() {
    WalletResponse expected = response("500.0000");
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "RECEIVE_DEMO", KEY))
        .thenReturn(Optional.empty());
    when(posting.receiveDemo(USER_ID, "USD", new BigDecimal("500.0000"), NORMALIZED, KEY))
        .thenReturn(expected);

    WalletResponse result =
        service(true).receiveDemo(USER_ID, new WalletReceiveRequest("USD", "500.0000"), KEY);

    assertEquals(expected, result);
  }

  @Test
  void anOperationRaceRetriesTheWholePostingOnce() {
    WalletResponse expected = response("500.0000");
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "RECEIVE_DEMO", KEY))
        .thenReturn(Optional.empty(), Optional.empty());
    when(posting.receiveDemo(USER_ID, "USD", new BigDecimal("500.0000"), NORMALIZED, KEY))
        .thenThrow(new OperationRaceException())
        .thenReturn(expected);

    WalletResponse result =
        service(true).receiveDemo(USER_ID, new WalletReceiveRequest("USD", "500.0000"), KEY);

    assertEquals(expected, result);
  }

  @Test
  void aSecondUnresolvedRaceReturnsRetryConflict() {
    when(operations.findByUserIdAndOperationTypeAndClientKey(USER_ID, "RECEIVE_DEMO", KEY))
        .thenReturn(Optional.empty(), Optional.empty());
    when(posting.receiveDemo(USER_ID, "USD", new BigDecimal("500.0000"), NORMALIZED, KEY))
        .thenThrow(new OperationRaceException());

    assertThrows(
        DemoFundingRetryException.class,
        () -> service(true).receiveDemo(USER_ID, new WalletReceiveRequest("USD", "500.0000"), KEY));
  }

  @Test
  void rejectsInvalidInputsWithoutRoundingThem() {
    DemoFundingService service = service(true);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.receiveDemo(null, new WalletReceiveRequest("USD", "1.0000"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.receiveDemo(USER_ID, new WalletReceiveRequest("GBP", "1.0000"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.receiveDemo(USER_ID, new WalletReceiveRequest("USD", "0.0000"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.receiveDemo(USER_ID, new WalletReceiveRequest("USD", "1.00000"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.receiveDemo(USER_ID, new WalletReceiveRequest("USD", "not-money"), KEY));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.receiveDemo(USER_ID, new WalletReceiveRequest("USD", "1.0000"), " "));
  }

  private DemoFundingService service(boolean enabled) {
    return new DemoFundingService(
        new M2DemoFundingConfig(enabled, SYSTEM_USER_ID.toString()),
        operations,
        posting,
        objectMapper);
  }

  private static WalletOperation completedOperation(String normalized, String snapshot) {
    WalletOperation operation =
        new WalletOperation(USER_ID, "RECEIVE_DEMO", KEY, normalized, "M2-DEMO-test");
    operation.complete(snapshot);
    return operation;
  }

  private static WalletResponse response(String balance) {
    return new WalletResponse(
        "33333333-3333-3333-3333-333333333333",
        "USD",
        balance,
        "0.0000",
        balance,
        "M2-DEMO-44444444-4444-4444-4444-444444444444");
  }
}
