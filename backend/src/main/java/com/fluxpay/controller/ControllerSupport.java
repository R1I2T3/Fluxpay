package com.fluxpay.controller;

import com.fluxpay.common.security.CurrentUser;
import com.fluxpay.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Shared request plumbing for M4 controllers: user resolution and correlation IDs. */
final class ControllerSupport {
  private ControllerSupport() {}

  static CurrentUser currentUser() {
    Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
    if (authentication != null && authentication.getPrincipal() instanceof CurrentUser user) {
      return user;
    }
    return null;
  }

  static String correlationId(HttpServletRequest request) {
    String cid = MDC.get("correlationId");
    if (cid != null && !cid.isBlank()) {
      return cid;
    }
    if (request != null) {
      cid = request.getHeader(CorrelationIdFilter.HEADER);
      if (cid != null && !cid.isBlank()) {
        return cid;
      }
      Object attribute = request.getAttribute("correlationId");
      if (attribute != null && !attribute.toString().isBlank()) {
        return attribute.toString();
      }
    }
    return "none";
  }
}
