package com.fluxpay.repository;

import com.fluxpay.beans.SupportTicket;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
  Optional<SupportTicket> findByIdAndUserId(UUID id, UUID userId);

  Page<SupportTicket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  Page<SupportTicket> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);

  Page<SupportTicket> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
