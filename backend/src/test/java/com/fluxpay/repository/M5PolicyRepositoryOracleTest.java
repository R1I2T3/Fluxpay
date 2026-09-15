package com.fluxpay.repository;

import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.beans.M5PolicyChunk;
import com.fluxpay.beans.M5PolicyDocument;
import com.fluxpay.common.util.UuidRawCodec;
import com.fluxpay.config.M5ApiException;
import com.fluxpay.service.M5WorkDeadline;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.AbstractDataSource;

/**
 * Explicit opt-in, pre-migrated/reconciled Oracle only. M5_ORACLE_POLICY_ID must name an existing
 * current policy selected by the operator. No fixtures, DDL, migrations or persistent writes.
 * Repository transaction boundaries become Oracle savepoints inside an always-rolled-back outer
 * transaction. This tests real RAW/CLOB/VECTOR/FK/query/rollback semantics, not cross-session races.
 */
@EnabledIfEnvironmentVariable(named="M5_ORACLE_POLICY_TESTS",matches="true")
class M5PolicyRepositoryOracleTest {
  @Test void nativeVectorsSwitchSpacesAndFailedPublicationPreservesPreviousGeneration() throws Exception {
    UUID id=UUID.fromString(System.getenv("M5_ORACLE_POLICY_ID"));
    try(Connection connection=DriverManager.getConnection(System.getenv("ORACLE_JDBC_URL"),System.getenv("ORACLE_USERNAME"),System.getenv("ORACLE_PASSWORD"))) {
      connection.setAutoCommit(false);
      var failPointer=new AtomicBoolean();
      var repo=new M5PolicyRepository(new SavepointSource(connection,failPointer));
      try {
        repo.requireReady(deadline());
        M5PolicyDocument original=repo.find(id,deadline()).orElseThrow();
        String space="oracle-acceptance-"+UUID.randomUUID();
        float[] vector=new float[768];vector[0]=1;
        UUID firstGeneration=UUID.randomUUID();
        var first=repo.publish(original,chunks(original,firstGeneration,vector),space,"oracle-acceptance-v1",deadline());
        assertEquals(firstGeneration,repo.find(id,deadline()).orElseThrow().activeGenerationId());
        var matches=repo.search(vector,space,5,0.000001,deadline());
        assertEquals(1,matches.size());assertEquals(id,matches.get(0).policyDocumentId());
        assertEquals(original.content(),matches.get(0).content());assertEquals(0,matches.get(0).distance(),0.000001);
        assertEquals(firstGeneration,repo.publish(original,chunks(original,UUID.randomUUID(),vector),space,"oracle-acceptance-v1",deadline()).activeGenerationId());

        UUID failedGeneration=UUID.randomUUID();failPointer.set(true);
        assertThrows(M5ApiException.class,()->repo.publish(first,chunks(first,failedGeneration,vector),space+"-new","oracle-acceptance-v1",deadline()));
        failPointer.set(false);
        assertEquals(firstGeneration,repo.find(id,deadline()).orElseThrow().activeGenerationId());
        assertEquals(1,repo.search(vector,space,5,0.000001,deadline()).size());
        try(var ps=connection.prepareStatement("SELECT COUNT(*) FROM policy_generations WHERE id = ?")) {
          ps.setBytes(1,UuidRawCodec.toBytes(failedGeneration));try(var rs=ps.executeQuery()) {rs.next();assertEquals(0,rs.getInt(1));}
        }

        var second=repo.publish(first,chunks(first,UUID.randomUUID(),vector),space+"-new","oracle-acceptance-v1",deadline());
        assertNotEquals(firstGeneration,second.activeGenerationId());
        assertTrue(repo.search(vector,space,5,0.000001,deadline()).isEmpty());
        assertEquals(id,repo.search(vector,space+"-new",5,0.000001,deadline()).get(0).policyDocumentId());
      } finally {connection.rollback();}
    }
  }
  private static List<M5PolicyChunk> chunks(M5PolicyDocument doc,UUID generation,float[] vector) {
    return List.of(new M5PolicyChunk(UUID.randomUUID(),doc.id(),generation,1,doc.content(),vector));
  }
  private static M5WorkDeadline deadline() {return new M5WorkDeadline(Duration.ofSeconds(30),"INDEX_TIMEOUT");}

  /** Test-only nested transactions; the physical connection is never committed or closed by repo. */
  private static final class SavepointSource extends AbstractDataSource {
    private final Connection physical;
    private final AtomicBoolean failPointer;
    SavepointSource(Connection physical,AtomicBoolean failPointer) {this.physical=physical;this.failPointer=failPointer;}
    @Override public Connection getConnection() throws SQLException {
      Savepoint savepoint=physical.setSavepoint();
      return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)->{
        if(method.getName().equals("close") || method.getName().equals("commit")) return null;
        if(method.getName().equals("rollback") && (args==null || args.length==0)) {physical.rollback(savepoint);return null;}
        if(method.getName().equals("setAutoCommit")) {if(Boolean.TRUE.equals(args[0])) throw new SQLException("Acceptance test forbids committing");return null;}
        if(method.getName().equals("prepareStatement") && failPointer.get() && args[0].toString().startsWith("UPDATE policy_documents SET active_generation_id"))
          throw new SQLException("Injected publication failure after real Oracle generation/chunk inserts");
        try {return method.invoke(physical,args);} catch(InvocationTargetException e) {throw e.getCause();}
      });
    }
    @Override public Connection getConnection(String username,String password) throws SQLException {return getConnection();}
  }
}
