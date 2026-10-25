package com.whatname.iam.common.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.service.InitializationService;
import java.nio.file.*;
import org.springframework.boot.*;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class InitializationRunner implements ApplicationRunner {
  private final Environment env;
  private final InitializationService init;
  private final ObjectMapper json;
  private final ConfigurableApplicationContext context;

  public InitializationRunner(
      Environment env,
      InitializationService init,
      ObjectMapper json,
      ConfigurableApplicationContext context) {
    this.env = env;
    this.init = init;
    this.json = json;
    this.context = context;
  }

  @Override
  public void run(ApplicationArguments args) throws Exception {
    String mode = env.getProperty("iam.init.mode", "");
    if (mode.isEmpty()) return;
    if (!mode.equals("import") && !mode.equals("recover"))
      throw new IllegalArgumentException("Unknown initialization mode");
    String user = env.getProperty("iam.init.super-username", "superadmin"),
        password = env.getProperty("IAM_INIT_SUPER_PASSWORD", "");
    if (mode.equals("import")) {
      var result =
          init.initialize(
              Path.of(env.getProperty("iam.init.data", "target/prepared-data.json")),
              user,
              env.getProperty("iam.init.super-name", "系统管理员"),
              env.getRequiredProperty("iam.init.super-phone"),
              password);
      Files.createDirectories(Path.of("target"));
      json.writerWithDefaultPrettyPrinter()
          .writeValue(Path.of("target/initialization-report.json").toFile(), result);
    } else init.recover(user, password);
    SpringApplication.exit(context, () -> 0);
  }
}
