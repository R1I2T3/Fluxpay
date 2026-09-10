CREATE TABLE payout_routes (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  route_code VARCHAR2(50) NOT NULL UNIQUE,
  route_name VARCHAR2(100) NOT NULL,
  provider_name VARCHAR2(100) NOT NULL,
  route_type VARCHAR2(30) NOT NULL
    CHECK (route_type IN ('STANDARD','INSTANT','LOCAL_PARTNER')),
  base_fee NUMBER(19,4) NOT NULL CHECK (base_fee >= 0),
  fx_spread_percentage NUMBER(9,6) NOT NULL CHECK (fx_spread_percentage >= 0),
  estimated_minutes NUMBER(10) NOT NULL CHECK (estimated_minutes > 0),
  success_rate NUMBER(5,2) NOT NULL CHECK (success_rate BETWEEN 0 AND 100),
  active NUMBER(1) NOT NULL CHECK (active IN (0,1)),
  version NUMBER(10) DEFAULT 0 NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE payout_attempts (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  payment_id VARCHAR2(50) NOT NULL,
  payout_route_id RAW(16) NOT NULL REFERENCES payout_routes(id),
  attempt_number NUMBER(10) NOT NULL CHECK (attempt_number > 0),
  status VARCHAR2(30) NOT NULL
    CHECK (status IN ('INITIATED','PROCESSING','COMPLETED','FAILED')),
  failure_reason VARCHAR2(1000),
  provider_reference VARCHAR2(100) UNIQUE,
  initiated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  completed_at TIMESTAMP WITH TIME ZONE,
  CONSTRAINT uq_payout_attempt UNIQUE (payment_id, attempt_number)
);

INSERT INTO payout_routes
  (route_code, route_name, provider_name, route_type, base_fee,
   fx_spread_percentage, estimated_minutes, success_rate, active,
   version, created_at, updated_at)
VALUES
  ('STANDARD_BANK',  'Standard Bank Rail', 'Standard Bank',     'STANDARD',       5.00, 0.8, 240, 99.50, 1, 0, SYSTIMESTAMP, SYSTIMESTAMP),
  ('INSTANT_PAYOUT', 'Instant Payout',     'Instant Payout Co', 'INSTANT',        8.50, 2.0, 5,   98.00, 1, 0, SYSTIMESTAMP, SYSTIMESTAMP),
  ('LOCAL_PARTNER',  'Local Partner',      'Local Partner Ltd', 'LOCAL_PARTNER',  2.00, 3.5, 150, 96.50, 1, 0, SYSTIMESTAMP, SYSTIMESTAMP);
