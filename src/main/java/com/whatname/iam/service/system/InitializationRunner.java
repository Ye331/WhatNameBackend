package com.whatname.iam.service.system;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

/**
 * 受控初始化入口，只在启动参数带 iam.init.mode 时生效，执行完即退出，不对外提供 HTTP 接口。
 *
 * <pre>
 * 首次初始化：--iam.init.mode=import --iam.init.data=target/prepared-data.json
 *             --iam.init.super-username=... --iam.init.super-name=... --iam.init.super-phone=...
 *             密码从环境变量 IAM_INIT_SUPER_PASSWORD 读取，不写在命令行里。
 * 兜底恢复：  --iam.init.mode=recover --iam.init.super-username=...
 * </pre>
 */
@Component
@ConditionalOnProperty("iam.init.mode")
public class InitializationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(InitializationRunner.class);

    private final InitializationService service;
    private final Environment env;
    private final ConfigurableApplicationContext context;

    public InitializationRunner(
            InitializationService service,
            Environment env,
            ConfigurableApplicationContext context) {
        this.service = service;
        this.env = env;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String mode = env.getRequiredProperty("iam.init.mode");
        String username = env.getRequiredProperty("iam.init.super-username");
        int exitCode = 0;
        try {
            if (mode.equals("import")) {
                var result =
                        service.initialize(
                                Path.of(env.getRequiredProperty("iam.init.data")),
                                new InitializationService.SuperUser(
                                        username,
                                        env.getRequiredProperty("iam.init.super-name"),
                                        env.getRequiredProperty("iam.init.super-phone"),
                                        env.getRequiredProperty("IAM_INIT_SUPER_PASSWORD")));
                log.info("初始化完成：{}", result);
            } else if (mode.equals("recover")) {
                service.recover(username);
                log.info("超级用户 {} 已恢复", username);
            } else {
                throw new IllegalArgumentException("iam.init.mode 只能是 import 或 recover");
            }
        } catch (Exception e) {
            log.error("初始化失败，已回滚：{}", e.getMessage());
            exitCode = 1;
        }
        int code = exitCode;
        System.exit(SpringApplication.exit(context, () -> code));
    }
}
