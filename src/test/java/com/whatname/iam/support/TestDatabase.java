package com.whatname.iam.support;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.MountableFile;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 每个测试类独享一个空库；不读取个人数据库配置，不需要阶段环境开关。 */
public final class TestDatabase implements BeforeAllCallback, AfterAllCallback {
    private static MySQLContainer<?> database;

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        database =
                new MySQLContainer<>("mysql:8.0")
                        .withDatabaseName("unified_permission")
                        .withTmpFs(java.util.Map.of("/var/lib/mysql", "rw"))
                        .withCommand("--default-time-zone=+08:00")
                        .withCopyFileToContainer(
                                MountableFile.forHostPath(
                                        Path.of("db/schema.sql").toAbsolutePath()),
                                "/docker-entrypoint-initdb.d/schema.sql");
        database.start();
        // 仅准备一个根和两个空叶子，供调岗、合并和范围测试使用。
        try (var connection = java.sql.DriverManager.getConnection(url(), username(), password());
                var statement = connection.createStatement()) {
            statement.executeUpdate(
                    "INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path) VALUES "
                            + "(1,0,'TEST-SEED-ROOT','测试总公司','/1/'),"
                            + "(2,1,'TEST-SEED-LEFT','测试部门甲','/1/2/'),"
                            + "(3,1,'TEST-SEED-RIGHT','测试部门乙','/1/3/')");
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        if (database != null) {
            database.stop();
            database = null;
        }
    }

    public static String url() {
        return database.getJdbcUrl();
    }

    public static String username() {
        return database.getUsername();
    }

    public static String password() {
        return database.getPassword();
    }

    public static String[] arguments(String... applicationArguments) {
        var arguments = new ArrayList<>(List.of(applicationArguments));
        arguments.add("--spring.config.import=");
        arguments.add("--spring.datasource.url=" + url());
        arguments.add("--spring.datasource.username=" + username());
        arguments.add("--spring.datasource.password=" + password());
        return arguments.toArray(String[]::new);
    }
}
