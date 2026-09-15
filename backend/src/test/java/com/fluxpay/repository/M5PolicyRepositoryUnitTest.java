package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fluxpay.beans.*;
import com.fluxpay.common.util.UuidRawCodec;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.service.M5WorkDeadline;
import java.sql.*;
import java.time.*;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/** JDBC boundary tests; real Oracle locking/vector semantics remain an acceptance gate. */
class M5PolicyRepositoryUnitTest {
  final DataSource source=mock(DataSource.class);
  final Connection connection=mock(Connection.class);
  final List<String> sql=new ArrayList<>();
  final PreparedStatement query=mock(PreparedStatement.class), write=mock(PreparedStatement.class);
  final ResultSet rows=mock(ResultSet.class);
  final M5PolicyRepository repo=new M5PolicyRepository(source);
  final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000001");
  final UUID generation=UUID.fromString("00000000-0000-0000-0000-000000000002");
  M5PolicyRepositoryUnitTest() throws Exception {
    when(source.getConnection()).thenReturn(connection);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.prepareStatement(anyString())).thenAnswer(i->{
      String s=i.getArgument(0); sql.add(s);
      return s.startsWith("SELECT") ? query : write;
    });
    when(query.executeQuery()).thenReturn(rows);
    when(write.executeUpdate()).thenReturn(1);
  }
  M5WorkDeadline deadline() {return new M5WorkDeadline(Duration.ofSeconds(30),"INDEX_TIMEOUT");}
  void documentRow(long version, UUID active,String space,String chunker) throws Exception {
    when(rows.next()).thenReturn(true,false);
    when(rows.getBytes("id")).thenReturn(UuidRawCodec.toBytes(id));
    when(rows.getString("title")).thenReturn("Policy"); when(rows.getString("category")).thenReturn("KYC");
    when(rows.getString("content")).thenReturn("policy"); when(rows.getString("canonical_hash")).thenReturn("canonical");
    when(rows.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.EPOCH));
    when(rows.getLong("document_version")).thenReturn(version);
    when(rows.getString("index_state")).thenReturn(active==null?"UNINDEXED":"INDEXED");
    when(rows.getBytes("active_generation_id")).thenReturn(active==null?null:UuidRawCodec.toBytes(active));
    when(rows.getString("embedding_space_id")).thenReturn(space);
    when(rows.getString("chunker_version")).thenReturn(chunker);
    when(rows.getInt("chunk_count")).thenReturn(active==null?0:1);
  }
  M5PolicyDocument captured() {return new M5PolicyDocument(id,"Policy","KYC","policy","original",Instant.EPOCH,0,"UNINDEXED",null,null,null,0);}
  List<M5PolicyChunk> chunks() {float[] vector=new float[768]; vector[0]=1;return List.of(new M5PolicyChunk(UUID.randomUUID(),id,generation,1,"policy",vector));}
  @Test void findRoundTripsRawUuidAndCanonicalHashAndAppliesStatementTimeout() throws Exception {
    documentRow(0,null,null,null);
    var found=repo.find(id,deadline()).orElseThrow();
    assertEquals(id,found.id());assertEquals("canonical",found.documentHash());assertEquals("policy",found.content());
    verify(query).setBytes(1,UuidRawCodec.toBytes(id));verify(query).setQueryTimeout(intThat(n->n>0&&n<=30));
  }
  @Test void publicationRollsBackOnInsertFailureWithoutPublishingPointer() throws Exception {
    documentRow(0,null,null,null);
    when(write.executeUpdate()).thenThrow(new SQLException("synthetic insert failure"));
    assertThrows(M5ApiException.class,()->repo.publish(captured(),chunks(),"space","chunker",deadline()));
    verify(connection).rollback();verify(connection,never()).commit();
    assertTrue(sql.stream().noneMatch(s->s.startsWith("UPDATE policy_documents")));
  }
  @Test void concurrentMatchingWinnerReplaysWithoutNewGeneration() throws Exception {
    documentRow(1,generation,"space","chunker");
    var result=repo.publish(captured(),chunks(),"space","chunker",deadline());
    assertEquals(generation,result.activeGenerationId());assertEquals(1,result.version());
    assertTrue(sql.stream().noneMatch(s->s.startsWith("INSERT")));
  }
  @Test void incompatibleConcurrentWinnerConflictsWithoutMutation() throws Exception {
    documentRow(1,generation,"other","chunker");
    assertEquals("INDEX_CONFLICT",assertThrows(M5ApiException.class,()->repo.publish(captured(),chunks(),"space","chunker",deadline())).code());
    verify(connection).rollback();assertTrue(sql.stream().noneMatch(s->s.startsWith("INSERT")));
  }
  @Test void successfulPublicationInsertsGenerationAndChunksBeforeActivePointer() throws Exception {
    documentRow(0,null,null,null);
    var result=repo.publish(captured(),chunks(),"space","chunker",deadline());
    assertEquals(1,result.version());assertEquals(generation,result.activeGenerationId());
    assertTrue(sql.get(0).contains("FOR UPDATE WAIT"));
    assertTrue(sql.get(1).startsWith("INSERT INTO policy_generations"));
    assertTrue(sql.get(2).startsWith("INSERT INTO policy_chunks"));
    assertTrue(sql.get(3).startsWith("UPDATE policy_documents"));
    verify(connection).commit();verify(connection,never()).rollback();
  }
  @Test void canonicalDuplicatesAbortBeforeAnyHashWrite() throws Exception {
    when(rows.next()).thenReturn(true,true,false);
    when(rows.getBytes("id")).thenReturn(UuidRawCodec.toBytes(id),UuidRawCodec.toBytes(generation));
    when(rows.getString("content")).thenReturn(" policy\r\n","policy");
    assertEquals("CANONICAL_HASH_COLLISION",assertThrows(M5ApiException.class,()->repo.reconcileCanonicalHashes(deadline())).code());
    verify(connection).rollback(); assertTrue(sql.stream().noneMatch(s->s.startsWith("UPDATE")));
  }
  @Test void canonicalReconciliationPreservesOriginalEvidenceAndIsIdempotent() throws Exception {
    when(rows.next()).thenReturn(true,false);
    when(rows.getBytes("id")).thenReturn(UuidRawCodec.toBytes(id));
    when(rows.getString("content")).thenReturn("policy");
    assertEquals(1,repo.reconcileCanonicalHashes(deadline()));
    assertTrue(sql.stream().anyMatch(s->s.startsWith("LOCK TABLE policy_documents")&&s.contains("WAIT")));
    assertTrue(sql.stream().filter(s->s.startsWith("UPDATE")).allMatch(s->s.startsWith("UPDATE policy_documents SET canonical_hash = ? WHERE id = ?")));
    verify(connection).commit();
    reset(rows); when(rows.next()).thenReturn(true,false);when(rows.getBytes("id")).thenReturn(UuidRawCodec.toBytes(id));
    when(rows.getString("content")).thenReturn("policy");
    when(rows.getString("canonical_hash")).thenReturn("823412d1eacb67956220e532959f0104603057c88704863ca38e7cd188fda812");
    assertEquals(0,repo.reconcileCanonicalHashes(deadline()));
  }
  @Test void readinessRejectsUnreconciledCorpus() throws Exception {
    when(rows.next()).thenReturn(true);when(rows.getLong(1)).thenReturn(1L);
    assertEquals("POLICY_CORPUS_UNRECONCILED",assertThrows(M5ApiException.class,()->repo.requireReady(deadline())).code());
  }
  @Test void searchBindsVectorAndFiltersManagedActiveCurrentSpaceWithStableOrderAndCutoff() throws Exception {
    when(rows.next()).thenReturn(true,false);
    float[] vector=new float[768];vector[0]=1;
    assertTrue(repo.search(vector,"space",3,.35,deadline()).isEmpty());
    String s=sql.get(sql.size()-1);
    assertTrue(s.contains("VECTOR_DISTANCE")&&s.contains("COSINE"));
    assertTrue(s.contains("active_generation_id")&&s.contains("superseded_by_id IS NULL"));
    assertTrue(s.contains("embedding_space_id = ?")&&s.contains("distance <= ?"));
    assertTrue(s.contains("ORDER BY distance, policy_document_id, chunk_number"));
    verify(query).setString(2,"space");verify(query).setDouble(3,.35);verify(query).setInt(4,3);
  }
  @Test void concurrentDuplicateCreateReturnsWinningDocumentIdAfterRollback() throws Exception {
    documentRow(0,null,null,null);
    when(write.executeUpdate()).thenThrow(new SQLException("unique canonical key","23000",1));
    var error=assertThrows(M5ApiException.class,()->repo.create(captured(),deadline()));
    assertEquals("DUPLICATE_POLICY",error.code());assertEquals(id.toString(),error.fieldErrors().get("existingPolicyDocumentId"));
    verify(connection).rollback();verify(connection,never()).commit();
  }
  @Test void pointerPublicationFailureRollsBackAlreadyInsertedGenerationAndChunks() throws Exception {
    documentRow(0,null,null,null);
    when(write.executeUpdate()).thenReturn(1,1,0);
    assertEquals("INDEX_CONFLICT",assertThrows(M5ApiException.class,()->repo.publish(captured(),chunks(),"space","chunker",deadline())).code());
    verify(connection).rollback();verify(connection,never()).commit();
    assertEquals(2,sql.stream().filter(s->s.startsWith("INSERT")).count());
    assertTrue(sql.stream().noneMatch(s->s.startsWith("DELETE")));
  }
  @Test void deadlineReachedAfterPointerUpdateStillRollsBackBeforeCommit() throws Exception {
    documentRow(0,null,null,null);
    var time=new java.util.concurrent.atomic.AtomicLong();
    var deadline=new M5WorkDeadline(Duration.ofSeconds(30),"INDEX_TIMEOUT",time::get);
    when(write.executeUpdate()).thenReturn(1,1).thenAnswer(i->{time.set(Duration.ofSeconds(31).toNanos());return 1;});
    assertEquals("INDEX_TIMEOUT",assertThrows(M5ApiException.class,()->repo.publish(captured(),chunks(),"space","chunker",deadline)).code());
    verify(connection).rollback();verify(connection,never()).commit();
  }
  @Test void canonicalSecondWriteFailureRollsBackEntireReconciliation() throws Exception {
    when(rows.next()).thenReturn(true,true,false);
    when(rows.getBytes("id")).thenReturn(UuidRawCodec.toBytes(id),UuidRawCodec.toBytes(generation));
    when(rows.getString("content")).thenReturn("first policy","second policy");
    when(write.executeUpdate()).thenReturn(1,1).thenThrow(new SQLException("second canonical write failed"));
    assertThrows(M5ApiException.class,()->repo.reconcileCanonicalHashes(deadline()));
    verify(connection).rollback();verify(connection,never()).commit();
    assertEquals(2,sql.stream().filter(s->s.startsWith("UPDATE")).count());
  }
  @Test void failedRollbackNeverRestoresAutoCommitAndAbortsUnsafeConnection() throws Exception {
    documentRow(0,null,null,null);
    when(write.executeUpdate()).thenReturn(1,1).thenThrow(new SQLException("pointer publication failed"));
    doThrow(new SQLException("rollback failed")).when(connection).rollback();
    assertThrows(M5ApiException.class,()->repo.publish(captured(),chunks(),"space","chunker",deadline()));
    verify(connection,never()).setAutoCommit(true);
    verify(connection).abort(any(java.util.concurrent.Executor.class));
    verify(connection,never()).commit();
  }
}
