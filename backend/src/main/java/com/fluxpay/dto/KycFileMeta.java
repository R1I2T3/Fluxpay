package com.fluxpay.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Document metadata. Storage paths are never exposed to clients. */
public record KycFileMeta(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Pattern(regexp = "application/pdf|image/jpeg|image/png") String fileType,
    @Positive @Max(5 * 1024 * 1024) long fileSize,
    java.util.UUID id,
    java.time.Instant uploadedAt,
    boolean available) {
  public KycFileMeta(String fileName, String fileType, long fileSize) {
    this(fileName, fileType, fileSize, null, null, false);
  }

  public static KycFileMeta from(com.fluxpay.beans.KycDocument document) {
    return new KycFileMeta(
        document.getFileName(),
        document.getFileType(),
        document.getFileSize(),
        document.getId(),
        document.getUploadedAt(),
        document.getStorageUrl().startsWith("local:"));
  }
}
