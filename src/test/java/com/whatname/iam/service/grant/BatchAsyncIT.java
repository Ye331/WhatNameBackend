package com.whatname.iam.service.grant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 使用正式后台执行器，执行线程中不设置请求线程的身份上下文。 */
@SpringBootTest(properties = {"spring.config.import=", "iam.grant-batch.recovery.enabled=true"})
@AutoConfigureMockMvc
@Import(GrantIT.Integrations.class)
@Testcontainers
@org.springframework.test.annotation.DirtiesContext(classMode=org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class BatchAsyncIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>(DockerImageName.parse(
            "mysql@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("unified_permission").withTmpFs(Map.of("/var/lib/mysql", "rw"))
            .withCommand("--default-time-zone=+08:00")
            .withCopyFileToContainer(MountableFile.forHostPath(Path.of("db", "01_schema.sql").toAbsolutePath()),
                    "/docker-entrypoint-initdb.d/01_schema.sql");
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired GrantIT.TestState state;

    @Test void committedHttpSubmissionIsExecutedAutomaticallyWithoutRequestIdentity() throws Exception {
        jdbc.update("INSERT INTO sys_dept(id,parent_id,dept_code,dept_name,dept_path,status) VALUES(1,0,'ASYNC','test','/1/',1)");
        for (long user : new long[]{1, 2}) jdbc.update("INSERT INTO sys_user(id,username,real_name_ciphertext,phone,phone_hash,password_hash,dept_id,source,status) VALUES(?,?,?,?,?,?,1,3,1)",
                user, "async_" + user, "test-only", "test-only", String.format("%064x", user), "test-only");
        jdbc.update("INSERT INTO sys_admin(user_id,admin_level,dept_id,status) VALUES(1,1,1,1)");
        jdbc.update("INSERT INTO sys_role(id,role_code,role_name,role_type,status) VALUES(100,'ASYNC','test',1,1)");
        state.actor.set(1L);
        var response = mvc.perform(post("/api/v1/grants/user-roles/batch").header("Idempotency-Key", "real-executor")
                .contentType("application/json").content("""
                {"operation":"GRANT","roleId":"100","targets":[{"userId":"2","sourceType":"MANUAL","sourceRef":"ASYNC"}],"reason":"async test"}
                """)).andExpect(status().isAccepted()).andReturn();
        String batch = json.readTree(response.getResponse().getContentAsString()).get("data").get("batchId").asText();
        state.actor.remove();
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            assertThat(jdbc.queryForObject("SELECT processing_status FROM sys_grant_batch WHERE id=?", String.class, batch)).isEqualTo("COMPLETED");
            assertThat(jdbc.queryForObject("SELECT grant_revision FROM sys_user WHERE id=2", Long.class)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys_operation_log WHERE target_id=2", Long.class)).isEqualTo(1);
        });
        state.actor.set(1L);
        mvc.perform(get("/api/v1/grants/batches/" + batch)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.succeeded").value(1)).andExpect(jsonPath("$.data.items[0].status").value("APPLIED"));
    }
}
