-- Additive M5 vector publication. No historical content, hashes, timestamps or chunks are rewritten.
-- Canonical hashes must be populated by the explicit transactional Java reconciliation command.
-- Historical recorded hashes may describe an earlier content revision. Preserve their values,
-- but do not let their old uniqueness constraint reject distinct canonical content.
ALTER TABLE policy_documents DROP UNIQUE (document_hash);
-- Match the API's 200 Unicode-character title limit independently of database BYTE defaults.
ALTER TABLE policy_documents MODIFY (title VARCHAR2(200 CHAR));
ALTER TABLE policy_documents ADD (
  canonical_hash VARCHAR2(64),
  document_version NUMBER(19) DEFAULT 0 NOT NULL,
  index_state VARCHAR2(20) DEFAULT 'UNINDEXED' NOT NULL,
  active_generation_id RAW(16),
  embedding_space_id VARCHAR2(200),
  chunker_version VARCHAR2(100),
  chunk_count NUMBER(3) DEFAULT 0 NOT NULL,
  CONSTRAINT uq_m5_policy_canonical UNIQUE (canonical_hash),
  CONSTRAINT ck_m5_policy_state CHECK (index_state IN ('UNINDEXED','INDEXED')),
  CONSTRAINT ck_m5_policy_version CHECK (document_version >= 0),
  CONSTRAINT ck_m5_policy_count CHECK (chunk_count BETWEEN 0 AND 16),
  CONSTRAINT ck_m5_policy_active CHECK (
    (index_state = 'UNINDEXED' AND active_generation_id IS NULL AND chunk_count = 0)
    OR (index_state = 'INDEXED' AND active_generation_id IS NOT NULL
      AND embedding_space_id IS NOT NULL AND chunker_version IS NOT NULL AND chunk_count BETWEEN 1 AND 16))
);

CREATE TABLE policy_generations (
  id RAW(16) PRIMARY KEY,
  policy_document_id RAW(16) NOT NULL,
  embedding_space_id VARCHAR2(200) NOT NULL,
  chunker_version VARCHAR2(100) NOT NULL,
  chunk_count NUMBER(3) NOT NULL CHECK (chunk_count BETWEEN 1 AND 16),
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT fk_m5_generation_document FOREIGN KEY (policy_document_id) REFERENCES policy_documents(id),
  CONSTRAINT uq_m5_generation_document UNIQUE (policy_document_id, id)
);

ALTER TABLE policy_documents ADD CONSTRAINT fk_m5_policy_active_generation
  FOREIGN KEY (id, active_generation_id) REFERENCES policy_generations(policy_document_id, id);

-- A NULL generation denotes retained, ineligible legacy chunks; managed chunks retain VECTOR(768,FLOAT32).
ALTER TABLE policy_chunks ADD (generation_id RAW(16));
ALTER TABLE policy_chunks DROP CONSTRAINT uq_chunk;
-- Oracle compares partially-NULL unique keys: retain the document id so legacy chunk 1
-- in two different documents does not collide while both generation ids are NULL.
ALTER TABLE policy_chunks ADD CONSTRAINT uq_m5_generation_chunk UNIQUE (policy_document_id, generation_id, chunk_number);
ALTER TABLE policy_chunks ADD CONSTRAINT fk_m5_chunk_generation
  FOREIGN KEY (policy_document_id, generation_id) REFERENCES policy_generations(policy_document_id, id);
ALTER TABLE policy_chunks ADD CONSTRAINT ck_m5_managed_chunk_number
  CHECK (generation_id IS NULL OR chunk_number BETWEEN 1 AND 16);
CREATE INDEX idx_m5_chunk_generation ON policy_chunks(policy_document_id, generation_id);
