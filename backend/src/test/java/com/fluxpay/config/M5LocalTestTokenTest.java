package com.fluxpay.config;

import static org.junit.jupiter.api.Assertions.*;
import com.fluxpay.common.security.JwtUtil;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.WINDOWS)
class M5LocalTestTokenTest {
  @Test void documentedPowerShellHelperProducesSignedFixedFixtureIdentities() throws Exception {
    String secret="local-test-only-never-a-production-key-".repeat(2);
    for(String account:new String[]{"admin","sender","pending"}) {
      var command=new ProcessBuilder("powershell.exe","-NoProfile","-NonInteractive","-ExecutionPolicy","Bypass","-File",
          Path.of("../docs/scripts/New-M5TestToken.ps1").toAbsolutePath().normalize().toString(),"-Account",account);
      command.environment().put("JWT_SECRET",secret);
      var process=command.start();
      assertTrue(process.waitFor(10,TimeUnit.SECONDS),"Token helper should terminate");
      assertEquals(0,process.exitValue(),"Token helper failed");
      String token=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
      var user=new JwtUtil(secret).parse(token);
      assertEquals("m5."+account+"@example.invalid",user.email());
      assertEquals(account.equals("admin")?"ADMIN":"USER",user.role());
      assertTrue(user.userId().toString().startsWith("5f000000-0000-0000-0000-"));
    }
  }
}
