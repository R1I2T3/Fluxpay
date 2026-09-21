package com.fluxpay.controller;

import com.fluxpay.beans.KycDocumentType;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.dto.KycStatusResponse;
import com.fluxpay.service.KycUploadService;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/kyc")
public class KycDocumentController {
  private final KycUploadService uploads;

  public KycDocumentController(KycUploadService uploads) {
    this.uploads = uploads;
  }

  @PostMapping(value = "/applications", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<ApiResponse<KycStatusResponse>> submit(
      @AuthenticationPrincipal CurrentUser actor,
      @RequestParam KycDocumentType docType,
      @RequestParam String docNumber,
      @RequestPart("files") List<MultipartFile> files) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            new ApiResponse<>(
                Optional.ofNullable(org.slf4j.MDC.get("correlationId")).orElse("none"),
                uploads.submit(actor.userId(), docType, docNumber, files)));
  }

  @GetMapping("/documents/{id}/content")
  public ResponseEntity<byte[]> content(
      @AuthenticationPrincipal CurrentUser actor, @PathVariable UUID id) {
    var file = uploads.read(actor, id);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.type()))
        .cacheControl(CacheControl.noStore())
        .header("X-Content-Type-Options", "nosniff")
        .header("Content-Security-Policy", "sandbox; default-src 'none'")
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            ContentDisposition.attachment()
                .filename(file.name(), StandardCharsets.UTF_8)
                .build()
                .toString())
        .body(file.bytes());
  }
}
