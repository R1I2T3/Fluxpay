package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fluxpay.beans.SupportTicket;
import org.junit.jupiter.api.Test;

class SupportTicketRepositoryTest {

  @Test
  void ticketDefaultsToOpenStatus() {
    SupportTicket ticket =
        new SupportTicket(
            java.util.UUID.randomUUID(), null, "Payout stuck", "My payment failed twice");

    assertEquals("OPEN", ticket.getStatus());
  }
}
