CREATE TABLE users (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  email VARCHAR2(255) NOT NULL,
  password_hash VARCHAR2(255) NOT NULL,
  full_name VARCHAR2(255) NOT NULL,
  role VARCHAR2(20) DEFAULT 'CUSTOMER' NOT NULL,
  kyc_status VARCHAR2(20) DEFAULT 'NONE' NOT NULL,
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT chk_users_role CHECK (role IN ('CUSTOMER', 'ADMIN')),
  CONSTRAINT chk_users_kyc_status CHECK (kyc_status IN ('NONE', 'PENDING', 'VERIFIED', 'REJECTED'))
);

CREATE INDEX idx_users_kyc_status ON users(kyc_status);

CREATE OR REPLACE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW
BEGIN
  :NEW.updated_at := SYSTIMESTAMP;
END;
/

CREATE TABLE user_roles (
  user_id RAW(16) NOT NULL REFERENCES users(id),
  role VARCHAR2(20) NOT NULL,
  PRIMARY KEY(user_id, role),
  CONSTRAINT chk_user_roles_role CHECK (role IN ('CUSTOMER', 'ADMIN'))
);

CREATE TABLE refresh_tokens (
  id RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  user_id RAW(16) NOT NULL REFERENCES users(id),
  token_hash VARCHAR2(255) NOT NULL,
  expires_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_refresh_tokens_user_id ON refresh_tokens(user_id);
