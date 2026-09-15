-- Reference privilege inventory used by scripts/reset-local-db.py.
-- The helper validates an exact FLUXPAY or FLUXPAY_TEST target, creates it with
-- an environment-sourced password, and applies only these application grants.
GRANT CREATE SESSION TO &SCHEMA;
GRANT CREATE TABLE TO &SCHEMA;
GRANT CREATE SEQUENCE TO &SCHEMA;
GRANT CREATE TRIGGER TO &SCHEMA;
GRANT CREATE PROCEDURE TO &SCHEMA;
GRANT CREATE VIEW TO &SCHEMA;
ALTER USER &SCHEMA QUOTA UNLIMITED ON USERS;
