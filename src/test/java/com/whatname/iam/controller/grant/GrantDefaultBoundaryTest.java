package com.whatname.iam.controller.grant;

import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/** 没有数据库或真实身份时，正式业务入口必须默认拒绝。 */
@SpringBootTest(properties = "spring.config.import=")
@AutoConfigureMockMvc
@Import(GrantDefaultBoundaryTest.NoDatabaseTransactions.class)
class GrantDefaultBoundaryTest {
    @Autowired MockMvc mvc;

    @TestConfiguration(proxyBeanMethods = false)
    static class NoDatabaseTransactions {
        @Bean
        PlatformTransactionManager transactions() {
            return new AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() {
                    return new Object();
                }

                protected void doBegin(Object transaction, TransactionDefinition definition) {}

                protected void doCommit(DefaultTransactionStatus status) {}

                protected void doRollback(DefaultTransactionStatus status) {}
            };
        }
    }

    @Test
    void allFourRoutesRejectMissingIdentity() throws Exception {
        mvc.perform(get("/api/v1/users/6/roles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(get("/api/v1/users/6/roles/100/sources"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(
                        post("/api/v1/grants/user-roles")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
{"userId":"6","roleId":"100","sourceType":"MANUAL","sourceRef":"WORK-1",
 "reason":"test","expectedVersion":"v1"}
"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(
                        post("/api/v1/grants/user-roles/revoke")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
{"userId":"6","roleId":"100","grantSourceId":"901","reason":"test","expectedVersion":"v1"}
"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }

    @Test
    void batchSubmissionRejectsMissingIdentityAndRemovedLookupReturns404() throws Exception {
        mvc.perform(
                        post("/api/v1/grants/user-roles/batch")
                                .header("Idempotency-Key", "default-deny")
                                .contentType(APPLICATION_JSON)
                                .content(
                                        """
{"operation":"GRANT","roleId":"100","targets":[{"userId":"6","sourceType":"MANUAL","sourceRef":"WORK"}],"reason":"test"}
"""))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
        mvc.perform(get("/api/v1/grants/batches/1"))
                .andExpect(status().isNotFound());
    }
}
