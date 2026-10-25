package com.whatname.iam.common.config;

import com.whatname.iam.common.security.JwtTokenService;
import java.nio.file.*;
import java.security.KeyPairGenerator;
import java.util.*;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;

@Configuration
public class LocalJwtConfiguration {
  @Bean
  public JwtTokenService jwtTokenService(Environment env) throws Exception {
    String privateKey = env.getProperty("iam.security.jwt-private-key", ""),
        publicKey = env.getProperty("iam.security.jwt-public-key", "");
    if (privateKey.isBlank()
        && publicKey.isBlank()
        && env.getProperty("iam.security.local-keys", Boolean.class, true)) {
      Path path =
          Path.of(
                  env.getProperty(
                      "iam.security.jwt-key-file", "config/local-secrets/jwt.properties"))
              .toAbsolutePath();
      Files.createDirectories(path.getParent());
      var properties = new Properties();
      // Reuse the same local key pair across restarts. Never replace a malformed existing file.
      try (var channel =
              java.nio.channels.FileChannel.open(
                  path.resolveSibling(path.getFileName() + ".lock"),
                  StandardOpenOption.CREATE,
                  StandardOpenOption.WRITE);
          var lock = channel.lock()) {
        if (Files.exists(path)) {
          try (var in = Files.newInputStream(path)) {
            properties.load(in);
          }
        } else {
          var generator = KeyPairGenerator.getInstance("RSA");
          generator.initialize(2048);
          var pair = generator.generateKeyPair();
          properties.setProperty(
              "private", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
          properties.setProperty(
              "public", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()));
          try (var out = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) {
            properties.store(out, "Local demo JWT keys; preserve this file");
          }
        }
      }
      privateKey = properties.getProperty("private", "");
      publicKey = properties.getProperty("public", "");
    }
    return new JwtTokenService(
        privateKey,
        publicKey,
        setting(env, "iam.security.jwt-issuer", "coursework-iam"),
        setting(env, "iam.security.jwt-audience", "coursework-apps"),
        setting(env, "iam.security.jwt-key-id", "local-dev"));
  }

  private String setting(Environment env, String key, String fallback) {
    String value = env.getProperty(key, "");
    return value.isBlank() ? fallback : value;
  }
}
