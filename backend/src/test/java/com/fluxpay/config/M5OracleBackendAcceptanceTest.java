package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.common.security.JwtUtil;
import com.fluxpay.service.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

/** Real Oracle/HTTP boot gate. Read-only unless M5_ORACLE_RECONCILE_HASHES=true is explicitly set.
 * Never migrates, seeds, or delivers pending reviews. */
@EnabledIfEnvironmentVariable(named="M5_ORACLE_BACKEND_TESTS",matches="true")
class M5OracleBackendAcceptanceTest {
  @Test void realIntegratedRuntimeUsesSignedHttpAndRealMemberAdapters() throws Exception {
    var app=new SpringApplication(M5BackendApplication.class);
    app.setAdditionalProfiles("m5-risk","m5-backend","m5-m3-integration");
    Map<String,Object> properties=new HashMap<>();
    properties.put("spring.config.name","m5-backend");
    properties.put("server.address","127.0.0.1");properties.put("server.port","0");
    properties.put("spring.datasource.url",System.getenv("ORACLE_JDBC_URL"));
    properties.put("spring.datasource.username",System.getenv("ORACLE_USERNAME"));
    properties.put("spring.datasource.password",System.getenv("ORACLE_PASSWORD"));
    properties.put("fluxpay.jwt-secret",System.getenv("JWT_SECRET"));
    properties.put("spring.datasource.hikari.connection-timeout","3000");
    properties.put("spring.jpa.open-in-view","false");properties.put("spring.jpa.hibernate.ddl-auto","none");
    properties.put("spring.jpa.properties.hibernate.jdbc.time_zone","UTC");
    properties.put("compliance.high-value-thresholds.USD","1000");
    properties.put("compliance.day-zone","Asia/Kolkata");properties.put("compliance.recipient-today-mode","ALL_ATTEMPTS");
    properties.put("compliance.high-risk-countries","RU");properties.put("m5.review-delivery.enabled","false");
    properties.put("m5.integration.system-user-id","5f000000-0000-0000-0000-000000000002");
    properties.put("fluxpay.fx-provider-url","https://api.frankfurter.dev/v1/latest");
    app.setDefaultProperties(properties);
    try(var context=(ServletWebServerApplicationContext)app.run()) {
      assertInstanceOf(M3M2WalletAdapter.class,context.getBean(M3WalletPort.class));
      assertInstanceOf(M3M2PostingAdapter.class,context.getBean(M3PostingPort.class));
      assertInstanceOf(M3M5ComplianceBridge.class,context.getBean(M3PaymentCompliancePort.class));
      assertInstanceOf(M3M5ReviewDecisionSink.class,context.getBean(M5ReviewDecisionSink.class));
      assertFalse(context.containsBean("m3PaymentTestConfig"));assertFalse(context.containsBean("flyway"));
      assertFalse(context.containsBean("m5ReviewDeliveryConfiguration"));
      String base="http://127.0.0.1:"+context.getWebServer().getPort();
      var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
      for(String path:List.of("/api/compliance/cases","/api/policies","/api/copilot/ask","/api/payments")) {
        var unsigned=client.send(HttpRequest.newBuilder(URI.create(base+path))
            .header("X-Local-User-Id",UUID.randomUUID().toString()).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(401,unsigned.statusCode(),path);
      }
      String token=context.getBean(JwtUtil.class).generate(UUID.randomUUID(),"local-acceptance@example.invalid","ADMIN");
      // An unknown payment must fail in the authorized real JDBC context lookup, before any
      // provider call. Also exercises the post-acquisition budget on the JPA-bound connection.
      var unknownContext=client.send(HttpRequest.newBuilder(URI.create(base+"/api/copilot/ask"))
          .header("Authorization","Bearer "+token).header("Content-Type","application/json")
          .timeout(Duration.ofSeconds(6)).POST(HttpRequest.BodyPublishers.ofString(
              "{\"question\":\"Why is this payment reviewed?\",\"paymentId\":\""+UUID.randomUUID()+"\"}")).build(),
          HttpResponse.BodyHandlers.ofString());
      assertEquals(404,unknownContext.statusCode());assertTrue(unknownContext.body().contains("CASE_NOT_FOUND"));
      if("true".equals(System.getenv("M5_ORACLE_RECONCILE_HASHES"))) {
        var reconcile=HttpRequest.newBuilder(URI.create(base+"/api/policies/reconcile-hashes"))
            .header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(35))
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        var first=client.send(reconcile,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,first.statusCode(),"Explicit canonical reconciliation failed");
        var second=client.send(reconcile,HttpResponse.BodyHandlers.ofString());
        assertEquals(200,second.statusCode());assertTrue(second.body().contains("\"updatedDocuments\":0"));
      }
      var cases=client.send(HttpRequest.newBuilder(URI.create(base+"/api/compliance/cases"))
          .header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,cases.statusCode());assertTrue(cases.body().contains("\"data\""));
      var policies=client.send(HttpRequest.newBuilder(URI.create(base+"/api/policies"))
          .header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertTrue(policies.statusCode()==200 || (policies.statusCode()==503 && policies.body().contains("POLICY_CORPUS_UNRECONCILED")));
      var payments=client.send(HttpRequest.newBuilder(URI.create(base+"/api/payments"))
          .header("Authorization","Bearer "+token).timeout(Duration.ofSeconds(5)).GET().build(),HttpResponse.BodyHandlers.ofString());
      assertEquals(200,payments.statusCode());
    }
  }
}
