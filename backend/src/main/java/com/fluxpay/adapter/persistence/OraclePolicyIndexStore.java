package com.fluxpay.adapter.persistence;

import com.fluxpay.common.contracts.PolicyIndexStore;
import com.fluxpay.dto.IndexedPolicyChunk;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import oracle.jdbc.OracleType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Publishes an immutable Oracle vector generation and then atomically activates it for a policy.
 */
@Repository
public class OraclePolicyIndexStore implements PolicyIndexStore {
  private static final String INSERT_GENERATION_SQL =
      "INSERT INTO policy_generations "
          + "(id, policy_document_id, embedding_space_id, chunker_version, chunk_count) "
          + "VALUES (?, ?, ?, ?, ?)";
  private static final String INSERT_CHUNK_SQL =
      "INSERT INTO policy_chunks "
          + "(id, policy_document_id, generation_id, chunk_number, content, embedding) "
          + "VALUES (?, ?, ?, ?, ?, ?)";
  private static final String ACTIVATE_GENERATION_SQL =
      "UPDATE policy_documents "
          + "SET active_generation_id = ?, embedding_space_id = ?, chunker_version = ?, "
          + "index_state = 'INDEXED', chunk_count = ? "
          + "WHERE id = ?";
  private static final String CLEAR_ACTIVE_GENERATION_SQL =
      "UPDATE policy_documents SET active_generation_id = NULL WHERE id = ?";
  private static final String DELETE_CHUNKS_SQL =
      "DELETE FROM policy_chunks WHERE policy_document_id = ?";
  private static final String DELETE_GENERATIONS_SQL =
      "DELETE FROM policy_generations WHERE policy_document_id = ?";

  private final JdbcTemplate jdbcTemplate;

  public OraclePolicyIndexStore(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void publish(
      UUID policyDocumentId,
      String embeddingSpaceId,
      String chunkerVersion,
      List<IndexedPolicyChunk> chunks) {
    if (chunks == null || chunks.isEmpty() || chunks.size() > 16) {
      throw new IllegalArgumentException("Policy generation must contain between 1 and 16 chunks");
    }
    UUID generationId = UUID.randomUUID();
    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            connection -> {
              insertGeneration(
                  connection,
                  generationId,
                  policyDocumentId,
                  embeddingSpaceId,
                  chunkerVersion,
                  chunks.size());
              insertChunks(connection, generationId, policyDocumentId, chunks);
              activateGeneration(
                  connection,
                  generationId,
                  policyDocumentId,
                  embeddingSpaceId,
                  chunkerVersion,
                  chunks.size());
              return null;
            });
  }

  @Override
  public void delete(UUID policyDocumentId) {
    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            connection -> {
              executeForDocument(connection, CLEAR_ACTIVE_GENERATION_SQL, policyDocumentId);
              executeForDocument(connection, DELETE_CHUNKS_SQL, policyDocumentId);
              executeForDocument(connection, DELETE_GENERATIONS_SQL, policyDocumentId);
              return null;
            });
  }

  private static void executeForDocument(
      java.sql.Connection connection, String sql, UUID policyDocumentId)
      throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setBytes(1, raw16(policyDocumentId));
      statement.executeUpdate();
    }
  }

  private static void insertGeneration(
      java.sql.Connection connection,
      UUID generationId,
      UUID documentId,
      String embeddingSpaceId,
      String chunkerVersion,
      int chunkCount)
      throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_GENERATION_SQL)) {
      statement.setBytes(1, raw16(generationId));
      statement.setBytes(2, raw16(documentId));
      statement.setString(3, embeddingSpaceId);
      statement.setString(4, chunkerVersion);
      statement.setInt(5, chunkCount);
      statement.executeUpdate();
    }
  }

  private static void insertChunks(
      java.sql.Connection connection,
      UUID generationId,
      UUID documentId,
      List<IndexedPolicyChunk> chunks)
      throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(INSERT_CHUNK_SQL)) {
      for (IndexedPolicyChunk chunk : chunks) {
        statement.setBytes(1, raw16(UUID.randomUUID()));
        statement.setBytes(2, raw16(documentId));
        statement.setBytes(3, raw16(generationId));
        statement.setInt(4, chunk.chunkNumber());
        statement.setString(5, chunk.content());
        statement.setObject(6, chunk.embedding(), OracleType.VECTOR_FLOAT32);
        statement.executeUpdate();
      }
    }
  }

  private static void activateGeneration(
      java.sql.Connection connection,
      UUID generationId,
      UUID documentId,
      String embeddingSpaceId,
      String chunkerVersion,
      int chunkCount)
      throws java.sql.SQLException {
    try (PreparedStatement statement = connection.prepareStatement(ACTIVATE_GENERATION_SQL)) {
      statement.setBytes(1, raw16(generationId));
      statement.setString(2, embeddingSpaceId);
      statement.setString(3, chunkerVersion);
      statement.setInt(4, chunkCount);
      statement.setBytes(5, raw16(documentId));
      statement.executeUpdate();
    }
  }

  private static byte[] raw16(UUID id) {
    return ByteBuffer.allocate(16)
        .putLong(id.getMostSignificantBits())
        .putLong(id.getLeastSignificantBits())
        .array();
  }
}
