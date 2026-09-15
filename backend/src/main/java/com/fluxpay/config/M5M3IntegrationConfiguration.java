package com.fluxpay.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fluxpay.common.contracts.*;
import com.fluxpay.controller.PaymentController;
import com.fluxpay.repository.*;
import com.fluxpay.service.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.net.http.HttpClient;
import java.time.*;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** Explicit same-process M3/M5 collaboration. No local mocks, Kafka consumer, or payout executor. */
@Profile("m5-m3-integration") @Configuration(proxyBeanMethods=false) @EnableTransactionManagement
@EntityScan("com.fluxpay.beans")
@EnableJpaRepositories(basePackages="com.fluxpay.repository",includeFilters=@ComponentScan.Filter(
    type=FilterType.ASSIGNABLE_TYPE,classes={PaymentRepository.class,PaymentQuoteRepository.class,
      RecipientRepository.class,M3PaymentOperationRepository.class,OutboxEventRepository.class,
      M3OutboxDeliveryRepository.class,M3ReviewDecisionRepository.class,WalletRepository.class,
      LedgerEntryRepository.class,KycCaseRepository.class}))
@Import({PaymentService.class,QuoteService.class,
    PaymentController.class,DatabaseKycGate.class,FxQuoteService.class,LedgerJournalService.class,
    PersistentLedgerWriter.class,LedgerPostingContext.class,M3ApiExceptionHandler.class,
    M5IntegrationApiExceptionHandler.class,M5ReviewDeliveryConfiguration.class})
public class M5M3IntegrationConfiguration {
  // The base M5 launcher deliberately excludes JPA auto-configuration. Create it only in this
  // opt-in profile; importing the excluded auto-configuration early loses its DataSource condition.
  @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
    var factory=new LocalContainerEntityManagerFactoryBean();
    factory.setDataSource(source);factory.setPackagesToScan("com.fluxpay.beans");
    factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
    factory.setJpaPropertyMap(java.util.Map.of(
        "hibernate.hbm2ddl.auto", "none",
        "hibernate.jdbc.time_zone", "UTC",
        "hibernate.type.preferred_instant_jdbc_type", "TIMESTAMP"));
    return factory;
  }
  @Bean PlatformTransactionManager transactionManager(EntityManagerFactory entities,DataSource source) {
    var manager=new JpaTransactionManager(entities);manager.setDataSource(source);return manager;
  }
  @Bean Clock m5IntegrationClock() {return Clock.systemUTC();}
  @Bean M3WalletPort m5RealWalletAdapter(WalletRepository wallets,EntityManager entities,
      @Value("${m5.integration.system-user-id}") UUID systemOwner) {
    return new M3M2WalletAdapter(wallets,entities,systemOwner);
  }
  @Bean M3PostingPort m5RealPostingAdapter(M3WalletPort wallets,LedgerJournalService ledger,Clock clock) {
    return new M3M2PostingAdapter(wallets,ledger,clock);
  }
  @Bean FxSnapshotSource m5LiveFxSource(ObjectMapper json,Clock clock,
      @Value("${fluxpay.fx-provider-url}") String endpoint) {
    var uri=java.net.URI.create(endpoint);
    if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null)
      throw new IllegalArgumentException("fluxpay.fx-provider-url must be an explicit HTTPS endpoint");
    return new FrankfurterFxProvider(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build(),json,endpoint,clock);
  }
  @Bean M5ActiveReviewGuard m5ActiveReviewGuard(JdbcTemplate jdbc) {return new JdbcM5ActiveReviewGuard(jdbc);}
  @Bean M5PaymentDispositionPort m5JoiningDispositions(M5ScreeningStore store,PlatformTransactionManager manager) {
    return new M5JoiningDispositionService(store,manager);
  }
  @Bean M3PaymentCompliancePort m5PaymentCompliancePort(M5ComplianceService compliance,M5PaymentReader reader,PlatformTransactionManager manager) {
    return new M3M5ComplianceBridge(compliance,reader,manager);
  }
  @Bean M3ConfirmationWorker m5ConfirmationWorker(PaymentRepository payments,PaymentQuoteRepository quotes,
      RecipientRepository recipients,KycGate kyc,M3PostingPort posting,Clock clock,
      M3PaymentOperationRepository operations,OutboxEventRepository outbox,M3OutboxDeliveryRepository deliveries,
      ObjectMapper json,M5PaymentDispositionPort disposition,M3ReviewDecisionRepository decisions,PlatformTransactionManager manager) {
    return new M3ConfirmationWorker(payments,quotes,recipients,kyc,posting,clock,operations,outbox,deliveries,json,disposition,decisions,manager);
  }
  @Bean PaymentConfirmationService m5PaymentConfirmationService(PaymentRepository payments,
      M3PaymentOperationRepository operations,M3PaymentCompliancePort compliance,M3ConfirmationWorker worker,ObjectMapper json) {
    return new PaymentConfirmationService(payments,operations,compliance,worker,json);
  }
  @Bean M3ReviewDecisionPort m5M3ReviewReceiver(PaymentRepository payments,M3ReviewDecisionRepository decisions,
      ObjectMapper json,Clock clock,PlatformTransactionManager manager) {
    return new M3ReviewService(payments,decisions,json,clock,manager);
  }
  @Bean M5ReviewDecisionSink m5M3ReviewSink(M3ReviewDecisionPort receiver) {return new M3M5ReviewDecisionSink(receiver);}
  @Bean M5ReviewDeliveryService m5ReviewDeliveryService(M5ScreeningStore store,M5ReviewDecisionSink sink,
      PlatformTransactionManager manager,Clock clock) {return new M5ReviewDeliveryService(store,sink,manager,clock);}
  @Bean InitializingBean m5SharedTransactionPreflight(DataSource source,PlatformTransactionManager manager,JdbcTemplate jdbc) {
    return () -> {
      if(!(manager instanceof JpaTransactionManager jpa) || jpa.getDataSource()!=source || jdbc.getDataSource()!=source)
        throw new IllegalStateException("M3 and M5 must share one JPA transaction manager and the identical Oracle DataSource");
      jdbc.queryForList("SELECT review_assessment_id,review_case_id,review_payment_fingerprint,approval_decision_id FROM payments WHERE 1=0");
      jdbc.queryForList("SELECT normalized_command,receipt_consumed_at FROM m3_review_decisions WHERE 1=0");
    };
  }
}
