-- M5 publishes only 1536-dimensional Qwen/Ollama chunks. The existing 1536-dimensional
-- policy_chunks.embedding column is retained because Oracle does not support MODIFY on VECTOR
-- columns. Legacy chunks have a NULL generation_id and are never selected as an active corpus.

ALTER TABLE policy_documents ADD (
  active_generation_id RAW(16),
  embedding_space_id VARCHAR2(200),
  chunker_version VARCHAR2(100),
  index_state VARCHAR2(20) DEFAULT 'UNINDEXED' NOT NULL,
  chunk_count NUMBER(3) DEFAULT 0 NOT NULL,
  CONSTRAINT ck_m5_policy_index_state CHECK (index_state IN ('UNINDEXED', 'INDEXED')),
  CONSTRAINT ck_m5_policy_chunk_count CHECK (chunk_count BETWEEN 0 AND 16)
);

CREATE TABLE policy_generations (
  id RAW(16) PRIMARY KEY,
  policy_document_id RAW(16) NOT NULL,
  embedding_space_id VARCHAR2(200) NOT NULL,
  chunker_version VARCHAR2(100) NOT NULL,
  chunk_count NUMBER(3) NOT NULL CHECK (chunk_count BETWEEN 1 AND 16),
  created_at TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT fk_m5_generation_document
    FOREIGN KEY (policy_document_id) REFERENCES policy_documents(id),
  CONSTRAINT uq_m5_generation_document UNIQUE (policy_document_id, id)
);

ALTER TABLE policy_documents ADD CONSTRAINT fk_m5_policy_active_generation
  FOREIGN KEY (id, active_generation_id)
  REFERENCES policy_generations(policy_document_id, id);

ALTER TABLE policy_chunks ADD (generation_id RAW(16));
ALTER TABLE policy_chunks DROP CONSTRAINT uq_chunk;
ALTER TABLE policy_chunks ADD CONSTRAINT uq_m5_generation_chunk
  UNIQUE (policy_document_id, generation_id, chunk_number);
ALTER TABLE policy_chunks ADD CONSTRAINT fk_m5_chunk_generation
  FOREIGN KEY (policy_document_id, generation_id)
  REFERENCES policy_generations(policy_document_id, id);
ALTER TABLE policy_chunks ADD CONSTRAINT ck_m5_managed_chunk_number
  CHECK (generation_id IS NULL OR chunk_number BETWEEN 1 AND 16);
CREATE INDEX idx_m5_chunk_generation ON policy_chunks(policy_document_id, generation_id);
