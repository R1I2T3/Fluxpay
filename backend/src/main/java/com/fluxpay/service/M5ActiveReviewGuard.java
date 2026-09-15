package com.fluxpay.service;
import java.util.UUID;
/** Read-only committed M3 binding check, called while M5 holds its publication head lock. */
public interface M5ActiveReviewGuard { boolean isActive(UUID paymentId,UUID caseId); }
