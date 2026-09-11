import java.sql.*;
class _ensure_api_smoke_user {
  public static void main(String[] args) throws Exception {
    try (Connection c = DriverManager.getConnection(System.getenv("ORACLE_JDBC_URL"), System.getenv("ORACLE_USERNAME"), System.getenv("ORACLE_PASSWORD"));
         PreparedStatement s = c.prepareStatement("MERGE INTO users u USING (SELECT HEXTORAW(REPLACE(?, '-', '')) id FROM dual) x ON (u.id=x.id) WHEN NOT MATCHED THEN INSERT (id,email,password_hash,role) VALUES (x.id,'api-smoke@local.test','local-only','CUSTOMER')")) {
      s.setString(1, "11111111-1111-1111-1111-111111111111");
      s.executeUpdate();
      try (PreparedStatement wallet = c.prepareStatement("MERGE INTO wallets w USING (SELECT HEXTORAW(REPLACE(?, '-', '')) id, HEXTORAW(REPLACE(?, '-', '')) user_id FROM dual) x ON (w.id=x.id) WHEN NOT MATCHED THEN INSERT (id,user_id,currency,balance) VALUES (x.id,x.user_id,'USD',1000000)")) {
        wallet.setString(1, "22222222-2222-2222-2222-222222222222");
        wallet.setString(2, "11111111-1111-1111-1111-111111111111");
        wallet.executeUpdate();
      }
      System.out.println("API smoke user ready");
    }
  }
}
