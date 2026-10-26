package com.whatname.iam.simplified;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Only the new simplified business contract, executed against a disposable MySQL schema. */
@Testcontainers(disabledWithoutDocker = true)
class SimplifiedMySqlIT extends SimplifiedBusinessTest {
  @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
      .withDatabaseName("simplified_test").withUsername("simplified_test")
      .withPassword("isolated-simplified-test-password").withInitScript("schema.sql");

  @DynamicPropertySource static void mysql(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
    registry.add("spring.datasource.username", MYSQL::getUsername);
    registry.add("spring.datasource.password", MYSQL::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    registry.add("spring.sql.init.mode", () -> "never");
  }
}
