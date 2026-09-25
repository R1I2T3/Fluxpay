package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.PaymentOperationsResponse;
import com.fluxpay.service.PaymentOperationsService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/payments")
@PreAuthorize("hasRole('ADMIN')")
public class PaymentOperationsAdminController {
  private final PaymentOperationsService operations;

  public PaymentOperationsAdminController(PaymentOperationsService operations) {
    this.operations = operations;
  }

  @GetMapping("/{paymentId}/operations")
  public ApiResponse<PaymentOperationsResponse> get(
      @PathVariable String paymentId, HttpServletRequest request) {
    return new ApiResponse<>(ControllerSupport.correlationId(request), operations.get(paymentId));
  }
}
