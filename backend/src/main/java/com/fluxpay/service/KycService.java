package com.fluxpay.service;

import com.fluxpay.beans.KycCase;
import com.fluxpay.beans.KycDocument;
import com.fluxpay.beans.User;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.dto.KycAdminRow;
import com.fluxpay.dto.KycFileMeta;
import com.fluxpay.dto.KycReviewRequest;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.dto.KycSubmitRequest;
import com.fluxpay.exception.KycException;
import com.fluxpay.repository.KycCaseRepository;
import com.fluxpay.repository.KycDocumentRepository;
import com.fluxpay.repository.UserRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KycService {
  private final KycCaseRepository kycCases;
  private final KycDocumentRepository kycDocuments;
  private final UserRepository users;
  private final java.time.Clock clock;
  private final boolean metadataEnabled;
  private final KycDocumentStorage storage;

  public KycService(
      KycCaseRepository kycCases,
      KycDocumentRepository kycDocuments,
      UserRepository users,
      java.time.Clock clock,
      @org.springframework.beans.factory.annotation.Value(
              "${fluxpay.development.kyc-metadata-enabled:false}")
          boolean metadataEnabled,
      KycDocumentStorage storage) {
    this.kycCases = kycCases;
    this.kycDocuments = kycDocuments;
    this.users = users;
    this.clock = clock;
    this.metadataEnabled = metadataEnabled;
    this.storage = storage;
  }

  @Transactional(readOnly = true)
  public KycStatusResponse getMyStatus(UUID userId) {
    return kycCases.findByUserId(userId).map(this::toStatusResponse).orElseGet(this::noneResponse);
  }

  @Transactional
  public KycStatusResponse submit(UUID userId, KycSubmitRequest request) {
    // Validate storage capability before claiming a completed external action: document metadata
    // requires an explicitly enabled development metadata store. Without it, fail honestly instead
    // of manufacturing a URL that pretends a file was stored.
    if (!metadataEnabled) {
      throw new KycException(
          KycException.KYC_STORAGE_UNAVAILABLE,
          "No KYC document storage is configured; files were not stored.");
    }
    Instant now = clock.instant();
    KycCase kycCase =
        kycCases
            .findByUserIdForUpdate(userId)
            .map(existing -> resubmitOrReject(existing, request, now))
            .orElseGet(() -> createCase(userId, request, now));

    kycCase = kycCases.saveAndFlush(kycCase);
    replaceDocuments(kycCase, request.documents(), now);
    return toStatusResponse(kycCase);
  }

  @Transactional(readOnly = true)
  public List<KycAdminRow> listForAdmin(KycStatus status, Pageable pageable) {
    List<KycCase> cases =
        status == null
            ? kycCases.findAllByOrderBySubmittedAtAscIdAsc(pageable).getContent()
            : kycCases.findAllByStatusOrderBySubmittedAtAscIdAsc(status, pageable).getContent();
    return cases.stream().map(this::toAdminRow).toList();
  }

  @Transactional
  public KycStatusResponse approve(UUID reviewerId, UUID applicationId, KycReviewRequest request) {
    KycCase kycCase = reviewableCase(applicationId, request.expectedVersion());
    List<KycDocument> submitted =
        kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(applicationId);
    if (submitted.isEmpty()
        || submitted.stream().anyMatch(d -> !d.getStorageUrl().startsWith("local:"))) {
      throw new KycException(
          KycException.VALIDATION,
          "Original documents are unavailable. Reject this application and request a new upload.");
    }
    // A local storage marker alone is not evidence that the original file still exists.
    submitted.forEach(document -> storage.read(document.getStorageUrl()));
    User reviewer = findUser(reviewerId);
    kycCase.approve(reviewer, clock.instant());
    return toStatusResponse(kycCases.saveAndFlush(kycCase));
  }

  @Transactional
  public KycStatusResponse reject(UUID reviewerId, UUID applicationId, KycReviewRequest request) {
    if (request.reason() == null || request.reason().isBlank()) {
      throw new KycException(KycException.REJECT_REASON_REQUIRED, "a rejection reason is required");
    }
    KycCase kycCase = reviewableCase(applicationId, request.expectedVersion());
    User reviewer = findUser(reviewerId);
    kycCase.reject(reviewer, clock.instant(), request.reason().trim());
    return toStatusResponse(kycCases.saveAndFlush(kycCase));
  }

  private KycCase createCase(UUID userId, KycSubmitRequest request, Instant now) {
    return new KycCase(
        UUID.randomUUID(),
        userId,
        KycStatus.PENDING,
        request.docType(),
        request.docNumber().trim(),
        now,
        now);
  }

  private KycCase resubmitOrReject(KycCase existing, KycSubmitRequest request, Instant now) {
    if (existing.getStatus() == KycStatus.PENDING) {
      throw new KycException(
          KycException.KYC_ALREADY_PENDING, "KYC application is already pending");
    }
    if (existing.getStatus() == KycStatus.VERIFIED) {
      throw new KycException(
          KycException.KYC_ALREADY_VERIFIED, "KYC application is already verified");
    }
    existing.resubmit(request.docType(), request.docNumber().trim(), now);
    return existing;
  }

  private void replaceDocuments(KycCase kycCase, List<KycFileMeta> metadata, Instant uploadedAt) {
    kycDocuments.deleteAll(kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(kycCase.getId()));

    List<KycDocument> documents = new ArrayList<>();
    for (KycFileMeta file : metadata) {
      UUID documentId = UUID.randomUUID();
      documents.add(
          new KycDocument(
              documentId,
              kycCase,
              file.fileName().trim(),
              file.fileType(),
              file.fileSize(),
              // Metadata-only development record: explicitly states files were not stored.
              KycDocument.NOT_STORED_METADATA_ONLY,
              uploadedAt));
    }
    kycDocuments.saveAll(documents);
  }

  private KycCase reviewableCase(UUID applicationId, Long expectedVersion) {
    KycCase kycCase =
        kycCases
            .findByIdForUpdate(applicationId)
            .orElseThrow(
                () -> new KycException(KycException.KYC_NOT_FOUND, "KYC application not found"));
    if (expectedVersion == null || kycCase.getVersion() != expectedVersion) {
      throw new KycException(KycException.KYC_CONFLICT, "KYC application has changed");
    }
    if (kycCase.getStatus() != KycStatus.PENDING) {
      throw new KycException(
          KycException.KYC_ALREADY_DECIDED, "KYC application is already decided");
    }
    return kycCase;
  }

  private User findUser(UUID userId) {
    return users.findById(userId).orElseThrow(() -> new NoSuchElementException("user not found"));
  }

  private KycStatusResponse noneResponse() {
    return new KycStatusResponse(null, null, KycStatus.NONE, null, null, null);
  }

  private KycStatusResponse toStatusResponse(KycCase kycCase) {
    return new KycStatusResponse(
        kycCase.getId(),
        kycCase.getVersion(),
        kycCase.getStatus(),
        kycCase.getRejectReason(),
        kycCase.getSubmittedAt(),
        kycCase.getDecidedAt(),
        kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(kycCase.getId()).stream()
            .map(KycFileMeta::from)
            .toList());
  }

  private KycAdminRow toAdminRow(KycCase kycCase) {
    // Orphaned user must not fail the entire admin list; surface a placeholder instead.
    // TODO: replace per-row user/document queries with a fetch-join or batch load.
    User user = users.findById(kycCase.getUserId()).orElse(null);
    String email = user == null ? "unknown@fluxpay.invalid" : user.getEmail();
    String fullName = user == null ? "Unknown user" : user.getFullName();
    List<KycFileMeta> documents =
        kycDocuments.findAllByKycCaseIdOrderByUploadedAtAsc(kycCase.getId()).stream()
            .map(KycFileMeta::from)
            .toList();
    return new KycAdminRow(
        kycCase.getId(),
        kycCase.getVersion(),
        email,
        fullName,
        kycCase.getDocType(),
        kycCase.getDocNumber(),
        kycCase.getStatus(),
        kycCase.getSubmittedAt(),
        kycCase.getDecidedAt(),
        kycCase.getRejectReason(),
        documents);
  }
}
