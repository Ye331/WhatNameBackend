package com.whatname.iam.controller.admin;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.whatname.iam.common.exception.GlobalExceptionHandler;
import com.whatname.iam.common.web.RequestIdFilter;
import com.whatname.iam.service.admin.AdminTestFixture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminControllerTest {
    private AdminTestFixture db;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        db = new AdminTestFixture();
        mvc =
                MockMvcBuilders.standaloneSetup(new AdminController(db.service()))
                        .setControllerAdvice(new GlobalExceptionHandler())
                        .addFilters(new RequestIdFilter())
                        .build();
    }

    @Test
    void listUsesStringIdsPaginationAndRequestId() throws Exception {
        db.actorId = 2;
        mvc.perform(get("/api/v1/admins").header("X-Request-Id", "admin-list-test"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.requestId").value("admin-list-test"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.pageSize").value(20))
                .andExpect(jsonPath("$.data.items[0].userId").value("2"))
                .andExpect(jsonPath("$.data.items[0].deptId").value("20"));
    }

    @Test
    void appointmentAndRevocationRoutesPersistChanges() throws Exception {
        mvc.perform(
                        post("/api/v1/admins/appointments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"userId":"6","adminLevel":3,"deptId":"21","reason":"任命"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("6"));
        mvc.perform(
                        post("/api/v1/admins/6/revoke")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"reason":"撤销"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointmentRevoked").value(true));
        assertThat(db.appointments.get(6L).status()).isZero();
    }

    @Test
    void ownScopeAndDelegationVersionHaveContractFieldNames() throws Exception {
        mvc.perform(get("/api/v1/admins/me/scope"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.appointed").value(true))
                .andExpect(jsonPath("$.data.delegableRolesVersion").value("v1"))
                .andExpect(jsonPath("$.data.scopeRootIds[0]").value("10"));
        mvc.perform(get("/api/v1/admins/2/delegable-roles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delegationVersion").value("v1"))
                .andExpect(jsonPath("$.data.roleIds[0]").value("100"));
        mvc.perform(
                        put("/api/v1/admins/2/delegable-roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"roleIds":[],"expectedVersion":"v1","reason":"清空"}
                                        """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.delegationVersion").value("v2"))
                .andExpect(jsonPath("$.data.roleIds").isEmpty());
    }

    @Test
    void staleListVersionReturns409WithoutOverwriting() throws Exception {
        mvc.perform(
                        put("/api/v1/admins/2/delegable-roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"roleIds":[],"expectedVersion":"v9","reason":"冲突"}
                                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        assertThat(db.delegations).hasSize(6);
    }

    @Test
    void invalidBodiesAndPagingReturn400() throws Exception {
        for (String body :
                new String[] {
                    "{}",
                    "{\"userId\":\"6\",\"adminLevel\":0,\"deptId\":\"21\",\"reason\":\"x\"}",
                    "{\"userId\":\"6\",\"adminLevel\":3,\"deptId\":\"21\",\"reason\":\" \"}"
                }) {
            mvc.perform(
                            post("/api/v1/admins/appointments")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        mvc.perform(get("/api/v1/admins?pageSize=501")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admins?page=0")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/admins/9223372036854775808/delegable-roles"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(
                        put("/api/v1/admins/2/delegable-roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"roleIds":[null],"expectedVersion":"v1","reason":"x"}
                                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void hiddenAndMissingTargetsHaveTheSame404Response() throws Exception {
        db.actorId = 2;
        for (String id : new String[] {"5", "999"}) {
            mvc.perform(get("/api/v1/admins/" + id + "/delegable-roles"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{\"userId\":6,\"adminLevel\":3,\"deptId\":\"21\",\"reason\":\"x\"}",
                "{\"userId\":\"6\",\"adminLevel\":3.5,\"deptId\":\"21\",\"reason\":\"x\"}",
                "{\"userId\":\"6\",\"adminLevel\":\"3\",\"deptId\":\"21\",\"reason\":\"x\"}",
                "{\"userId\":\"6\",\"adminLevel\":3,\"deptId\":\"21\",\"reason\":true}"
            })
    void invalidJsonTypesAreNotSilentlyCoerced(String body) throws Exception {
        mvc.perform(
                        post("/api/v1/admins/appointments")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertThat(db.appointments).doesNotContainKey(6L);
    }

    @Test
    void numericRoleIdIsNotAcceptedAsAStringId() throws Exception {
        mvc.perform(
                        put("/api/v1/admins/2/delegable-roles")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"roleIds":[100],"expectedVersion":"v1","reason":"x"}
                                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }
}
