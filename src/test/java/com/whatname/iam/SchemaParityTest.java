package com.whatname.iam;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.*;
import org.junit.jupiter.api.Test;

class SchemaParityTest {
  @Test
  void h2UsesTheSameElevenTableConstraintsAsMysqlApartFromVendorSyntax() throws Exception {
    String source =
        Files.readString(Path.of("db/schema.sql"))
            .replace("SET NAMES utf8mb4;", "")
            .replace(" ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "")
            .replace(" CHARACTER SET ascii COLLATE ascii_bin", "")
            .replace(" COLLATE utf8mb4_bin", "");
    assertEquals(
        source.replace("\r\n", "\n"),
        Files.readString(Path.of("src/test/resources/schema-h2.sql")).replace("\r\n", "\n"));
  }
}
