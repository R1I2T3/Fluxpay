package com.fluxpay;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.adapter.fx.FrankfurterFxProvider;
import com.fluxpay.common.contracts.ComplianceAssessor;
import com.fluxpay.common.contracts.FxSnapshotSource;
import com.fluxpay.common.contracts.TransferRail;
import com.fluxpay.common.security.JwtAuthFilter;
import com.fluxpay.messaging.OutboxDispatchJob;
import com.fluxpay.messaging.PaymentEventConsumer;
import com.fluxpay.repository.OutboxDeliveryRepository;
import com.fluxpay.repository.PaymentOperationRepository;
import com.fluxpay.repository.PaymentRepository;
import com.fluxpay.service.PaymentOperationService;
import com.fluxpay.service.PaymentRiskComplianceAssessor;
import com.fluxpay.service.WalletOperationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@TestPropertySource(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:production-boot;MODE=Oracle;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "spring.flyway.enabled=false",
      "spring.kafka.bootstrap-servers=localhost:65535",
      "spring.kafka.listener.auto-startup=false",
      "spring.kafka.admin.fail-fast=false",
      "fluxpay.fx-provider-url=https://api.frankfurter.dev/v1/latest",
      "fluxpay.jwt-secret=production-boot-test-secret-is-at-least-32-bytes",
      "fluxpay.outbox.dispatch-initial-delay-ms=600000"
    })
class ProductionBootTest {

  @Autowired private ApplicationContext context;

  @Test
  void defaultContextWiresRealInternalBoundariesAndHonestExternalDefaults() {
    assertThat(context.getBeansOfType(PaymentRepository.class)).hasSize(1);
    assertThat(context.getBeansOfType(PaymentOperationRepository.class)).hasSize(1);
    assertThat(context.getBeansOfType(OutboxDeliveryRepository.class)).hasSize(1);
    assertThat(context.getBeansOfType(JwtAuthFilter.class)).hasSize(1);
    assertThat(context.getBeansOfType(PaymentOperationService.class)).hasSize(1);
    assertThat(context.getBeansOfType(WalletOperationService.class)).hasSize(1);
    assertThat(context.getBeansOfType(OutboxDispatchJob.class)).hasSize(1);
    assertThat(context.getBeansOfType(PaymentEventConsumer.class)).hasSize(1);

    assertThat(context.getBean(FxSnapshotSource.class)).isInstanceOf(FrankfurterFxProvider.class);
    assertThat(context.getBean(ComplianceAssessor.class))
        .isInstanceOf(PaymentRiskComplianceAssessor.class);
    assertThat(context.getBeansOfType(TransferRail.class)).hasSize(1);
    assertThat(context.getBean(TransferRail.class))
        .isInstanceOf(com.fluxpay.adapter.transfer.InternalLedgerTransferRail.class);
  }
}
