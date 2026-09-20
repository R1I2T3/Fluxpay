package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.beans.*;
import com.fluxpay.common.contracts.*;
import com.fluxpay.domain.RoutePreference;
import com.fluxpay.dto.*;
import com.fluxpay.exception.BusinessException;
import com.fluxpay.repository.*;
import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.*;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:payment-purpose;MODE=Oracle;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = PaymentPurposeFlowTest.Config.class)
@Import({PaymentService.class, PaymentOperationService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class PaymentPurposeFlowTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class Config {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    ObjectMapper mapper() {
      return new ObjectMapper().findAndRegisterModules();
    }
  }

  @Autowired PaymentService service;
  @Autowired RecipientRepository recipients;
  @MockBean WalletPort wallets;
  @MockBean KycGate kyc;

  private DraftPaymentRequest request(
      UUID wallet, UUID recipient, PaymentPurpose purpose, String reason) {
    return new DraftPaymentRequest(
        wallet, recipient, "25.00", "USD", "INR", purpose, RoutePreference.values()[0], reason);
  }

  @Test
  void customReasonIsPersistedReturnedAndIncludedInIdempotency() {
    UUID user = UUID.randomUUID(), wallet = UUID.randomUUID(), recipient = UUID.randomUUID();
    recipients.saveAndFlush(
        new Recipient(
            recipient,
            user,
            "Test Recipient",
            "TEST-ACCOUNT",
            "Test Bank",
            "IN",
            "INR",
            RecipientStatus.ACTIVE,
            Instant.now()));
    when(kyc.isVerified(user)).thenReturn(true);
    when(wallets.findOwned(user, wallet))
        .thenReturn(
            Optional.of(new WalletSnapshot(wallet, user, "USD", new BigDecimal("1000"), true)));
    var draft =
        service.draft(
            user,
            request(wallet, recipient, PaymentPurpose.OTHERS, "  Medical treatment  "),
            "purpose-test");
    assertThat(draft.purpose()).isEqualTo(PaymentPurpose.OTHERS);
    assertThat(draft.purposeReason()).isEqualTo("Medical treatment");
    assertThat(service.detail(user, draft.id()).purposeReason()).isEqualTo("Medical treatment");
    assertThat(service.list(user, 0, 10).items())
        .extracting(PaymentResponse::purposeReason)
        .contains("Medical treatment");
    assertThat(
            service
                .draft(
                    user,
                    request(wallet, recipient, PaymentPurpose.OTHERS, "Medical treatment"),
                    "purpose-test")
                .id())
        .isEqualTo(draft.id());
    assertThatThrownBy(
            () ->
                service.draft(
                    user,
                    request(wallet, recipient, PaymentPurpose.OTHERS, "Travel expenses"),
                    "purpose-test"))
        .isInstanceOf(BusinessException.class);
    var preset =
        service.draft(
            user,
            request(wallet, recipient, PaymentPurpose.FAMILY_SUPPORT, "Stale custom text"),
            "preset-test");
    assertThat(preset.purposeReason()).isNull();
  }

  @Test
  void othersRequiresReasonInBothBeanValidationAndService() {
    UUID user = UUID.randomUUID(), wallet = UUID.randomUUID(), recipient = UUID.randomUUID();
    try (var factory = Validation.buildDefaultValidatorFactory()) {
      for (String reason : Arrays.asList(null, "", "  ", "x".repeat(251))) {
        var body = request(wallet, recipient, PaymentPurpose.OTHERS, reason);
        assertThat(factory.getValidator().validate(body)).isNotEmpty();
        assertThatThrownBy(() -> service.draft(user, body, UUID.randomUUID().toString()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("reason");
      }
      assertThat(
              factory
                  .getValidator()
                  .validate(request(wallet, recipient, PaymentPurpose.OTHERS, "Medical treatment")))
          .isEmpty();
      assertThat(
              factory
                  .getValidator()
                  .validate(request(wallet, recipient, PaymentPurpose.FAMILY_SUPPORT, null)))
          .isEmpty();
    }
    verifyNoInteractions(wallets, kyc);
  }
}
