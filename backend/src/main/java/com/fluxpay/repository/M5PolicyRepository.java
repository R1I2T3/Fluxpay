package com.fluxpay.repository;
import com.fluxpay.beans.M5PolicyDocument;
import com.fluxpay.beans.M5PolicyChunk;
import com.fluxpay.dto.M5PolicyDtos;
import com.fluxpay.service.M5WorkDeadline;
import com.fluxpay.service.M5PolicyChunker;
import com.fluxpay.common.util.UuidRawCodec;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.config.M5EmbeddingAdapter;
import java.io.StringReader;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import oracle.jdbc.OracleType;
import org.springframework.stereotype.Repository;
@Repository
public class M5PolicyRepository {
  private static final String COLUMNS="id, title, category, content, canonical_hash, created_at, document_version, index_state, active_generation_id, embedding_space_id, chunker_version, chunk_count";
  private final DataSource source;
  public M5PolicyRepository(DataSource source) {this.source=source;}

  /** Reads only: boot and the explicit reconciliation endpoint remain usable before this gate passes. */
  public void requireReady(M5WorkDeadline deadline) {
    read(deadline,c->{
      try(var ps=statement(c,"SELECT COUNT(*) FROM policy_documents WHERE canonical_hash IS NULL",deadline);var rs=ps.executeQuery()) {
        if(!rs.next() || rs.getLong(1)>0) throw new M5ApiException(503,"POLICY_CORPUS_UNRECONCILED","An administrator must explicitly reconcile canonical policy hashes");
      }
      return null;
    });
  }

  /** Serialize against all corpus DML; compute and collision-check every row before the first write. */
  public int reconcileCanonicalHashes(M5WorkDeadline deadline) {
    return transaction(deadline,c->{
      try(var lock=statement(c,"LOCK TABLE policy_documents IN SHARE ROW EXCLUSIVE MODE WAIT "+lockWait(deadline),deadline)) {lock.executeUpdate();}
      var hashes=new HashMap<String,UUID>();var changed=new ArrayList<HashUpdate>();
      try(var ps=statement(c,"SELECT id, content, canonical_hash FROM policy_documents ORDER BY id",deadline);var rs=ps.executeQuery()) {
        while(rs.next()) {
          deadline.check();UUID id=uuid(rs.getBytes("id"));String hash=M5PolicyChunker.hash(M5PolicyChunker.normalize(rs.getString("content")));
          UUID collision=hashes.putIfAbsent(hash,id);
          if(collision!=null) throw new M5ApiException(409,"CANONICAL_HASH_COLLISION","Canonical policy content collides; no documents were changed",Map.of("firstPolicyDocumentId",collision.toString(),"secondPolicyDocumentId",id.toString()));
          String stored=rs.getString("canonical_hash");
          if(stored!=null && !hash.equals(stored)) throw new M5ApiException(409,"CANONICAL_HASH_MISMATCH","Stored canonical evidence differs from immutable content; no documents were changed",Map.of("policyDocumentId",id.toString()));
          if(stored==null) changed.add(new HashUpdate(id,hash));
        }
      }
      for(var update:changed) {
        try(var ps=statement(c,"UPDATE policy_documents SET canonical_hash = ? WHERE id = ? AND canonical_hash IS NULL",deadline)) {
          ps.setString(1,update.hash());ps.setBytes(2,raw(update.id()));
          if(ps.executeUpdate()!=1) throw new SQLException("Canonical reconciliation update count was not one");
        }
      }
      return changed.size();
    });
  }
  public Optional<M5PolicyDocument> find(UUID id, M5WorkDeadline deadline) {
    return read(deadline,c->find(c,"id",raw(id),deadline));
  }
  public Optional<M5PolicyDocument> findByHash(String hash, M5WorkDeadline deadline) {
    return read(deadline,c->find(c,"canonical_hash",hash,deadline));
  }
  private Optional<M5PolicyDocument> find(Connection c,String column,Object key,M5WorkDeadline deadline) throws SQLException {
    try(var ps=statement(c,"SELECT "+COLUMNS+" FROM policy_documents WHERE "+column+" = ?",deadline)) {
      if(key instanceof byte[] bytes) ps.setBytes(1,bytes);else ps.setString(1,(String)key);
      try(var rs=ps.executeQuery()) {return rs.next()?Optional.of(document(rs)):Optional.empty();}
    }
  }
  public M5PolicyDocument create(M5PolicyDocument doc, M5WorkDeadline deadline) {
    try {
      return transaction(deadline,c->{
        try(var ps=statement(c,"INSERT INTO policy_documents (id, title, category, content, document_hash, canonical_hash, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",deadline)) {
          ps.setBytes(1,raw(doc.id()));ps.setString(2,doc.title());ps.setString(3,doc.category());
          ps.setCharacterStream(4,new StringReader(doc.content()),doc.content().length());
          ps.setString(5,doc.documentHash());ps.setString(6,doc.documentHash());ps.setTimestamp(7,Timestamp.from(doc.createdAt()));
          if(ps.executeUpdate()!=1) throw new SQLException("Policy insert count was not one");
        }
        return doc;
      });
    } catch(M5ApiException e) {
      if(e.getCause() instanceof SQLException sql && sql.getErrorCode()==1) {
        var existing=findByHash(doc.documentHash(),deadline);
        if(existing.isPresent()) throw new M5ApiException(409,"DUPLICATE_POLICY","Normalized policy content already exists",Map.of("existingPolicyDocumentId",existing.get().id().toString()));
      }
      throw e;
    }
  }
  public M5PolicyDtos.Page list(int page,int size,M5WorkDeadline deadline) {
    if(page<0 || size<1 || size>100) throw new M5ApiException(400,"VALIDATION","Invalid policy page");
    return read(deadline,c->{
      long total;
      try(var ps=statement(c,"SELECT COUNT(*) FROM policy_documents",deadline);var rs=ps.executeQuery()) {rs.next();total=rs.getLong(1);}
      var documents=new ArrayList<M5PolicyDocument>();
      try(var ps=statement(c,"SELECT "+COLUMNS+" FROM policy_documents ORDER BY created_at, id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",deadline)) {
        ps.setLong(1,(long)page*size);ps.setInt(2,size);
        try(var rs=ps.executeQuery()) {while(rs.next()) documents.add(document(rs));}
      }
      return new M5PolicyDtos.Page(List.copyOf(documents),page,size,total,(total+size-1)/size);
    });
  }
  public M5PolicyDocument publish(M5PolicyDocument captured,List<M5PolicyChunk> chunks,String space,String chunker,M5WorkDeadline deadline) {
    validateChunks(captured,chunks,space,chunker);
    return transaction(deadline,c->{
      M5PolicyDocument locked;
      try(var ps=statement(c,"SELECT "+COLUMNS+", superseded_by_id FROM policy_documents WHERE id = ? FOR UPDATE WAIT "+lockWait(deadline),deadline)) {
        ps.setBytes(1,raw(captured.id()));
        try(var rs=ps.executeQuery()) {
          if(!rs.next()) throw new M5ApiException(404,"POLICY_NOT_FOUND","Policy document was not found");
          if(rs.getBytes("superseded_by_id")!=null) throw new M5ApiException(409,"INDEX_CONFLICT","Superseded policy cannot be published as current evidence");
          locked=document(rs);
        }
      }
      if(locked.activeGenerationId()!=null && "INDEXED".equals(locked.indexState()) && space.equals(locked.embeddingSpaceId()) && chunker.equals(locked.chunkerVersion())) return locked;
      if(locked.version()!=captured.version() || !Objects.equals(locked.activeGenerationId(),captured.activeGenerationId()))
        throw new M5ApiException(409,"INDEX_CONFLICT","Another index generation was published; capture the current document again");
      UUID generation=chunks.get(0).generationId();
      try(var ps=statement(c,"INSERT INTO policy_generations (id, policy_document_id, embedding_space_id, chunker_version, chunk_count) VALUES (?, ?, ?, ?, ?)",deadline)) {
        ps.setBytes(1,raw(generation));ps.setBytes(2,raw(captured.id()));ps.setString(3,space);ps.setString(4,chunker);ps.setInt(5,chunks.size());
        if(ps.executeUpdate()!=1) throw new SQLException("Generation insert count was not one");
      }
      for(var chunk:chunks) {
        try(var ps=statement(c,"INSERT INTO policy_chunks (id, policy_document_id, generation_id, chunk_number, content, embedding) VALUES (?, ?, ?, ?, ?, ?)",deadline)) {
          ps.setBytes(1,raw(chunk.id()));ps.setBytes(2,raw(captured.id()));ps.setBytes(3,raw(generation));ps.setInt(4,chunk.chunkNumber());
          ps.setCharacterStream(5,new StringReader(chunk.content()),chunk.content().length());ps.setObject(6,chunk.embedding(),OracleType.VECTOR_FLOAT32);
          if(ps.executeUpdate()!=1) throw new SQLException("Chunk insert count was not one");
        }
      }
      try(var ps=statement(c,"UPDATE policy_documents SET active_generation_id = ?, document_version = document_version + 1, index_state = 'INDEXED', embedding_space_id = ?, chunker_version = ?, chunk_count = ? WHERE id = ? AND document_version = ?",deadline)) {
        ps.setBytes(1,raw(generation));ps.setString(2,space);ps.setString(3,chunker);ps.setInt(4,chunks.size());ps.setBytes(5,raw(captured.id()));ps.setLong(6,captured.version());
        if(ps.executeUpdate()!=1) throw new M5ApiException(409,"INDEX_CONFLICT","Document changed during publication");
      }
      return new M5PolicyDocument(locked.id(),locked.title(),locked.category(),locked.content(),locked.documentHash(),locked.createdAt(),locked.version()+1,"INDEXED",generation,space,chunker,chunks.size());
    });
  }
  public List<M5PolicyDtos.Match> search(float[] vector,String space,int topK,double maxDistance,M5WorkDeadline deadline) {
    M5EmbeddingAdapter.validate(vector,768);
    if(space==null || space.isBlank() || topK<1 || topK>5 || !Double.isFinite(maxDistance) || maxDistance<0 || maxDistance>2)
      throw new M5ApiException(400,"VALIDATION","Invalid policy retrieval settings");
    requireReady(deadline);
    String sql="SELECT policy_document_id, title, chunk_number, content, distance FROM ("
        +"SELECT pd.id AS policy_document_id, pd.title, pc.chunk_number, pc.content, VECTOR_DISTANCE(pc.embedding, ?, COSINE) AS distance "
        +"FROM policy_documents pd JOIN policy_generations pg ON pg.policy_document_id = pd.id AND pg.id = pd.active_generation_id "
        +"JOIN policy_chunks pc ON pc.policy_document_id = pd.id AND pc.generation_id = pg.id "
        +"WHERE pd.index_state = 'INDEXED' AND pd.superseded_by_id IS NULL AND pd.canonical_hash IS NOT NULL "
        +"AND pg.embedding_space_id = ? AND pd.embedding_space_id = pg.embedding_space_id "
        +"AND pd.chunker_version = pg.chunker_version AND pd.chunk_count = pg.chunk_count AND pc.embedding IS NOT NULL) "
        +"WHERE distance <= ? ORDER BY distance, policy_document_id, chunk_number FETCH FIRST ? ROWS ONLY";
    return read(deadline,c->{
      var matches=new ArrayList<M5PolicyDtos.Match>();
      try(var ps=statement(c,sql,deadline)) {
        ps.setObject(1,vector,OracleType.VECTOR_FLOAT32);ps.setString(2,space);ps.setDouble(3,maxDistance);ps.setInt(4,topK);
        try(var rs=ps.executeQuery()) {while(rs.next()) {deadline.check();matches.add(new M5PolicyDtos.Match(uuid(rs.getBytes("policy_document_id")),rs.getString("title"),rs.getInt("chunk_number"),rs.getString("content"),rs.getDouble("distance")));}}
      }
      return List.copyOf(matches);
    });
  }
  private static void validateChunks(M5PolicyDocument doc,List<M5PolicyChunk> chunks,String space,String chunker) {
    if(doc==null || chunks==null || chunks.isEmpty() || chunks.size()>16 || space==null || space.isBlank() || space.length()>200 || chunker==null || chunker.isBlank() || chunker.length()>100)
      throw new M5ApiException(400,"VALIDATION","Invalid index generation");
    UUID generation=chunks.get(0).generationId();
    for(int i=0;i<chunks.size();i++) {
      var chunk=chunks.get(i);
      if(chunk.id()==null || generation==null || !generation.equals(chunk.generationId()) || !doc.id().equals(chunk.policyDocumentId()) || chunk.chunkNumber()!=i+1 || chunk.content()==null || chunk.content().isBlank())
        throw new M5ApiException(400,"VALIDATION","Index generation chunks must be complete and ordered");
      M5EmbeddingAdapter.validate(chunk.embedding(),768);
    }
  }
  private static M5PolicyDocument document(ResultSet rs) throws SQLException {
    return new M5PolicyDocument(uuid(rs.getBytes("id")),rs.getString("title"),rs.getString("category"),rs.getString("content"),rs.getString("canonical_hash"),rs.getTimestamp("created_at").toInstant(),rs.getLong("document_version"),rs.getString("index_state"),uuid(rs.getBytes("active_generation_id")),rs.getString("embedding_space_id"),rs.getString("chunker_version"),rs.getInt("chunk_count"));
  }
  private static PreparedStatement statement(Connection c,String sql,M5WorkDeadline deadline) throws SQLException {
    deadline.check();var ps=c.prepareStatement(sql);
    try {ps.setQueryTimeout(deadline.sqlTimeoutSeconds());return ps;}
    catch(SQLException|RuntimeException e) {ps.close();throw e;}
  }
  private static int lockWait(M5WorkDeadline deadline) {return Math.min(5,deadline.sqlTimeoutSeconds());}
  private <T> T read(M5WorkDeadline deadline,SqlWork<T> work) {
    deadline.check();
    try(var c=source.getConnection()) {T result=work.run(c);deadline.check();return result;}
    catch(SQLException e) {throw databaseFailure(e,deadline);}
  }
  private <T> T transaction(M5WorkDeadline deadline,SqlWork<T> work) {
    deadline.check();
    try(var c=source.getConnection()) {
      boolean auto=c.getAutoCommit();c.setAutoCommit(false);
      boolean restoreAutoCommit=true;
      try {T result=work.run(c);deadline.check();c.commit();return result;}
      catch(SQLException|RuntimeException e) {
        try {c.rollback();}
        catch(SQLException rollback) {
          // Auto-commit restoration could commit unrolled-back publication work. Discard instead.
          restoreAutoCommit=false;e.addSuppressed(rollback);
          try {c.abort(Runnable::run);} catch(SQLException|RuntimeException abort) {e.addSuppressed(abort);}
        }
        throw e;
      }
      finally {if(restoreAutoCommit) c.setAutoCommit(auto);}
    } catch(SQLException e) {throw databaseFailure(e,deadline);}
  }
  private static M5ApiException databaseFailure(SQLException e,M5WorkDeadline deadline) {
    deadline.check();
    var error=new M5ApiException(503,"POLICY_DATABASE_UNAVAILABLE","Policy database operation failed");error.initCause(e);return error;
  }
  private static byte[] raw(UUID id) {return UuidRawCodec.toBytes(id);}
  private static UUID uuid(byte[] bytes) {return bytes==null?null:UuidRawCodec.fromBytes(bytes);}
  private record HashUpdate(UUID id,String hash) {}
  @FunctionalInterface private interface SqlWork<T> {T run(Connection c) throws SQLException;}
}
