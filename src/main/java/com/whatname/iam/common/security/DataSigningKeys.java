package com.whatname.iam.common.security;

import jakarta.annotation.PostConstruct;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.*;
import java.security.spec.*;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 本地演示只使用一对持久化 Ed25519 密钥；显式配置优先，不自动轮换。 */
@Component
@ConfigurationProperties(prefix = "iam.security.data-signing")
public class DataSigningKeys {
  private String keyId = "", privateKey = "";
  private Map<String, String> publicKeys = new HashMap<>();

  private boolean localAutoGenerate;
  private String localKeyFile = "config/local-secrets/data-signing.properties";

  public boolean isLocalAutoGenerate() {
    return localAutoGenerate;
  }

  public void setLocalAutoGenerate(boolean value) {
    localAutoGenerate = value;
  }

  public String getLocalKeyFile() {
    return localKeyFile;
  }

  public void setLocalKeyFile(String value) {
    localKeyFile = value;
  }

  @PostConstruct
  public void initialize() {
    if (!localAutoGenerate) return;
    // Never silently replace a partially configured or mismatched explicit pair.
    boolean explicitPublic = publicKeys.values().stream().anyMatch(v -> v != null && !v.isBlank());
    if (!privateKey.isBlank() || explicitPublic) {
      validatePair();
      return;
    }
    Path file = Path.of(localKeyFile).toAbsolutePath().normalize();
    try {
      Files.createDirectories(file.getParent());
      // Serialize first startup, including simultaneous IDEA processes.
      try (var channel =
              FileChannel.open(
                  file.resolveSibling(file.getFileName() + ".lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var lock = channel.lock()) {
        var properties = new Properties();
        if (Files.exists(file)) {
          try (var input = Files.newInputStream(file)) {
            properties.load(input);
          }
        } else {
          var pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
          properties.setProperty("key-id", "local-dev");
          properties.setProperty(
              "private-key", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
          properties.setProperty(
              "public-key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
          Path temporary = Files.createTempFile(file.getParent(), "signing-", ".tmp");
          try {
            try (var output = Files.newOutputStream(temporary)) {
              properties.store(output, "Local demo signing key; keep this file across restarts");
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE);
          } finally {
            Files.deleteIfExists(temporary);
          }
        }
        keyId = properties.getProperty("key-id", "");
        privateKey = properties.getProperty("private-key", "");
        publicKeys = new HashMap<>();
        publicKeys.put(keyId, properties.getProperty("public-key", ""));
        validatePair();
      }
    } catch (Exception e) {
      throw new IllegalStateException(
          "Local signing key could not be loaded; preserve the existing file and restore its valid"
              + " backup",
          e);
    }
  }

  private void validatePair() {
    try {
      if (!keyId.matches("[A-Za-z0-9._-]{1,64}")) throw new GeneralSecurityException();
      var factory = KeyFactory.getInstance("Ed25519");
      var signer = Signature.getInstance("Ed25519");
      signer.initSign(
          factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKey))));
      signer.update(new byte[] {1, 2, 3});
      byte[] proof = signer.sign();
      signer.initVerify(
          factory.generatePublic(
              new X509EncodedKeySpec(Base64.getDecoder().decode(publicKeys.get(keyId)))));
      signer.update(new byte[] {1, 2, 3});
      if (!signer.verify(proof)) throw new GeneralSecurityException();
    } catch (Exception e) {
      throw new IllegalStateException("Signing keys are incomplete or do not match", e);
    }
  }

  public String getKeyId() {
    return keyId;
  }

  public void setKeyId(String value) {
    keyId = value;
  }

  public String getPrivateKey() {
    return privateKey;
  }

  public void setPrivateKey(String value) {
    privateKey = value;
  }

  public Map<String, String> getPublicKeys() {
    return publicKeys;
  }

  public void setPublicKeys(Map<String, String> value) {
    publicKeys = value;
  }
}
