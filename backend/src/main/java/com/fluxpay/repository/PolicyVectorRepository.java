package com.fluxpay.repository;

import com.fluxpay.common.util.UuidRawCodec;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import oracle.jdbc.OracleType;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Native JDBC access to the {@code embedding} VECTOR column on {@code policy_chunks}.
 *
 * <p>Hibernate 6.4 (the version that ships with Spring Boot 3.2.5) has no built-in mapping for
 * Oracle 23ai's VECTOR type. Rather than bolt on a fragile custom Hibernate {@code UserType}, this
 * talks to the column directly through the Oracle JDBC driver (ojdbc11 23.4+), which supports
 * binding a {@code float[]} via {@link OracleType#VECTOR_FLOAT32} and reading it back the same
 * way. This keeps the {@code PolicyChunk} JPA entity mapping -- and {@code ddl-auto: validate} --
 * completely untouched; embeddings are set/read only through this class.
 *
 * <p>If a driver version doesn't expose {@code OracleType.VECTOR_FLOAT32}, fall back to the
 * generic {@code OracleType.VECTOR}.
 */
@org.springframework.context.annotation.Profile("m5-legacy")
@Repository
public class PolicyVectorRepository {

  private final JdbcTemplate jdbcTemplate;

  public PolicyVectorRepository(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void saveEmbedding(UUID chunkId, float[] embedding) {
    jdbcTemplate.execute(
        (ConnectionCallback<Void>)
            con -> {
              try (PreparedStatement ps =
                  con.prepareStatement("UPDATE policy_chunks SET embedding = ? WHERE id = ?")) {
                ps.setObject(1, embedding, OracleType.VECTOR_FLOAT32);
                ps.setBytes(2, UuidRawCodec.toBytes(chunkId));
                ps.executeUpdate();
              }
              return null;
            });
  }

  /** Top-{@code limit} nearest chunks to {@code queryEmbedding} by cosine distance. */
  public List<ChunkMatch> findNearest(float[] queryEmbedding, int limit) {
    String sql =
        "SELECT pc.id AS chunk_id, pd.id AS document_id, pd.title, pc.chunk_number, pc.content, "
            + "VECTOR_DISTANCE(pc.embedding, ?, COSINE) AS distance "
            + "FROM policy_chunks pc "
            + "JOIN policy_documents pd ON pd.id = pc.policy_document_id "
            + "WHERE pc.embedding IS NOT NULL "
            + "ORDER BY VECTOR_DISTANCE(pc.embedding, ?, COSINE) "
            + "FETCH FIRST ? ROWS ONLY";
    return jdbcTemplate.execute(
        (ConnectionCallback<List<ChunkMatch>>)
            con -> {
              try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setObject(1, queryEmbedding, OracleType.VECTOR_FLOAT32);
                ps.setObject(2, queryEmbedding, OracleType.VECTOR_FLOAT32);
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                  List<ChunkMatch> matches = new ArrayList<>();
                  while (rs.next()) {
                    matches.add(
                        new ChunkMatch(
                            UuidRawCodec.fromBytes(rs.getBytes("chunk_id")),
                            UuidRawCodec.fromBytes(rs.getBytes("document_id")),
                            rs.getString("title"),
                            rs.getInt("chunk_number"),
                            rs.getString("content"),
                            rs.getDouble("distance")));
                  }
                  return matches;
                }
              }
            });
  }

  public record ChunkMatch(
      UUID chunkId,
      UUID documentId,
      String title,
      int chunkNumber,
      String content,
      double distance) {}
}
