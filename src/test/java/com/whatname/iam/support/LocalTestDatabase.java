package com.whatname.iam.support;

import java.util.ArrayList;
import java.util.List;

/** Configuration only: opt-in tests never discover a personal application-local.yml. */
public final class LocalTestDatabase {
    private LocalTestDatabase() { }

    public static String[] arguments(String... applicationArguments) {
        var arguments = new ArrayList<>(List.of(applicationArguments));
        arguments.add("--spring.config.import=");
        arguments.add("--spring.datasource.url=" + required("A_TEST_DB_URL"));
        arguments.add("--spring.datasource.username=" + required("A_TEST_DB_USERNAME"));
        arguments.add("--spring.datasource.password=" + required("A_TEST_DB_PASSWORD"));
        return arguments.toArray(String[]::new);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Set " + name + " privately for opt-in database tests");
        }
        return value;
    }
}
