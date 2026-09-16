package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.PolicyChunkRequest;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.dto.PolicyDocumentRequest;
import com.fluxpay.dto.PolicyDocumentResponse;
import com.fluxpay.m5.application.M5PolicyDeletionService;
import com.fluxpay.service.PolicyChunkService;
import com.fluxpay.service.PolicyDocumentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.access.prepost.PreAuthorize;

@RestController
@RequestMapping("/api/policies")
public class PolicyController {

  private final PolicyDocumentService documentService;
  private final PolicyChunkService chunkService;
  private final M5PolicyDeletionService deletionService;

  public PolicyController(
      PolicyDocumentService documentService,
      PolicyChunkService chunkService,
      M5PolicyDeletionService deletionService) {
    this.documentService = documentService;
    this.chunkService = chunkService;
    this.deletionService = deletionService;
  }

  @PostMapping
  public ResponseEntity<ApiResponse<PolicyDocumentResponse>> create(
      @Valid @RequestBody PolicyDocumentRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(wrap(documentService.create(request)));
  }

  @GetMapping
  public ApiResponse<List<PolicyDocumentResponse>> list() {
    return wrap(documentService.list());
  }

  @GetMapping("/{id}")
  public ApiResponse<PolicyDocumentResponse> getById(@PathVariable UUID id) {
    return wrap(documentService.getById(id));
  }

  @PostMapping("/{id}/chunks")
  public ResponseEntity<ApiResponse<PolicyChunkResponse>> addChunk(
      @PathVariable UUID id, @Valid @RequestBody PolicyChunkRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(wrap(chunkService.addChunk(id, request)));
  }

  @GetMapping("/{id}/chunks")
  public ApiResponse<List<PolicyChunkResponse>> listChunks(@PathVariable UUID id) {
    return wrap(chunkService.list(id));
  }

  @DeleteMapping("/{id}")
  @PreAuthorize("hasRole('ADMIN')")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    deletionService.delete(id);
    return ResponseEntity.noContent().build();
  }

  private <T> ApiResponse<T> wrap(T data) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, data);
  }
}
