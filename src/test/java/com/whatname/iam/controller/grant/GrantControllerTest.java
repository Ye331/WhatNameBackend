package com.whatname.iam.controller.grant;

import com.whatname.iam.common.exception.GlobalExceptionHandler;
import com.whatname.iam.common.web.RequestIdFilter;
import com.whatname.iam.service.grant.GrantTestFixture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;

class GrantControllerTest {
    private final GrantTestFixture db = new GrantTestFixture();
    private MockMvc mvc;
    private static final String VALID = """
            {"userId":"6","roleId":"100","sourceType":"MANUAL","sourceRef":"WORK-1",
             "reason":"test","expectedVersion":"v1"}
            """;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new GrantController(db.service()))
                .setControllerAdvice(new GlobalExceptionHandler()).addFilters(new RequestIdFilter()).build();
    }
    @Test void fourRouteRoundTripUsesContractFieldsAndRetainsRevokedSource() throws Exception {
        mvc.perform(get("/api/v1/users/6/roles").header("X-Request-Id", "grant-test"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestId").value("grant-test"))
                .andExpect(jsonPath("$.data.grantRevision").value("v1"))
                .andExpect(jsonPath("$.data.roles").isEmpty());
        mvc.perform(post("/api/v1/grants/user-roles").contentType(APPLICATION_JSON).content(VALID))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grantSourceId").value("901"))
                .andExpect(jsonPath("$.data.permissionRevision").value("v2"));
        mvc.perform(get("/api/v1/users/6/roles"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0].roleId").value("100"))
                .andExpect(jsonPath("$.data.roles[0].activeSourceCount").value(1));
        mvc.perform(post("/api/v1/grants/user-roles/revoke").contentType(APPLICATION_JSON).content("""
                {"userId":"6","roleId":"100","grantSourceId":"901","reason":"test","expectedVersion":"v2"}
                """))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.revokedSourceId").value("901"))
                .andExpect(jsonPath("$.data.roleEffective").value(false))
                .andExpect(jsonPath("$.data.remainingActiveSources").value(0));
        mvc.perform(get("/api/v1/users/6/roles/100/sources"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.grantRevision").value("v3"))
                .andExpect(jsonPath("$.data.sources[0].active").value(false))
                .andExpect(jsonPath("$.data.sources[0].sourceDeptId").doesNotExist());
    }
    @ParameterizedTest @ValueSource(strings = {
            "{}", "null", "[]",
            "{\"userId\":6}",
            "{\"userId\":\"6\",\"roleId\":100}",
            "{\"userId\":\"6\",\"reason\":true}",
            "{\"sourceType\":1}", "{\"sourceRef\":123}", "{\"expectedVersion\":1}", "{\"sourceDeptId\":21}"
    })
    void malformedOrCoercedJsonCannotCreateSources(String body) throws Exception {
        mvc.perform(post("/api/v1/grants/user-roles").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        assertThat(db.sources).isEmpty();
    }
    @Test void invalidSemanticValuesAndLengthsAreRejected() throws Exception {
        for (String body : new String[]{VALID.replace("WORK-1", " "), VALID.replace("WORK-1", "x".repeat(129)),
                VALID.replace("test", "x".repeat(513)), VALID.replace("v1", "v0"),
                VALID.replace("v1", "v9223372036854775808"), VALID.replace("MANUAL", "OA_APPROVAL"),
                VALID.replace("\"6\"", "\"9223372036854775808\"")}) {
            mvc.perform(post("/api/v1/grants/user-roles").contentType(APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        }
        assertThat(db.sources).isEmpty();
    }
    @Test void unknownFieldsAreRejectedInsteadOfSilentlyIgnoringOperatorOrApproval() throws Exception {
        mvc.perform(post("/api/v1/grants/user-roles").contentType(APPLICATION_JSON)
                        .content(VALID.replace("\"reason\"", "\"actorId\":\"1\",\"reason\"")))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/grants/user-roles/revoke").contentType(APPLICATION_JSON).content("""
                {"userId":"6","roleId":"100","grantSourceId":"901","reason":"test",
                 "expectedVersion":"v1","approvalId":"OA-1"}
                """))
                .andExpect(status().isBadRequest());
        assertThat(db.sources).isEmpty();
    }
    @Test void staleVersionAndMissingOrHiddenTargetsUseBusinessErrors() throws Exception {
        mvc.perform(post("/api/v1/grants/user-roles").contentType(APPLICATION_JSON).content(VALID.replace("v1", "v8")))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        db.admins.actorId = 2;
        for (String user : new String[]{"5", "999"}) {
            mvc.perform(get("/api/v1/users/" + user + "/roles"))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
        }
        mvc.perform(post("/api/v1/grants/user-roles/revoke").contentType(APPLICATION_JSON).content("""
                {"userId":"6","roleId":"100","grantSourceId":"901","reason":"test","expectedVersion":"v1"}
                """))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("GRANT_SOURCE_NOT_FOUND"));
    }
}
