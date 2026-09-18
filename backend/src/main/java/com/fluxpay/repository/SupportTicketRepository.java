package com.fluxpay.repository;

import com.fluxpay.beans.SupportTicket;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportTicketRepository extends JpaRepository<SupportTicket, UUID> {
  Page<SupportTicket> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

  Page<SupportTicket> findByStatusOrderByCreatedAtDesc(String status, Pageable pageable);
}
