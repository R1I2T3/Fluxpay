package com.fluxpay.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.KycDocument;
import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.beans.User;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.dto.KycFileMeta;
import com.fluxpay.dto.KycReviewRequest;
import com.fluxpay.dto.KycSubmitRequest;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.KycDocumentRepository;
import com.fluxpay.repository.UserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class KycServiceTest {
  @Mock private KycCaseRepository kycCases;
  @Mock private KycDocumentRepository kycDocuments;
  @Mock private UserRepository users;

  private KycService kycService;

  @BeforeEach
  void setUp() {
    kycService = new KycService(kycCases, kycDocuments, users);
  }

  @Test
  void getMyStatusReturnsNoneWhenNoCaseExists() {
    UUID userId = UUID.randomUUID();
    when(kycCases.findByUserId(userId)).thenReturn(Optional.empty());

    var response = kycService.getMyStatus(userId);

    assertThat(response.status()).isEqualTo(KycStatus.NONE);
    assertThat(response.applicationId()).isNull();
    assertThat(response.version()).isNull();
  }

  @Test
  void firstSubmissionCreatesPendingCaseAndStoresDocumentMetadata() {
    UUID userId = UUID.randomUUID();
    when(kycCases.findByUserIdForUpdate(userId)).thenReturn(Optional.empty());
    when(kycCases.saveAndFlush(any(KycCase.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(any(UUID.class)))
        .thenReturn(List.of());

    var response = kycService.submit(userId, request());

    ArgumentCaptor<KycCase> caseCaptor = ArgumentCaptor.forClass(KycCase.class);
    verify(kycCases).saveAndFlush(caseCaptor.capture());
    KycCase savedCase = caseCaptor.getValue();
    assertThat(savedCase.getUserId()).isEqualTo(userId);
    assertThat(savedCase.getStatus()).isEqualTo(KycStatus.PENDING);
    assertThat(savedCase.getDocNumber()).isEqualTo("ABCDE1234F");
    assertThat(response.status()).isEqualTo(KycStatus.PENDING);

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<KycDocument>> documentsCaptor = ArgumentCaptor.forClass(List.class);
    verify(kycDocuments).saveAll(documentsCaptor.capture());
    KycDocument document = documentsCaptor.getValue().get(0);
    assertThat(document.getKycCase()).isSameAs(savedCase);
    assertThat(document.getFileName()).isEqualTo("pan-card.pdf");
    assertThat(document.getFileType()).isEqualTo("application/pdf");
    assertThat(document.getFileSize()).isEqualTo(1024);
    assertThat(document.getStorageUrl()).startsWith("mock://kyc/" + savedCase.getId() + "/");
  }

  @Test
  void pendingResubmissionIsRejected() {
    UUID userId = UUID.randomUUID();
    when(kycCases.findByUserIdForUpdate(userId))
        .thenReturn(Optional.of(kycCase(userId, KycStatus.PENDING)));

    assertKycCode(() -> kycService.submit(userId, request()), M1KycException.KYC_ALREADY_PENDING);
  }

  @Test
  void verifiedResubmissionIsRejected() {
    UUID userId = UUID.randomUUID();
    when(kycCases.findByUserIdForUpdate(userId))
        .thenReturn(Optional.of(kycCase(userId, KycStatus.VERIFIED)));

    assertKycCode(() -> kycService.submit(userId, request()), M1KycException.KYC_ALREADY_VERIFIED);
  }

  @Test
  void rejectedResubmissionResetsDecisionFieldsAndReturnsToPending() {
    UUID userId = UUID.randomUUID();
    User reviewer = user();
    KycCase rejectedCase = kycCase(userId, KycStatus.REJECTED);
    rejectedCase.reject(reviewer, Instant.now(), "Old rejection reason");
    when(kycCases.findByUserIdForUpdate(userId)).thenReturn(Optional.of(rejectedCase));
    when(kycCases.saveAndFlush(rejectedCase)).thenReturn(rejectedCase);
    when(kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(rejectedCase.getId()))
        .thenReturn(List.of());

    var response = kycService.submit(userId, request());

    assertThat(response.status()).isEqualTo(KycStatus.PENDING);
    assertThat(rejectedCase.getRejectReason()).isNull();
    assertThat(rejectedCase.getDecidedAt()).isNull();
    assertThat(rejectedCase.getDecidedBy()).isNull();
  }

  @Test
  void rejectRequiresNonblankReason() {
    assertKycCode(
        () -> kycService.reject(UUID.randomUUID(), UUID.randomUUID(), new KycReviewRequest(0L, "  ")),
        M1KycException.REJECT_REASON_REQUIRED);

    verify(kycCases, never()).findByIdForUpdate(any());
  }

  @Test
  void reviewRejectsStaleExpectedVersion() {
    UUID applicationId = UUID.randomUUID();
    KycCase pendingCase = kycCase(UUID.randomUUID(), KycStatus.PENDING);
    when(kycCases.findByIdForUpdate(applicationId)).thenReturn(Optional.of(pendingCase));

    assertKycCode(
        () -> kycService.approve(UUID.randomUUID(), applicationId, new KycReviewRequest(1L, null)),
        M1KycException.KYC_CONFLICT);
  }

  @Test
  void approveChangesPendingCaseToVerified() {
    UUID reviewerId = UUID.randomUUID();
    UUID applicationId = UUID.randomUUID();
    KycCase pendingCase = kycCase(UUID.randomUUID(), KycStatus.PENDING);
    User reviewer = user(reviewerId);
    when(kycCases.findByIdForUpdate(applicationId)).thenReturn(Optional.of(pendingCase));
    when(users.findById(reviewerId)).thenReturn(Optional.of(reviewer));
    when(kycCases.saveAndFlush(pendingCase)).thenReturn(pendingCase);

    var response = kycService.approve(reviewerId, applicationId, new KycReviewRequest(0L, null));

    assertThat(response.status()).isEqualTo(KycStatus.VERIFIED);
    assertThat(pendingCase.getDecidedBy()).isSameAs(reviewer);
    assertThat(pendingCase.getDecidedAt()).isNotNull();
  }

  private KycSubmitRequest request() {
    return new KycSubmitRequest(
        KycDocumentType.PAN,
        "  ABCDE1234F  ",
        List.of(new KycFileMeta("  pan-card.pdf  ", "application/pdf", 1024)));
  }

  private KycCase kycCase(UUID userId, KycStatus status) {
    Instant now = Instant.now();
    return new KycCase(
        UUID.randomUUID(), userId, status, KycDocumentType.PAN, "ABCDE1234F", now, now);
  }

  private User user() {
    return user(UUID.randomUUID());
  }

  private User user(UUID id) {
    Instant now = Instant.now();
    return new User(id, "reviewer@fluxpay.test", "bcrypt", "ADMIN", "Reviewer", now, now);
  }

  private void assertKycCode(Runnable action, String expectedCode) {
    assertThatThrownBy(action::run)
        .isInstanceOf(M1KycException.class)
        .extracting(exception -> ((M1KycException) exception).getCode())
        .isEqualTo(expectedCode);
  }
}
