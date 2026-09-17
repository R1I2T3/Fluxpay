package com.fluxpay.adapter.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fluxpay.dto.IndexedPolicyChunk;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.UUID;
import oracle.jdbc.OracleType;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

class OraclePolicyIndexStoreTest {

  @Test
  @SuppressWarnings("unchecked")
  void publishesChunksThenMakesTheirGenerationTheDocumentActive() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement generationStatement = mock(PreparedStatement.class);
    PreparedStatement chunkStatement = mock(PreparedStatement.class);
    PreparedStatement documentStatement = mock(PreparedStatement.class);
    UUID documentId = UUID.fromString("0b36e53d-3a5e-42c7-b83a-d2d66f8fbd22");
    float[] vector = new float[] {0.5f, 0.25f};
    when(jdbcTemplate.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
    when(connection.prepareStatement(any(String.class)))
        .thenReturn(generationStatement, chunkStatement, documentStatement);

    new OraclePolicyIndexStore(jdbcTemplate)
        .publish(
            documentId,
            "ollama/qwen3-embedding:4b/1536",
            "m5-sentence-v1",
            List.of(new IndexedPolicyChunk(1, "Verify KYC.", vector)));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(connection, org.mockito.Mockito.times(3)).prepareStatement(sql.capture());
    assertThat(sql.getAllValues())
        .anySatisfy(value -> assertThat(value).contains("INSERT INTO policy_generations"))
        .anySatisfy(value -> assertThat(value).contains("INSERT INTO policy_chunks"))
        .anySatisfy(value -> assertThat(value).contains("active_generation_id"));
    verify(chunkStatement).setObject(eq(6), same(vector), eq(OracleType.VECTOR_FLOAT32));
    verify(chunkStatement).setInt(4, 1);
    verify(chunkStatement).setString(5, "Verify KYC.");
    InOrder writes = inOrder(generationStatement, chunkStatement, documentStatement);
    writes.verify(generationStatement).executeUpdate();
    writes.verify(chunkStatement).executeUpdate();
    writes.verify(documentStatement).executeUpdate();
  }

  @Test
  @SuppressWarnings("unchecked")
  void clearsActiveGenerationThenDeletesChunksAndGenerationsForAPolicy() throws Exception {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    Connection connection = mock(Connection.class);
    PreparedStatement clearDocument = mock(PreparedStatement.class);
    PreparedStatement deleteChunks = mock(PreparedStatement.class);
    PreparedStatement deleteGenerations = mock(PreparedStatement.class);
    UUID documentId = UUID.randomUUID();
    when(jdbcTemplate.execute(any(ConnectionCallback.class)))
        .thenAnswer(
            invocation ->
                ((ConnectionCallback<?>) invocation.getArgument(0)).doInConnection(connection));
    when(connection.prepareStatement(any(String.class)))
        .thenReturn(clearDocument, deleteChunks, deleteGenerations);

    new OraclePolicyIndexStore(jdbcTemplate).delete(documentId);

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(connection, org.mockito.Mockito.times(3)).prepareStatement(sql.capture());
    assertThat(sql.getAllValues())
        .containsExactly(
            "UPDATE policy_documents SET active_generation_id = NULL WHERE id = ?",
            "DELETE FROM policy_chunks WHERE policy_document_id = ?",
            "DELETE FROM policy_generations WHERE policy_document_id = ?");
    InOrder deletion = inOrder(clearDocument, deleteChunks, deleteGenerations);
    deletion.verify(clearDocument).executeUpdate();
    deletion.verify(deleteChunks).executeUpdate();
    deletion.verify(deleteGenerations).executeUpdate();
  }
}
