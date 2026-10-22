package com.whatname.iam.service.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.audit.AuditMapper;
import com.whatname.iam.service.permission.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;

import java.util.*;

class AuditQueryServiceTest {
    private final AuditMapper mapper = mock(AuditMapper.class);
    private final PermissionReadScope scope = mock(PermissionReadScope.class);
    private final DataSignatureService signatures = mock(DataSignatureService.class);
    private final AuditQueryService service =
            new AuditQueryService(mapper, scope, signatures, new ObjectMapper());

    @ParameterizedTest
    @CsvSource({"0,20", "1000001,20", "1,0", "1,501"})
    void invalidPaginationCannotReachScopeOrDatabase(int page, int size) {
        failure(
                ErrorCode.INVALID_ARGUMENT,
                () -> service.operations("token", false, null, page, size));
        failure(ErrorCode.INVALID_ARGUMENT, () -> service.logins("token", null, page, size));
        verifyNoInteractions(mapper, scope);
    }

    @Test
    void emptyScopeReturnsEmptyListsAndHidesEvidence() {
        when(scope.departments("token", "IAM_AUDIT_VIEW"))
                .thenReturn(new PermissionReadScope.Scope(Set.of(), false));
        assertEquals(List.of(), service.operations("token", false, null, 1, 20).get("items"));
        assertEquals(0L, service.logins("token", null, 1, 20).get("total"));
        failure(ErrorCode.RESOURCE_NOT_FOUND, () -> service.evidence("token", "1"));
        verifyNoInteractions(mapper, signatures);
    }

    @Test
    void invisibleRecordIsNotLoadedOrVerified() {
        when(scope.departments("token", "IAM_AUDIT_VIEW"))
                .thenReturn(new PermissionReadScope.Scope(Set.of(2L), false));
        when(mapper.visible(1, Set.of(2L), false)).thenReturn(0);
        failure(ErrorCode.RESOURCE_NOT_FOUND, () -> service.evidence("token", "1"));
        verify(mapper, never()).operation(anyLong());
        verifyNoInteractions(signatures);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "request_id",
                "user_id",
                "operation_type",
                "target_type",
                "target_id",
                "app_id",
                "api_id",
                "auth_decision",
                "auth_reason"
            })
    void validSealDoesNotAllowSwappingRecordMetadata(String field) {
        var row = retainedRow();
        row.put(field, "changed");
        when(signatures.verifySeal(anyMap(), anyMap())).thenReturn(true);
        failure(ErrorCode.INTEGRITY_FAILURE, () -> service.verifyRetainedEvidence(row));
    }

    @Test
    void matchingEvidenceKeepsItsSealAndVersion() {
        when(signatures.verifySeal(anyMap(), anyMap())).thenReturn(true);
        var result = service.verifyRetainedEvidence(retainedRow());
        assertEquals("v1", result.get("permissionVersion"));
        assertEquals(Map.of("keyId", "test"), result.get("seal"));
    }

    @Test
    void rejectedSealCannotBeRead() {
        when(signatures.verifySeal(anyMap(), anyMap())).thenReturn(false);
        failure(ErrorCode.INTEGRITY_FAILURE, () -> service.verifyRetainedEvidence(retainedRow()));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"not-json", "[]", "null"})
    void malformedEvidenceFailsClosed(String evidence) {
        var row = retainedRow();
        row.put("evidence", evidence);
        failure(ErrorCode.INTEGRITY_FAILURE, () -> service.verifyRetainedEvidence(row));
    }

    private Map<String, Object> retainedRow() {
        var row = new HashMap<String, Object>();
        row.put("request_id", "request-1");
        row.put("user_id", 1L);
        row.put("operation_type", "AUTH_CHECK");
        row.put("target_type", "USER");
        row.put("target_id", 1L);
        row.put("app_id", 2L);
        row.put("api_id", 3L);
        row.put("auth_decision", 1);
        row.put("auth_reason", "ALLOW");
        var metadata =
                Map.of(
                        "requestId",
                        "request-1",
                        "actorId",
                        "1",
                        "operation",
                        "AUTH_CHECK",
                        "targetType",
                        "USER",
                        "targetId",
                        "1",
                        "appId",
                        "2",
                        "apiId",
                        "3",
                        "allowed",
                        "1",
                        "reason",
                        "ALLOW");
        row.put(
                "evidence",
                CanonicalEvidence.json(
                        Map.of(
                                "metadata",
                                metadata,
                                "seal",
                                Map.of("keyId", "test"),
                                "permissionVersion",
                                "v1")));
        return row;
    }

    private static void failure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).errorCode());
    }
}
