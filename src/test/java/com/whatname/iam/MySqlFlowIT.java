package com.whatname.iam;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Runs the same actual controller/service tests against a fresh, disposable MySQL database. */
@Testcontainers(disabledWithoutDocker = true)
class MySqlFlowIT extends CourseworkFlowTest {
  @Container
  static final MySQLContainer<?> MYSQL =
      new MySQLContainer<>("mysql:8.0.46")
          .withDatabaseName("coursework_test")
          .withUsername("coursework_test")
          .withPassword("isolated-test-password")
          .withInitScript("schema.sql");

  @DynamicPropertySource
  static void mysql(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    registry.add("spring.sql.init.mode", () -> "never");
  }
}
