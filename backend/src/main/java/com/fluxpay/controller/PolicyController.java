package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.PolicyChunkRequest;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.dto.PolicyDocumentRequest;
import com.fluxpay.dto.PolicyDocumentResponse;
import com.fluxpay.dto.PolicyGuidanceRequest;
import com.fluxpay.dto.PolicyGuidanceResponse;
import com.fluxpay.dto.PolicyGuidanceUpdateRequest;
import com.fluxpay.service.PolicyChunkService;
import com.fluxpay.service.PolicyDeletionService;
import com.fluxpay.service.PolicyDocumentService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/policies")
@PreAuthorize("hasRole('ADMIN')")
public class PolicyController {

  private final PolicyDocumentService documentService;
  private final PolicyChunkService chunkService;
  private final PolicyDeletionService deletionService;
  private final com.fluxpay.service.PolicyGuidanceService guidanceService;

  public PolicyController(
      PolicyDocumentService documentService,
      PolicyChunkService chunkService,
      PolicyDeletionService deletionService,
      com.fluxpay.service.PolicyGuidanceService guidanceService) {
    this.documentService = documentService;
    this.chunkService = chunkService;
    this.deletionService = deletionService;
    this.guidanceService = guidanceService;
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

  @PutMapping("/{id}")
  public ResponseEntity<ApiResponse<PolicyDocumentResponse>> update(
      @PathVariable UUID id, @Valid @RequestBody PolicyDocumentRequest request) {
    return ResponseEntity.ok(wrap(documentService.update(id, request)));
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

  @GetMapping("/{id}/guidance")
  public ApiResponse<List<PolicyGuidanceResponse>> listGuidance(@PathVariable UUID id) {
    return wrap(guidanceService.list(id));
  }

  @PostMapping("/{id}/guidance")
  public ResponseEntity<ApiResponse<PolicyGuidanceResponse>> addGuidance(
      @PathVariable UUID id, @Valid @RequestBody PolicyGuidanceRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED).body(wrap(guidanceService.add(id, request)));
  }

  @PutMapping("/{id}/guidance/{guidanceId}")
  public ApiResponse<PolicyGuidanceResponse> updateGuidance(
      @PathVariable UUID id,
      @PathVariable UUID guidanceId,
      @Valid @RequestBody PolicyGuidanceUpdateRequest request) {
    return wrap(guidanceService.update(id, guidanceId, request));
  }

  @DeleteMapping("/{id}/guidance/{guidanceId}")
  public ResponseEntity<Void> deleteGuidance(@PathVariable UUID id, @PathVariable UUID guidanceId) {
    guidanceService.delete(id, guidanceId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/{id}/chunks/{chunkId}")
  public ApiResponse<PolicyChunkResponse> updateChunk(
      @PathVariable UUID id,
      @PathVariable UUID chunkId,
      @Valid @RequestBody PolicyChunkRequest request) {
    return wrap(chunkService.updateChunk(id, chunkId, request));
  }

  @DeleteMapping("/{id}/chunks/{chunkId}")
  public ResponseEntity<Void> deleteChunk(@PathVariable UUID id, @PathVariable UUID chunkId) {
    chunkService.deleteChunk(id, chunkId);
    return ResponseEntity.noContent().build();
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    deletionService.delete(id);
    return ResponseEntity.noContent().build();
  }

  private <T> ApiResponse<T> wrap(T data) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, data);
  }
}
