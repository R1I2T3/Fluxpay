package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.KycReviewRequest;
import com.fluxpay.exception.KycException;
import com.fluxpay.repository.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.*;

@DataJpaTest(
    showSql = false,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:kyc-documents;MODE=Oracle;DB_CLOSE_DELAY=-1",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop"
    })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = KycDocumentFlowTest.Config.class)
@Import({KycService.class, KycUploadService.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KycDocumentFlowTest {
  @TempDir static Path directory;

  @Configuration(proxyBeanMethods = false)
  @EntityScan("com.fluxpay.beans")
  @EnableJpaRepositories("com.fluxpay.repository")
  static class Config {
    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    KycDocumentStorage storage() {
      return new KycDocumentStorage(directory.toString());
    }
  }

  @Autowired KycUploadService uploads;
  @Autowired KycService kyc;
  @Autowired UserRepository users;

  @Test
  void completeReviewLifecyclePreservesFilesAndLocksDecisions() throws Exception {
    UUID owner = UUID.randomUUID(), admin = UUID.randomUUID();
    Instant now = Instant.now();
    users.saveAndFlush(
        new User(owner, "kyc-owner@example.test", "hash", "CUSTOMER", "Synthetic Owner", now, now));
    users.saveAndFlush(
        new User(
            admin, "kyc-reviewer@example.test", "hash", "ADMIN", "Synthetic Reviewer", now, now));
    var document =
        new MockMultipartFile(
            "files",
            "identity.pdf",
            "application/pdf",
            "%PDF-1.4\n1 0 obj <</Type /Catalog>> endobj\n%%EOF".getBytes());
    var first = uploads.submit(owner, KycDocumentType.PASSPORT, "QA-ONLY", List.of(document));
    assertThat(first.status()).isEqualTo(KycStatus.PENDING);
    assertThat(first.documents()).hasSize(1);
    assertThat(first.documents().get(0).available()).isTrue();
    UUID firstId = first.documents().get(0).id();
    assertThat(uploads.read(new CurrentUser(owner, "", "CUSTOMER"), firstId).bytes())
        .isEqualTo(document.getBytes());
    assertThat(uploads.read(new CurrentUser(admin, "", "ADMIN"), firstId).bytes())
        .isEqualTo(document.getBytes());
    assertThatThrownBy(
            () -> uploads.submit(owner, KycDocumentType.PASSPORT, "NEW", List.of(document)))
        .isInstanceOf(KycException.class);
    var rejected =
        kyc.reject(
            admin,
            first.applicationId(),
            new KycReviewRequest(first.version(), "Please include every corner."));
    assertThat(rejected.status()).isEqualTo(KycStatus.REJECTED);
    assertThat(rejected.rejectReason()).isEqualTo("Please include every corner.");
    var second = uploads.submit(owner, KycDocumentType.PASSPORT, "QA-CORRECTED", List.of(document));
    assertThat(second.status()).isEqualTo(KycStatus.PENDING);
    assertThat(second.rejectReason()).isNull();
    UUID secondId = second.documents().get(0).id();
    assertThat(secondId).isNotEqualTo(firstId);
    assertThatThrownBy(() -> uploads.read(new CurrentUser(owner, "", "CUSTOMER"), firstId))
        .isInstanceOf(KycException.class);
    assertThatThrownBy(
            () ->
                kyc.approve(
                    admin, second.applicationId(), new KycReviewRequest(first.version(), null)))
        .isInstanceOf(KycException.class);
    var approved =
        kyc.approve(admin, second.applicationId(), new KycReviewRequest(second.version(), null));
    assertThat(approved.status()).isEqualTo(KycStatus.VERIFIED);
    assertThat(approved.decidedAt()).isNotNull();
    assertThat(uploads.read(new CurrentUser(owner, "", "CUSTOMER"), secondId).bytes())
        .isEqualTo(document.getBytes());
    assertThatThrownBy(
            () -> uploads.submit(owner, KycDocumentType.PASSPORT, "NEW", List.of(document)))
        .isInstanceOf(KycException.class);
    assertThatThrownBy(
            () ->
                kyc.reject(
                    admin,
                    approved.applicationId(),
                    new KycReviewRequest(approved.version(), "Changed mind")))
        .isInstanceOf(KycException.class);
  }
}
