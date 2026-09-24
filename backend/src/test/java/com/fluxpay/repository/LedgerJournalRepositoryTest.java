package com.fluxpay.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fluxpay.beans.LedgerJournal;
import com.fluxpay.beans.LedgerTransactionCategory;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:journal-order;MODE=Oracle;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = LedgerJournalRepositoryTest.JpaConfiguration.class)
class LedgerJournalRepositoryTest {
  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories(basePackageClasses = LedgerJournalRepository.class)
  static class JpaConfiguration {}

  @Autowired LedgerJournalRepository journals;

  @Test
  void returnsRequestedJournalsInJournalReferenceOrder() {
    journals.saveAndFlush(
        new LedgerJournal(
            "JRN-C", LedgerTransactionCategory.LEGACY, "c".repeat(64), Instant.EPOCH));
    journals.saveAndFlush(
        new LedgerJournal(
            "JRN-A", LedgerTransactionCategory.LEGACY, "a".repeat(64), Instant.EPOCH));
    journals.saveAndFlush(
        new LedgerJournal(
            "JRN-B", LedgerTransactionCategory.LEGACY, "b".repeat(64), Instant.EPOCH));

    assertThat(
            journals.findByJournalReferenceInOrderByJournalReferenceAsc(
                List.of("JRN-C", "JRN-A", "JRN-B")))
        .extracting(LedgerJournal::getJournalReference)
        .containsExactly("JRN-A", "JRN-B", "JRN-C");
  }
}
