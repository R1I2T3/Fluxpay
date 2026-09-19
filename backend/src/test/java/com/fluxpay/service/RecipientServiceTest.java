package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.Recipient;
import com.fluxpay.beans.RecipientStatus;
import com.fluxpay.dto.RecipientRequest;
import com.fluxpay.repository.RecipientRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RecipientServiceTest {
  private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
  private static final UUID RECIPIENT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
  private static final Instant NOW = Instant.parse("2026-09-18T00:00:00Z");

  @Mock private RecipientRepository recipients;

  private RecipientService service;

  @BeforeEach
  void setUp() {
    service = new RecipientService(recipients, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void createIgnoresClientBlockedStatusAndReturnsActiveRecipient() {
    when(recipients.saveAndFlush(any(Recipient.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    var result = service.create(USER_ID, request(RecipientStatus.BLOCKED, null));

    assertThat(result.status()).isEqualTo(RecipientStatus.ACTIVE);
  }

  @Test
  void updateIgnoresClientBlockedStatusAndReactivatesRecipient() {
    Recipient recipient =
        new Recipient(
            RECIPIENT_ID,
            USER_ID,
            "Old Name",
            "account",
            "Old Bank",
            "IN",
            "INR",
            RecipientStatus.BLOCKED,
            NOW.minusSeconds(60));
    when(recipients.lockOwned(RECIPIENT_ID, USER_ID)).thenReturn(Optional.of(recipient));

    var result = service.update(USER_ID, RECIPIENT_ID, request(RecipientStatus.BLOCKED, 0L));

    assertThat(result.status()).isEqualTo(RecipientStatus.ACTIVE);
    assertThat(recipient.eligible()).isTrue();
  }

  private RecipientRequest request(RecipientStatus status, Long expectedVersion) {
    return new RecipientRequest(
        "Recipient", "account", "Bank", "IN", "INR", status, expectedVersion);
  }
}
