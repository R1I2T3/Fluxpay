package com.fluxpay.service;

import com.fluxpay.common.security.CurrentUser;
import java.util.Map;
import java.util.UUID;

/** Reads authorized persisted context; this operation never triggers an assessment. */
public interface M5CaseContextReader {
    Map<String, Object> context(UUID paymentId, CurrentUser actor);

    /** Deadline-aware read used by bounded callers; legacy implementations remain compatible. */
    default Map<String, Object> context(
            UUID paymentId, CurrentUser actor, M5WorkDeadline deadline) {
        deadline.check();
        Map<String, Object> value = context(paymentId, actor);
        deadline.check();
        return value;
    }
}
