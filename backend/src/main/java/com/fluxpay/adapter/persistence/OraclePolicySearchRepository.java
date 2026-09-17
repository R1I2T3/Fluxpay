package com.fluxpay.adapter.persistence;

import com.fluxpay.common.contracts.PolicySearchPort;
import com.fluxpay.dto.PolicyMatch;
import java.nio.ByteBuffer;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import oracle.jdbc.OracleType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Native Oracle AI Vector Search adapter over the current policy document generation. */
@Repository
public class OraclePolicySearchRepository implements PolicySearchPort {
  private static final String NEAREST_CHUNKS_SQL =
      "SELECT pc.id AS chunk_id, pd.id AS document_id, pd.title, pc.chunk_number, pc.content, "
          + "VECTOR_DISTANCE(pc.embedding, ?, COSINE) AS distance "
          + "FROM policy_chunks pc "
          + "JOIN policy_documents pd ON pd.id = pc.policy_document_id "
          + "WHERE pc.embedding IS NOT NULL "
          + "AND pd.active_generation_id IS NOT NULL "
          + "AND pc.generation_id = pd.active_generation_id "
          + "AND pd.index_state = 'INDEXED' "
          + "AND pd.embedding_space_id = ? "
          + "ORDER BY VECTOR_DISTANCE(pc.embedding, ?, COSINE) "
          + "FETCH FIRST ? ROWS ONLY";

  private final JdbcTemplate jdbcTemplate;

  public OraclePolicySearchRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public List<PolicyMatch> search(float[] queryEmbedding, String embeddingSpaceId, int limit) {
    if (queryEmbedding == null || queryEmbedding.length == 0) {
      throw new IllegalArgumentException("Policy-search query embedding is required");
    }
    if (embeddingSpaceId == null || embeddingSpaceId.isBlank()) {
      throw new IllegalArgumentException("Policy-search embedding space is required");
    }
    if (limit <= 0) {
      throw new IllegalArgumentException("Policy-search limit must be positive");
    }
    return jdbcTemplate.execute(
        (ConnectionCallback<List<PolicyMatch>>)
            connection -> {
              try (PreparedStatement statement = connection.prepareStatement(NEAREST_CHUNKS_SQL)) {
                statement.setObject(1, queryEmbedding, OracleType.VECTOR_FLOAT32);
                statement.setString(2, embeddingSpaceId);
                statement.setObject(3, queryEmbedding, OracleType.VECTOR_FLOAT32);
                statement.setInt(4, limit);
                try (ResultSet resultSet = statement.executeQuery()) {
                  List<PolicyMatch> matches = new ArrayList<>();
                  while (resultSet.next()) {
                    matches.add(
                        new PolicyMatch(
                            fromRaw16(resultSet.getBytes("chunk_id")),
                            fromRaw16(resultSet.getBytes("document_id")),
                            resultSet.getString("title"),
                            resultSet.getInt("chunk_number"),
                            resultSet.getString("content"),
                            resultSet.getDouble("distance")));
                  }
                  return List.copyOf(matches);
                }
              }
            });
  }

  private static UUID fromRaw16(byte[] bytes) {
    if (bytes == null || bytes.length != 16) {
      throw new IllegalStateException(
          "Oracle policy search returned an invalid RAW(16) identifier");
    }
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    return new UUID(buffer.getLong(), buffer.getLong());
  }
}
