package com.whatname.iam.service.permission;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.config.import=")
@AutoConfigureMockMvc
@Import(PermissionDefaultBoundaryTest.NoDatabaseTransactions.class)
class PermissionDefaultBoundaryTest {
    @Autowired MockMvc mvc;
    @TestConfiguration(proxyBeanMethods=false)
    static class NoDatabaseTransactions {
        @Bean PlatformTransactionManager transactions() {
            return new AbstractPlatformTransactionManager() {
                protected Object doGetTransaction() {return new Object();}
                protected boolean isExistingTransaction(Object transaction) {
                    return TransactionSynchronizationManager.isActualTransactionActive();
                }
                protected void doBegin(Object transaction,TransactionDefinition definition) { }
                protected void doCommit(DefaultTransactionStatus status) { }
                protected void doRollback(DefaultTransactionStatus status) { }
                protected void doSetRollbackOnly(DefaultTransactionStatus status) { }
            };
        }
    }
    @Test void allSixUserAndAuditReadsRejectMissingIdentity() throws Exception {
        for(String path:new String[]{"/auth/menus?appCode=IAM","/users/1/permissions?appCode=IAM",
                "/audit/operations","/audit/authorizations","/audit/logins","/audit/evidence/1"})
            mvc.perform(get("/api/v1"+path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_TOKEN"));
    }
    @Test void apiCheckNeverAcceptsCallerSuppliedApplicationOrTestIdentity() throws Exception {
        mvc.perform(post("/api/v1/auth/check").contentType("application/json")
                .header("X-App-Code","IAM").header("X-Test-Service","fixture-only").header("X-Client-Id","fixture-only")
                .content("{\"httpMethod\":\"GET\",\"routeTemplate\":\"/records/{id}\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SERVICE"));
    }
}
