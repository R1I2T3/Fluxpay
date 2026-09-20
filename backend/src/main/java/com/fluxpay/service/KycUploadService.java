package com.fluxpay.service;

import com.fluxpay.beans.*;
import com.fluxpay.common.enums.KycStatus;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.exception.KycException;
import com.fluxpay.repository.*;
import java.time.Clock;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KycUploadService {
  private final KycCaseRepository cases;
  private final KycDocumentRepository documents;
  private final UserRepository users;
  private final KycDocumentStorage storage;
  private final KycService kyc;
  private final Clock clock;

  public KycUploadService(
      KycCaseRepository cases,
      KycDocumentRepository documents,
      UserRepository users,
      KycDocumentStorage storage,
      KycService kyc,
      Clock clock) {
    this.cases = cases;
    this.documents = documents;
    this.users = users;
    this.storage = storage;
    this.kyc = kyc;
    this.clock = clock;
  }

  @Transactional
  public KycStatusResponse submit(
      UUID userId, KycDocumentType type, String number, List<MultipartFile> files) {
    if (type == null
        || number == null
        || number.isBlank()
        || number.trim().length() > 64
        || files == null
        || files.isEmpty()
        || files.size() > 4)
      throw new KycException(
          KycException.VALIDATION, "Enter your identity details and choose one to four documents.");
    // Lock the owner too: a first submission has no case row to lock yet.
    users.findByIdForUpdate(userId).orElseThrow(() -> missing());
    KycCase application = cases.findByUserIdForUpdate(userId).orElse(null);
    if (application != null && application.getStatus() != KycStatus.REJECTED)
      throw new KycException(
          KycException.KYC_CONFLICT,
          "Your documents are read-only. New uploads are allowed only after rejection.");
    var uploads = files.stream().map(storage::validate).toList();
    var now = clock.instant();
    if (application == null)
      application =
          new KycCase(UUID.randomUUID(), userId, KycStatus.PENDING, type, number.trim(), now, now);
    else application.resubmit(type, number.trim(), now);
    application = cases.saveAndFlush(application);
    var old = documents.findAllByKycCaseIdOrderByUploadedAtAsc(application.getId());
    List<String> stored = new ArrayList<>();
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status == STATUS_COMMITTED) old.forEach(d -> storage.remove(d.getStorageUrl()));
            else stored.forEach(storage::remove);
          }
        });
    List<KycDocument> replacements = new ArrayList<>();
    for (var upload : uploads) {
      UUID id = UUID.randomUUID();
      String key = storage.save(id, upload.bytes());
      stored.add(key);
      replacements.add(
          new KycDocument(
              id, application, upload.name(), upload.type(), upload.bytes().length, key, now));
    }
    documents.deleteAll(old);
    documents.saveAllAndFlush(replacements);
    return kyc.getMyStatus(userId);
  }

  public record Content(String name, String type, byte[] bytes) {}

  @Transactional(readOnly = true)
  public Content read(CurrentUser actor, UUID id) {
    KycDocument document = documents.findById(id).orElseThrow(() -> missing());
    if (!"ADMIN".equals(actor.role()) && !document.getKycCase().getUserId().equals(actor.userId()))
      throw missing();
    return new Content(
        document.getFileName(), document.getFileType(), storage.read(document.getStorageUrl()));
  }

  private KycException missing() {
    return new KycException(KycException.KYC_NOT_FOUND, "Document not found.");
  }
}
