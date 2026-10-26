package com.whatname.iam.simplified;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.config.*;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.*;
import com.whatname.iam.service.InitializationService;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

class SimplifiedConfigurationTest {
  @TempDir Path temporary;

  DataSigningKeys localSigning(Path file) {
    var keys = new DataSigningKeys();
    keys.setLocalAutoGenerate(true);
    keys.setLocalKeyFile(file.toString());
    return keys;
  }

  @Test void signingKeyRestartReusesExistingMaterialAndCorruptFileIsNeverReplaced() throws Exception {
    Path path = temporary.resolve("signing.properties");
    var first = localSigning(path); first.initialize();
    byte[] before = Files.readAllBytes(path);
    var second = localSigning(path); second.initialize();
    assertEquals(first.getPrivateKey(), second.getPrivateKey());
    assertEquals(first.getPublicKeys(), second.getPublicKeys());
    assertArrayEquals(before, Files.readAllBytes(path));
    Files.writeString(path, "key-id=broken\nprivate-key=broken\npublic-key=broken\n");
    byte[] broken = Files.readAllBytes(path);
    assertThrows(IllegalStateException.class, () -> localSigning(path).initialize());
    assertArrayEquals(broken, Files.readAllBytes(path));
  }

  @Test void explicitSigningSettingsTakePriorityAndIncompletePairFailsClosed() {
    var stored = localSigning(temporary.resolve("stored.properties")); stored.initialize();
    Path unused = temporary.resolve("unused.properties");
    var explicit = localSigning(unused);
    explicit.setKeyId(stored.getKeyId()); explicit.setPrivateKey(stored.getPrivateKey()); explicit.setPublicKeys(stored.getPublicKeys());
    assertDoesNotThrow(explicit::initialize);
    assertFalse(Files.exists(unused));
    explicit.setKeyId("invalid key id");
    assertThrows(IllegalStateException.class, explicit::initialize);
    var partial = localSigning(unused); partial.setPublicKeys(Map.of("id", stored.getPublicKeys().get(stored.getKeyId())));
    assertThrows(IllegalStateException.class, partial::initialize);
    var disabled = new DataSigningKeys(); disabled.setLocalKeyFile(unused.toString());
    assertDoesNotThrow(disabled::initialize);
    assertFalse(Files.exists(unused));
    var different = localSigning(temporary.resolve("other.properties")); different.initialize();
    explicit.setKeyId(stored.getKeyId()); explicit.setPublicKeys(different.getPublicKeys());
    assertThrows(IllegalStateException.class, explicit::initialize);
  }

  @Test void jwtRestartKeepsTokensValidAndDoesNotRegenerateCorruptOrDisabledKeys() throws Exception {
    Path path = temporary.resolve("jwt.properties");
    var env = new MockEnvironment().withProperty("iam.security.jwt-key-file", path.toString())
        .withProperty("iam.security.jwt-issuer", "configured").withProperty("iam.security.jwt-audience", "app")
        .withProperty("iam.security.jwt-key-id", "configured-id");
    var config = new LocalJwtConfiguration();
    String token = config.jwtTokenService(env).issue(1,1);
    byte[] before = Files.readAllBytes(path);
    assertEquals(1, config.jwtTokenService(env).verify(token).userId());
    assertArrayEquals(before, Files.readAllBytes(path));
    Files.writeString(path, "private=broken\npublic=broken\n");
    assertThrows(BusinessException.class, () -> config.jwtTokenService(env).issue(1,1));
    assertEquals("private=broken\npublic=broken\n", Files.readString(path));
    var disabled = new MockEnvironment().withProperty("iam.security.local-keys", "false")
        .withProperty("iam.security.jwt-key-file", temporary.resolve("disabled").toString());
    assertThrows(BusinessException.class, () -> config.jwtTokenService(disabled).issue(1,1));
    assertFalse(Files.exists(temporary.resolve("disabled")));
    var partial = new MockEnvironment().withProperty("iam.security.jwt-public-key", "broken");
    assertThrows(BusinessException.class, () -> config.jwtTokenService(partial).issue(1,1));
  }

  @ParameterizedTest @ValueSource(strings={"NORMAL", "IMPORT", "RECOVER", "UNKNOWN", "MISSING_PHONE"})
  void initializationCommandDispatchesExplicitlyAndNeverRunsDuringNormalStartup(String mode) throws Exception {
    var env = new MockEnvironment().withProperty("iam.init.super-username", "operator")
        .withProperty("IAM_INIT_SUPER_PASSWORD", "OnlyTest123!");
    if (!mode.equals("NORMAL")) env.setProperty("iam.init.mode", mode.equals("MISSING_PHONE") ? "import" : mode.toLowerCase());
    if (!mode.equals("MISSING_PHONE")) env.setProperty("iam.init.super-phone", "13900000000");
    var service = mock(InitializationService.class);
    when(service.initialize(any(), anyString(), anyString(), anyString(), anyString())).thenReturn(Map.of("employees", 4));
    try (var context = new GenericApplicationContext()) {
      context.refresh();
      var runner = new InitializationRunner(env, service, new ObjectMapper(), context);
      var arguments = new DefaultApplicationArguments(new String[0]);
      switch(mode) {
        case "UNKNOWN" -> assertThrows(IllegalArgumentException.class, () -> runner.run(arguments));
        case "MISSING_PHONE" -> assertThrows(IllegalStateException.class, () -> runner.run(arguments));
        default -> runner.run(arguments);
      }
      if (mode.equals("IMPORT")) {
        verify(service).initialize(Path.of("target/prepared-data.json"), "operator", "系统管理员", "13900000000", "OnlyTest123!");
        assertEquals(4, new ObjectMapper().readTree(Path.of("target/initialization-report.json").toFile()).path("employees").asInt());
        assertFalse(context.isActive());
      } else if (mode.equals("RECOVER")) {
        verify(service).recover("operator", "OnlyTest123!");
        assertFalse(context.isActive());
      } else verifyNoInteractions(service);
    }
  }

  @ParameterizedTest @ValueSource(strings={"VALID", "WRONG_COUNT", "LEGACY_ADMIN", "MISSING_SCOPE"})
  void schemaGuardAcceptsOnlySimplifiedShapeWithoutChangingTables(String shape) throws Exception {
    var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE", "sa", "");
    var jdbc = new JdbcTemplate(source);
    jdbc.execute("CREATE TABLE sys_user (id BIGINT" + (shape.equals("MISSING_SCOPE") ? "" : ",management_scope VARCHAR(16)") + ")");
    for (int i=0; i<(shape.equals("WRONG_COUNT") ? 9 : 10); i++)
      jdbc.execute("CREATE TABLE " + (shape.equals("LEGACY_ADMIN") && i==0 ? "sys_admin" : "sys_fixture_" + i) + " (id BIGINT)");
    var guard = new SchemaGuard(source);
    if (shape.equals("VALID")) assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments(new String[0])));
    else assertThrows(IllegalStateException.class, () -> guard.run(new DefaultApplicationArguments(new String[0])));
    assertTrue(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_schema='public'", Integer.class)>0);
  }

  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"wrong", "x", "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"})
  void serviceCredentialsRejectMissingWrongAndOverlongInput(String supplied) {
    var env = new MockEnvironment().withProperty("iam.integration.hr-key", "expected-key-at-least-24-characters");
    var keys = new ServiceKeys(env);
    assertThrows(BusinessException.class, () -> keys.require("hr", supplied));
  }
  @Test void credentialsCannotUseUnknownServiceOrMalformedAppCodeOrWeakConfiguredSecret() {
    var keys = new ServiceKeys(new MockEnvironment().withProperty("iam.integration.oa-key", "short"));
    assertThrows(BusinessException.class, () -> keys.require("unknown", "any"));
    assertThrows(BusinessException.class, () -> keys.require("oa", "short"));
    assertThrows(BusinessException.class, () -> keys.requireApplication(null, "any"));
    assertThrows(BusinessException.class, () -> keys.requireApplication("../OA", "any"));
  }
}
