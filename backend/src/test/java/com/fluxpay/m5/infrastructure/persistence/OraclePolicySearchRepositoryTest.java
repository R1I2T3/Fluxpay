package com.fluxpay.m5.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;
import oracle.jdbc.OracleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class OraclePolicySearchRepositoryTest {

  @Test
  @SuppressWarnings("unchecked")
  void searchesOnlyTheCurrentIndexedGenerationInTheRequestedVectorSpace() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement statement = mock(PreparedStatement.class);
    ResultSet resultSet = mock(ResultSet.class);
    UUID documentId = UUID.fromString("d64d5f2b-a434-430d-905c-0e5f08640c77");
    UUID chunkId = UUID.fromString("22f3cf50-5a1e-4d35-a373-4d22368a214e");
    float[] query = new float[] {0.25f, 0.5f};

    when(jdbcTemplate.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
    when(connection.prepareStatement(any(String.class))).thenReturn(statement);
    when(statement.executeQuery()).thenReturn(resultSet);
    when(resultSet.next()).thenReturn(true, false);
    when(resultSet.getBytes("document_id")).thenReturn(raw16(documentId));
    when(resultSet.getBytes("chunk_id")).thenReturn(raw16(chunkId));
    when(resultSet.getString("title")).thenReturn("KYC policy");
    when(resultSet.getInt("chunk_number")).thenReturn(2);
    when(resultSet.getString("content")).thenReturn("Verify the customer before release.");
    when(resultSet.getDouble("distance")).thenReturn(0.08d);

    OraclePolicySearchRepository repository = new OraclePolicySearchRepository(jdbcTemplate);

    var matches = repository.search(query, "qwen3-embedding:4b/1536", 5);

    assertThat(matches)
        .singleElement()
        .satisfies(
            match -> {
              assertThat(match.policyDocumentId()).isEqualTo(documentId);
              assertThat(match.policyChunkId()).isEqualTo(chunkId);
              assertThat(match.distance()).isEqualTo(0.08d);
            });
    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(connection).prepareStatement(sql.capture());
    assertThat(sql.getValue())
        .contains("pc.generation_id = pd.active_generation_id")
        .contains("pd.index_state = 'INDEXED'")
        .contains("pd.embedding_space_id = ?");
    verify(statement).setObject(eq(1), same(query), eq(OracleType.VECTOR_FLOAT32));
    verify(statement).setObject(eq(2), same(query), eq(OracleType.VECTOR_FLOAT32));
    verify(statement).setString(3, "qwen3-embedding:4b/1536");
    verify(statement).setInt(4, 5);
  }

  private static byte[] raw16(UUID id) {
    return ByteBuffer.allocate(16)
        .putLong(id.getMostSignificantBits())
        .putLong(id.getLeastSignificantBits())
        .array();
  }
}
