package com.fluxpay.service; import com.fluxpay.dto.*; import java.math.BigDecimal; import java.util.*;
public interface M3WalletPort { Optional<M3WalletSnapshot> findOwned(UUID userId,UUID walletId); M3PostingAccounts lockPostingAccounts(UUID userId,UUID walletId,String currency,BigDecimal gross); }
