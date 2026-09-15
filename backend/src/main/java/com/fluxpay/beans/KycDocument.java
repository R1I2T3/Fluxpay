package com.fluxpay.beans;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.sql.Types;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;

/** Metadata for a KYC document; this entity does not store document binary content. */
@Entity
@Table(name = "kyc_documents")
public class KycDocument {
  @Id
  @JdbcTypeCode(Types.BINARY)
  @Column(name = "id", nullable = false, columnDefinition = "RAW(16)")
  private UUID id;

  @ManyToOne(fetch = FetchType.LAZY, optional = false)
  @JoinColumn(name = "case_id", nullable = false)
  private KycCase kycCase;

  @Column(name = "file_name", nullable = false, length = 255)
  private String fileName;

  @Column(name = "file_type", nullable = false, length = 100)
  private String fileType;

  @Column(name = "file_size", nullable = false)
  private long fileSize;

  @Column(name = "storage_url", nullable = false, length = 1000)
  private String storageUrl;

  @JdbcTypeCode(Types.TIMESTAMP)
  @Column(name = "uploaded_at", nullable = false)
  private Instant uploadedAt;

  protected KycDocument() {}

  public KycDocument(
      UUID id,
      KycCase kycCase,
      String fileName,
      String fileType,
      long fileSize,
      String storageUrl,
      Instant uploadedAt) {
    this.id = id;
    this.kycCase = kycCase;
    this.fileName = fileName;
    this.fileType = fileType;
    this.fileSize = fileSize;
    this.storageUrl = storageUrl;
    this.uploadedAt = uploadedAt;
  }

  public UUID getId() {
    return id;
  }

  public KycCase getKycCase() {
    return kycCase;
  }

  public String getFileName() {
    return fileName;
  }

  public String getFileType() {
    return fileType;
  }

  public long getFileSize() {
    return fileSize;
  }

  public String getStorageUrl() {
    return storageUrl;
  }

  public Instant getUploadedAt() {
    return uploadedAt;
  }
}
