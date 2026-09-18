CREATE TABLE support_tickets (
  id RAW(16) PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  payment_id RAW(16) NULL REFERENCES payments(id),
  subject VARCHAR2(120) NOT NULL,
  body VARCHAR2(4000) NOT NULL,
  status VARCHAR2(20) DEFAULT 'OPEN' NOT NULL,
  assignee_admin_id RAW(16) NULL REFERENCES users(id),
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  CONSTRAINT chk_support_tickets_status
    CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'))
);

CREATE INDEX idx_support_tickets_user_created
  ON support_tickets (user_id, created_at DESC);

CREATE INDEX idx_support_tickets_status_created
  ON support_tickets (status, created_at DESC);

CREATE TABLE support_ticket_messages (
  id RAW(16) PRIMARY KEY,
  ticket_id RAW(16) NOT NULL REFERENCES support_tickets(id) ON DELETE CASCADE,
  author_user_id RAW(16) NOT NULL REFERENCES users(id),
  body VARCHAR2(4000) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_support_ticket_messages_ticket_created
  ON support_ticket_messages (ticket_id, created_at ASC);
