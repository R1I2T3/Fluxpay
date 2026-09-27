-- Indexed and legacy corpus material must stay immutable from the policy workspace.
-- Only chunks created explicitly by an administrator are marked MANUAL.
ALTER TABLE policy_chunks ADD (
  chunk_source VARCHAR2(16) DEFAULT 'GENERATED' NOT NULL
    CONSTRAINT ck_m5_policy_chunk_source CHECK (chunk_source IN ('GENERATED', 'MANUAL'))
);
