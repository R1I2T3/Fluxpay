package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.IndexResponse;
import com.fluxpay.dto.PolicyChunkRequest;
import com.fluxpay.dto.PolicyChunkResponse;
import com.fluxpay.dto.PolicyDocumentRequest;
import com.fluxpay.dto.PolicyDocumentResponse;
import com.fluxpay.service.PolicyChunkService;
import com.fluxpay.service.PolicyDocumentService;
import com.fluxpay.service.PolicyIndexingService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@org.springframework.context.annotation.Profile("m5-legacy")
@RestController
@RequestMapping("/api/policies")
public class PolicyController {

  private final PolicyDocumentService documentService;
  private final PolicyChunkService chunkService;
  private final PolicyIndexingService indexingService;

  public PolicyController(
      PolicyDocumentService documentService,
      PolicyChunkService chunkService,
      PolicyIndexingService indexingService) {
    this.documentService = documentService;
    this.chunkService = chunkService;
    this.indexingService = indexingService;
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

  /** Rebuilds all chunks + embeddings for this document from its current content. */
  @PostMapping("/{id}/index")
  public ApiResponse<IndexResponse> index(@PathVariable UUID id) {
    return wrap(indexingService.reindex(id));
  }

  private <T> ApiResponse<T> wrap(T data) {
    String cid = MDC.get("correlationId");
    return new ApiResponse<>(cid == null ? "none" : cid, data);
  }
}
