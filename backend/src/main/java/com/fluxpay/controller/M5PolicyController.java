package com.fluxpay.controller;

import com.fluxpay.beans.M5PolicyDocument;
import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.dto.M5PolicyDtos;
import com.fluxpay.service.M5CanonicalHashReconciler;
import com.fluxpay.service.M5PolicyService;
import java.util.*;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Profile("m5-backend")
@RestController @RequestMapping("/api/policies")
public class M5PolicyController {
  private final M5PolicyService policies;
  private final M5CanonicalHashReconciler reconciler;
  public M5PolicyController(M5PolicyService policies,M5CanonicalHashReconciler reconciler) {
    this.policies=policies;this.reconciler=reconciler;
  }
  @PostMapping public ResponseEntity<ApiResponse<M5PolicyDocument>> create(
      @RequestBody M5PolicyDtos.Create request,@AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);return ResponseEntity.status(201).body(wrap(policies.create(request)));
  }
  @GetMapping public ApiResponse<M5PolicyDtos.Page> list(@RequestParam(defaultValue="0") int page,
      @RequestParam(defaultValue="20") int size,@AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);return wrap(policies.list(page,size));
  }
  @GetMapping("/{id}") public ApiResponse<M5PolicyDocument> get(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);return wrap(policies.get(id));
  }
  @PostMapping("/{id}/index") public ApiResponse<M5PolicyDtos.Index> index(@PathVariable UUID id,@AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);return wrap(policies.index(id));
  }
  @PostMapping("/reconcile-hashes") public ApiResponse<Map<String,Integer>> reconcile(@AuthenticationPrincipal CurrentUser actor) {
    requireAdmin(actor);return wrap(Map.of("updatedDocuments",reconciler.reconcile()));
  }
  static void requireAdmin(CurrentUser actor) {
    if(actor==null) throw new M5ApiException(401,"AUTH_REQUIRED","A valid signed bearer token is required");
    if(!"ADMIN".equals(actor.role())) throw new M5ApiException(403,"FORBIDDEN","ADMIN access is required");
  }
  static <T> ApiResponse<T> wrap(T value) {
    String id=MDC.get("correlationId");return new ApiResponse<>(id==null?UUID.randomUUID().toString():id,value);
  }
}
