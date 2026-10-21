package com.whatname.iam.common.exception;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

class TransactionFailureResponseTest {
    @Test
    void unavailableDatabaseAtTransactionStartReturns503InsteadOfGeneric500() throws Exception {
        var mvc =
                MockMvcBuilders.standaloneSetup(new FailingTransactionBoundary())
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .build();
        mvc.perform(get("/transaction-start-test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"))
                .andExpect(jsonPath("$.message").value("数据库或当前权限状态不可用"));
    }

    @RestController
    static class FailingTransactionBoundary {
        @GetMapping("/transaction-start-test")
        public void write() {
            throw new CannotCreateTransactionException(
                    "test connection details must not be exposed");
        }
    }
}
