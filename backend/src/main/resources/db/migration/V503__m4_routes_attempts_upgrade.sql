-- Forward upgrade from the original V301 payout schema. Keep the source tables and
-- their foreign keys intact so all historical fields remain recoverable.
ALTER TABLE payout_attempts RENAME TO payout_attempts_legacy;
ALTER TABLE payout_routes RENAME TO payout_routes_legacy;

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

-- Legacy routes have no pricing/provider configuration. Preserve them as inactive
-- until a supported provider is explicitly configured; keep the full names in legacy.
INSERT INTO payout_routes
  (id, route_code, route_name, provider_name, route_type, base_fee,
   fx_spread_percentage, estimated_minutes, success_rate, active,
   version, created_at, updated_at)
SELECT SYS_GUID(), code, SUBSTR(name, 1, 100), 'LEGACY_UNCONFIGURED', 'STANDARD',
       0, 0, 1, 0, 0, 0, FROM_TZ(created_at, DBTIMEZONE), SYSTIMESTAMP
FROM payout_routes_legacy;

INSERT INTO payout_attempts
  (id, payment_id, payout_route_id, attempt_number, status, initiated_at, completed_at)
SELECT a.id,
       LOWER(SUBSTR(RAWTOHEX(a.payment_id), 1, 8) || '-' ||
             SUBSTR(RAWTOHEX(a.payment_id), 9, 4) || '-' ||
             SUBSTR(RAWTOHEX(a.payment_id), 13, 4) || '-' ||
             SUBSTR(RAWTOHEX(a.payment_id), 17, 4) || '-' ||
             SUBSTR(RAWTOHEX(a.payment_id), 21, 12)),
       r.id,
       ROW_NUMBER() OVER (PARTITION BY a.payment_id ORDER BY a.created_at, a.id),
       CASE a.status WHEN 'PENDING' THEN 'INITIATED'
                     WHEN 'SUBMITTED' THEN 'PROCESSING' ELSE a.status END,
       FROM_TZ(a.created_at, DBTIMEZONE),
       NULL
FROM payout_attempts_legacy a JOIN payout_routes r ON r.route_code = a.route_code;

-- Preserve an existing route with one of these codes; never silently activate it.
INSERT INTO payout_routes
  (route_code, route_name, provider_name, route_type, base_fee,
   fx_spread_percentage, estimated_minutes, success_rate, active,
   version, created_at, updated_at)
SELECT seeds.route_code, seeds.route_name, seeds.provider_name, seeds.route_type,
       seeds.base_fee, seeds.spread, seeds.minutes, seeds.success_rate,
       1, 0, SYSTIMESTAMP, SYSTIMESTAMP
FROM (
  SELECT 'STANDARD_BANK' route_code, 'Standard Bank Rail' route_name,
         'Standard Bank' provider_name, 'STANDARD' route_type,
         5.00 base_fee, 0.8 spread, 240 minutes, 99.50 success_rate FROM dual
  UNION ALL
  SELECT 'INSTANT_PAYOUT', 'Instant Payout', 'Instant Payout Co', 'INSTANT',
         8.50, 2.0, 5, 98.00 FROM dual
  UNION ALL
  SELECT 'LOCAL_PARTNER', 'Local Partner', 'Local Partner Ltd', 'LOCAL_PARTNER',
         2.00, 3.5, 150, 96.50 FROM dual
) seeds
WHERE NOT EXISTS (SELECT 1 FROM payout_routes r WHERE r.route_code = seeds.route_code);
