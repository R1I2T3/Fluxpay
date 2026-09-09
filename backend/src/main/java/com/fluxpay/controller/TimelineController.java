package com.fluxpay.controller;

import com.fluxpay.common.api.ApiResponse;
import com.fluxpay.dto.TimelineEventResponse;
import com.fluxpay.service.ForbiddenException;
import com.fluxpay.service.PaymentReader;
import com.fluxpay.service.PaymentSnapshot;
import com.fluxpay.service.RouteAdminAuthorizer;
import com.fluxpay.service.TimelineService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TimelineController {

  private final PaymentReader reader;
  private final TimelineService timeline;
  private final RouteAdminAuthorizer authorizer;

  public TimelineController(
      PaymentReader reader, TimelineService timeline, RouteAdminAuthorizer authorizer) {
    this.reader = Objects.requireNonNull(reader, "reader must not be null");
    this.timeline = Objects.requireNonNull(timeline, "timeline must not be null");
    this.authorizer = Objects.requireNonNull(authorizer, "authorizer must not be null");
  }

  @GetMapping("/api/payments/{paymentId}/timeline")
  public ApiResponse<List<TimelineEventResponse>> timeline(
      @PathVariable String paymentId, HttpServletRequest request) {
    String cid = ControllerSupport.correlationId(request);
    PaymentSnapshot payment = reader.get(paymentId);
    if (!authorizer.isOwner(ControllerSupport.currentUser(), payment)) {
      throw new ForbiddenException("user is not the owner of payment " + paymentId);
    }
    return new ApiResponse<>(cid, timeline.getTimeline(paymentId));
  }
}
