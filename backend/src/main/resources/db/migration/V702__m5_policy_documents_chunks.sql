-- V702: Member 5 policy corpus for the Compliance Copilot.
-- Vector indexing (CREATE VECTOR INDEX ... ORGANIZATION NEIGHBOR PARTITIONS)
-- is deliberately deferred to a later migration until embeddings are
-- actually being written -- an index over an all-NULL column adds nothing
-- yet and just adds risk to this pass. embedding stays a plain nullable
-- VECTOR column for now.
--
-- Dimension is 768, not the product spec's original 1536: this project uses
-- a local Ollama embedding model (nomic-embed-text) instead of an OpenAI-
-- compatible 1536-dim API model -- see OllamaEmbeddingProvider and
-- V704__m5_rich_policy_corpus.sql. If you later switch to a provider with a
-- different output size, either change this to match (no data loss, the
-- column is still empty pre-indexing) or use the flexible `VECTOR(*, FLOAT32)`
-- declaration instead of a fixed width.

CREATE TABLE policy_documents (
  id              RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  title           VARCHAR2(200) NOT NULL,
  category        VARCHAR2(20) NOT NULL
                     CHECK (category IN ('KYC','AML','PAYMENT_REVIEW','COUNTRY_RULE','SUPPORT')),
  content         CLOB NOT NULL,
  document_hash   VARCHAR2(64) NOT NULL UNIQUE,
  created_at      TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL
);

CREATE TABLE policy_chunks (
  id                    RAW(16) DEFAULT SYS_GUID() PRIMARY KEY,
  policy_document_id    RAW(16) NOT NULL REFERENCES policy_documents(id) ON DELETE CASCADE,
  chunk_number          NUMBER(10) NOT NULL,
  content               CLOB NOT NULL,
  embedding             VECTOR(768, FLOAT32),
  created_at            TIMESTAMP DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT uq_chunk UNIQUE (policy_document_id, chunk_number)
);

CREATE INDEX idx_chunk_doc ON policy_chunks(policy_document_id);
