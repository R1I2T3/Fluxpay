package com.fluxpay.common.contracts;

import com.fluxpay.dto.*;
import java.math.BigDecimal;
import java.util.*;

public interface WalletPort {
  Optional<WalletSnapshot> findOwned(UUID userId, UUID walletId);

  PostingAccounts lockPostingAccounts(
      UUID userId, UUID walletId, String currency, BigDecimal gross);
}
