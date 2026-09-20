-- Optional custom reason for new OTHERS payments. Existing payments are unchanged.
ALTER TABLE payments ADD (purpose_reason VARCHAR2(250 CHAR));
