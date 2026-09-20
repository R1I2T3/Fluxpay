package com.fluxpay.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fluxpay.beans.*;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.exception.KycException;
import com.fluxpay.repository.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.support.*;

class KycUploadServiceTest {
  @TempDir Path directory;
  KycCaseRepository cases;
  KycDocumentRepository documents;
  UserRepository users;
  KycDocumentStorage storage;
  KycUploadService service;
  KycService kyc;
  UUID owner = UUID.randomUUID();
  Instant now = Instant.parse("2026-09-20T12:00:00Z");

  @BeforeEach
  void setup() {
    cases = mock(KycCaseRepository.class);
    documents = mock(KycDocumentRepository.class);
    users = mock(UserRepository.class);
    kyc = mock(KycService.class);
    storage = new KycDocumentStorage(directory.toString());
    service =
        new KycUploadService(
            cases, documents, users, storage, kyc, Clock.fixed(now, ZoneOffset.UTC));
    when(users.findByIdForUpdate(owner))
        .thenReturn(
            Optional.of(
                new User(
                    owner, "kyc@example.test", "hash", "CUSTOMER", "Test Customer", now, now)));
    when(cases.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
    when(kyc.getMyStatus(owner))
        .thenReturn(
            new KycStatusResponse(UUID.randomUUID(), 0L, KycStatus.PENDING, null, now, null));
    TransactionSynchronizationManager.initSynchronization();
  }

  @AfterEach
  void clear() {
    TransactionSynchronizationManager.clearSynchronization();
  }

  MockMultipartFile pdf() {
    return new MockMultipartFile(
        "files",
        "identity.pdf",
        "application/pdf",
        "%PDF-1.4\n1 0 obj <</Type /Catalog>> endobj\n%%EOF".getBytes());
  }

  KycCase application(KycStatus status) {
    return new KycCase(
        UUID.randomUUID(), owner, status, KycDocumentType.PASSPORT, "TEST123", now, now);
  }

  @Test
  void uploadStoresActualBytesAndPendingMetadata() throws Exception {
    var response = service.submit(owner, KycDocumentType.PASSPORT, "TEST123", List.of(pdf()));
    assertThat(response.status()).isEqualTo(KycStatus.PENDING);
    var captor = org.mockito.ArgumentCaptor.forClass(List.class);
    verify(documents).saveAllAndFlush(captor.capture());
    KycDocument document = (KycDocument) captor.getValue().get(0);
    assertThat(storage.read(document.getStorageUrl())).isEqualTo(pdf().getBytes());
    assertThat(document.getFileName()).isEqualTo("identity.pdf");
    assertThat(document.getStorageUrl()).doesNotContain("identity.pdf");
    verify(users).findByIdForUpdate(owner);
  }

  @Test
  void pendingAndApprovedCannotOverwriteFiles() throws Exception {
    for (var status : List.of(KycStatus.PENDING, KycStatus.VERIFIED)) {
      when(cases.findByUserIdForUpdate(owner)).thenReturn(Optional.of(application(status)));
      assertThatThrownBy(
              () -> service.submit(owner, KycDocumentType.PASSPORT, "TEST123", List.of(pdf())))
          .isInstanceOf(KycException.class)
          .hasMessageContaining("read-only");
    }
    verify(documents, never()).saveAllAndFlush(any());
    try (var paths = Files.list(directory)) {
      assertThat(paths.count()).isZero();
    }
  }

  @Test
  void rejectedResubmissionRemovesPreviousFileOnlyAfterCommit() throws Exception {
    var rejected = application(KycStatus.REJECTED);
    var oldKey = storage.save(UUID.randomUUID(), pdf().getBytes());
    when(cases.findByUserIdForUpdate(owner)).thenReturn(Optional.of(rejected));
    when(documents.findAllByKycCaseIdOrderByUploadedAtAsc(rejected.getId()))
        .thenReturn(
            List.of(
                new KycDocument(
                    UUID.randomUUID(), rejected, "old.pdf", "application/pdf", 100, oldKey, now)));
    service.submit(owner, KycDocumentType.PASSPORT, "UPDATED", List.of(pdf()));
    assertThat(rejected.getStatus()).isEqualTo(KycStatus.PENDING);
    assertThat(rejected.getDocNumber()).isEqualTo("UPDATED");
    assertThat(storage.read(oldKey)).isNotEmpty();
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
    assertThatThrownBy(() -> storage.read(oldKey)).isInstanceOf(KycException.class);
  }

  @Test
  void databaseRollbackCleansNewFiles() throws Exception {
    service.submit(owner, KycDocumentType.PASSPORT, "TEST123", List.of(pdf()));
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
    try (var paths = Files.list(directory)) {
      assertThat(paths.count()).isZero();
    }
  }

  @Test
  void ownerAndAdminCanReadButAnotherCustomerCannot() throws Exception {
    var application = application(KycStatus.VERIFIED);
    UUID id = UUID.randomUUID();
    String key = storage.save(id, pdf().getBytes());
    when(documents.findById(id))
        .thenReturn(
            Optional.of(
                new KycDocument(
                    id, application, "identity.pdf", "application/pdf", 100, key, now)));
    assertThat(service.read(new CurrentUser(owner, "owner@example.test", "CUSTOMER"), id).bytes())
        .isEqualTo(pdf().getBytes());
    assertThat(
            service
                .read(new CurrentUser(UUID.randomUUID(), "admin@example.test", "ADMIN"), id)
                .bytes())
        .isEqualTo(pdf().getBytes());
    assertThatThrownBy(
            () ->
                service.read(
                    new CurrentUser(UUID.randomUUID(), "other@example.test", "CUSTOMER"), id))
        .isInstanceOf(KycException.class)
        .hasMessage("Document not found.");
  }

  @Test
  void oldMetadataIsNeverTreatedAsAStoredDocument() {
    assertThatThrownBy(() -> storage.read(KycDocument.NOT_STORED_METADATA_ONLY))
        .isInstanceOf(KycException.class);
  }

  @Test
  void validatesContentSizeAndTraversalNames() {
    assertThatThrownBy(
            () ->
                storage.validate(
                    new MockMultipartFile(
                        "files", "fake.png", "image/png", "<script>bad</script>".getBytes())))
        .isInstanceOf(KycException.class);
    assertThatThrownBy(
            () ->
                storage.validate(
                    new MockMultipartFile(
                        "files", "../secret.pdf", "application/pdf", pdf().getBytes())))
        .isInstanceOf(KycException.class);
    assertThatThrownBy(
            () ->
                storage.validate(
                    new MockMultipartFile("files", "empty.pdf", "application/pdf", new byte[0])))
        .isInstanceOf(KycException.class);
    assertThatThrownBy(
            () ->
                storage.validate(
                    new MockMultipartFile(
                        "files", "large.pdf", "application/pdf", new byte[5 * 1024 * 1024 + 1])))
        .isInstanceOf(KycException.class);
    assertThatThrownBy(() -> storage.read("local:../../secret")).isInstanceOf(KycException.class);
  }

  @Test
  void tooManyFilesRejectedBeforeStorage() {
    assertThatThrownBy(
            () ->
                service.submit(
                    owner, KycDocumentType.PAN, "TEST123", Collections.nCopies(5, pdf())))
        .isInstanceOf(KycException.class);
    verify(documents, never()).saveAllAndFlush(any());
  }
}
