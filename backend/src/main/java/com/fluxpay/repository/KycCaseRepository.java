package com.fluxpay.repository;

import com.fluxpay.beans.KycCase;
import com.fluxpay.common.enums.KycStatus;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface KycCaseRepository extends JpaRepository<KycCase, UUID> {
  Optional<KycCase> findByUserId(UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select k from KycCase k where k.userId = :userId")
  Optional<KycCase> findByUserIdForUpdate(@Param("userId") UUID userId);

  List<KycCase> findAllByStatusOrderBySubmittedAtAscIdAsc(KycStatus status);

  List<KycCase> findAllByOrderBySubmittedAtAscIdAsc();

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select k from KycCase k where k.id = :id")
  Optional<KycCase> findByIdForUpdate(@Param("id") UUID id);
}
