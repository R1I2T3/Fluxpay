package com.fluxpay.common.contracts;

import com.fluxpay.dto.FxSnapshot;

public interface FxSnapshotSource {
  FxSnapshot fetch(String from, String to);
}
