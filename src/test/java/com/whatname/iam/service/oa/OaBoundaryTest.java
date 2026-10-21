package com.whatname.iam.service.oa;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.whatname.iam.IamApplication;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        classes = IamApplication.class,
        properties = {
            "spring.config.import=",
            "spring.datasource.url=jdbc:mysql://127.0.0.1:1/unavailable",
            "spring.datasource.hikari.connection-timeout=250"
        })
@AutoConfigureMockMvc(
        print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class OaBoundaryTest {
    @Autowired MockMvc http;

    @Test
    void unavailableDatabaseRejectsOaBeforeAnyEventOrGrantWork() throws Exception {
        http.perform(
                        post("/api/v1/integrations/oa/decisions")
                                .contentType("application/json")
                                .header("X-Client-Id", "invented-oa")
                                .header("X-Key-Id", "invented-key")
                                .header("X-Request-Signature", "invented-signature")
                                .content(
                                        """
{"sourceSystem":"OA","eventId":"OA-boundary","approvalId":"APP-boundary",
 "approvalStatus":"APPROVED","operation":"GRANT","targetUserId":"20","roleId":"30",
 "approvedAt":"2026-10-19T10:00:00+08:00"}
"""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("AUTH_STATE_UNAVAILABLE"));
    }
}
