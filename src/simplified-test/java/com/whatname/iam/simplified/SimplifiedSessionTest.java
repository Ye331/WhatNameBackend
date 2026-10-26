package com.whatname.iam.simplified;

import static org.junit.jupiter.api.Assertions.*;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.JwtTokenService;
import com.whatname.iam.service.auth.PasswordHasher;
import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.RSASSASigner;
import java.security.interfaces.RSAPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

class SimplifiedSessionTest {
  static String privateKey, publicKey;
  static final Instant NOW = Instant.parse("2026-10-26T00:00:00Z");
  @BeforeAll static void isolatedKeys() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var pair = generator.generateKeyPair();
    privateKey = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
    publicKey = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
  }
  JwtTokenService service(Instant time, String issuer, String audience) {
    return new JwtTokenService(privateKey, publicKey, issuer, audience, "simplified-test", Clock.fixed(time, ZoneOffset.UTC));
  }
  void invalidToken(Runnable action) {
    assertEquals(ErrorCode.INVALID_TOKEN, assertThrows(BusinessException.class, action::run).errorCode());
  }

  @ParameterizedTest(name="reject signed token: {0}")
  @ValueSource(strings={"ALGORITHM", "TYPE", "KEY_ID", "CRITICAL", "SUB_TYPE", "SUB_ZERO", "SUB_OVERFLOW",
      "SV_ZERO", "SV_TYPE", "IAT_TYPE", "EXP_TYPE", "DURATION", "USE", "JTI_MISSING", "JTI_FORMAT", "AUDIENCE_LIST"})
  void correctlySignedButInvalidTokenContractsAreRejected(String invalid) throws Exception {
    var claims = new HashMap<String,Object>(Map.of("sub", "42", "sv", 1L, "iat", NOW.getEpochSecond(),
        "exp", NOW.plusSeconds(3600).getEpochSecond(), "iss", "simplified", "aud", "console",
        "token_use", "access", "jti", UUID.randomUUID().toString()));
    var header = new JWSHeader.Builder(invalid.equals("ALGORITHM") ? JWSAlgorithm.RS512 : JWSAlgorithm.RS256)
        .type(invalid.equals("TYPE") ? new JOSEObjectType("other") : JOSEObjectType.JWT)
        .keyID(invalid.equals("KEY_ID") ? "unknown" : "simplified-test");
    if (invalid.equals("CRITICAL")) header.customParam("unsupported", true).criticalParams(Set.of("unsupported"));
    switch (invalid) {
      case "SUB_TYPE" -> claims.put("sub", 42);
      case "SUB_ZERO" -> claims.put("sub", "0");
      case "SUB_OVERFLOW" -> claims.put("sub", "9223372036854775808");
      case "SV_ZERO" -> claims.put("sv", 0);
      case "SV_TYPE" -> claims.put("sv", "1");
      case "IAT_TYPE" -> claims.put("iat", "today");
      case "EXP_TYPE" -> claims.put("exp", "tomorrow");
      case "DURATION" -> claims.put("exp", NOW.plusSeconds(7200).getEpochSecond());
      case "USE" -> claims.put("token_use", "refresh");
      case "JTI_MISSING" -> claims.remove("jti");
      case "JTI_FORMAT" -> claims.put("jti", "predictable");
      case "AUDIENCE_LIST" -> claims.put("aud", List.of("console", "untrusted"));
    }
    var token = new JWSObject(header.build(), new Payload(claims));
    var key = (RSAPrivateKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey)));
    token.sign(new RSASSASigner(key));
    invalidToken(() -> service(NOW, "simplified", "console").verify(token.serialize()));
  }

  @Test void mismatchedAndWeakRsaKeysAreOperationalErrors() throws Exception {
    var generator = KeyPairGenerator.getInstance("RSA");
    generator.initialize(2048);
    var other = generator.generateKeyPair();
    var mismatch = new JwtTokenService(privateKey, Base64.getEncoder().encodeToString(other.getPublic().getEncoded()), "simplified", "console", "id");
    assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, assertThrows(BusinessException.class, () -> mismatch.issue(1,1)).errorCode());
    generator.initialize(1024);
    var weak = generator.generateKeyPair();
    var service = new JwtTokenService(Base64.getEncoder().encodeToString(weak.getPrivate().getEncoded()), Base64.getEncoder().encodeToString(weak.getPublic().getEncoded()), "simplified", "console", "id");
    assertThrows(BusinessException.class, () -> service.issue(1,1));
    assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, assertThrows(BusinessException.class, () -> service.verify("anything")).errorCode());
  }
  @Test void tokenLifetimeIsExactlyOneHourAndBindsIssuerAudienceAndSessionVersion() {
    var issuer = service(NOW, "simplified", "console");
    String token = issuer.issue(42, 7);
    var principal = issuer.verifyBearer("Bearer " + token);
    assertEquals(42, principal.userId());
    assertEquals(7, principal.sessionVersion());
    assertEquals(3600, Duration.between(principal.issuedAt(), principal.expiresAt()).toSeconds());
    assertFalse(principal.toString().contains(principal.jti()));
    assertDoesNotThrow(() -> service(NOW.plusSeconds(3599), "simplified", "console").verify(token));
    invalidToken(() -> service(NOW.plusSeconds(3600), "simplified", "console").verify(token));
    invalidToken(() -> service(NOW.minusSeconds(1), "simplified", "console").verify(token));
    invalidToken(() -> service(NOW, "other", "console").verify(token));
    invalidToken(() -> service(NOW, "simplified", "other").verify(token));
    String[] parts = token.split("\\.");
    invalidToken(() -> issuer.verify(parts[0] + "." + parts[1] + "." + "A".repeat(parts[2].length())));
    assertNotEquals(token, issuer.issue(42, 7));
  }
  @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"token", "Basic abc", "bearer abc", "Bearer ", "Bearer broken"})
  void invalidAuthorizationNeverCreatesASession(String value) {
    invalidToken(() -> service(NOW, "simplified", "console").verifyBearer(value));
  }
  @Test void missingKeysAndInvalidPrincipalCannotIssueTokens() {
    var issuer = service(NOW, "simplified", "console");
    for (long[] principal : List.of(new long[]{0,1}, new long[]{1,0}, new long[]{-1,1}))
      assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, assertThrows(BusinessException.class, () -> issuer.issue(principal[0], principal[1])).errorCode());
    assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, assertThrows(BusinessException.class, () -> new JwtTokenService("", "", "", "", "").issue(1,1)).errorCode());
    invalidToken(() -> issuer.verifyBearer("Bearer " + "x".repeat(8192)));
    invalidToken(() -> issuer.verify(null));
    invalidToken(() -> issuer.verify("x".repeat(8193)));
  }
  @Test void passwordHashesUseFreshSaltAndCorruptStorageNeverAuthenticates() {
    var hasher = new PasswordHasher();
    String first = hasher.hash("Simplified123!"), second = hasher.hash("Simplified123!");
    assertNotEquals(first, second);
    assertTrue(hasher.matches("Simplified123!", first));
    assertFalse(hasher.matches("Wrong123!", first));
    for (String encoded : List.of("", "plaintext", "$2a$untrusted", "x".repeat(77))) {
      assertEquals(PasswordHasher.VerificationResult.INVALID_HASH, hasher.verify("Simplified123!", encoded));
    }
    assertEquals(PasswordHasher.VerificationResult.INVALID_HASH, hasher.verify("Simplified123!", null));
    for (String raw : List.of("", "x".repeat(65), "😀".repeat(65))) {
      assertThrows(IllegalArgumentException.class, () -> hasher.hash(raw));
      assertEquals(PasswordHasher.VerificationResult.INVALID_PASSWORD, hasher.verify(raw, first));
    }
    assertThrows(IllegalArgumentException.class, () -> hasher.hash(null));
    assertFalse(hasher.matches(null, first));
  }
}
