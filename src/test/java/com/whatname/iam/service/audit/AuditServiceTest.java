package com.whatname.iam.service.audit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.web.RequestIdFilter;
import com.whatname.iam.repository.audit.AuditRepository;
import com.whatname.iam.service.permission.DataSignatureService;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

import java.util.*;
import java.util.stream.Stream;

class AuditServiceTest {
    private final AuditRepository repository = mock(AuditRepository.class);
    private final DataSignatureService signatures = mock(DataSignatureService.class);
    private final AuditService service = new AuditService(repository, signatures);

    @AfterEach
    void clearRequestId() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "password",
                "real_name",
                "phone",
                "credential",
                "private_key",
                "access_token",
                "sms_code"
            })
    void sensitiveFieldsNestedInListsAreNeverWritten(String field) {
        var evidence = Map.<String, Object>of("changes", List.of(Map.of(field, "secret")));
        failure(ErrorCode.AUDIT_UNAVAILABLE, () -> change(evidence));
        verifyNoInteractions(repository, signatures);
    }

    @ParameterizedTest(name = "{0}/{1}/{2}")
    @MethodSource("invalidMetadata")
    void invalidMetadataIsRejectedBeforeSigning(String operation, String target, String event) {
        failure(
                ErrorCode.INVALID_ARGUMENT,
                () -> service.change(operation, 1L, target, 2L, event, Map.of()));
        verifyNoInteractions(repository, signatures);
    }

    static Stream<Arguments> invalidMetadata() {
        return Stream.of(
                Arguments.of(null, "ROLE", null),
                Arguments.of("bad", "ROLE", null),
                Arguments.of("AUTH_CHECK", "ROLE", null),
                Arguments.of("ROLE_UPDATE", null, null),
                Arguments.of("ROLE_UPDATE", "bad", null),
                Arguments.of("ROLE_UPDATE", "ROLE", " "),
                Arguments.of("ROLE_UPDATE", "ROLE", "e".repeat(129)));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"bad request id", "safe-request_1"})
    void requestIdIsPreservedOrReplacedAndEvidenceIsNotMutated(String requestId) throws Exception {
        if (requestId != null) MDC.put(RequestIdFilter.MDC_KEY, requestId);
        when(signatures.seal(anyMap())).thenReturn(Map.of("keyId", "test"));
        var evidence = new HashMap<String, Object>();
        evidence.put("reason", "approved");
        evidence.put("before", null);
        change(evidence);
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(repository).insert(captor.capture());
        var entry = captor.getValue();
        if ("safe-request_1".equals(requestId)) assertEquals(requestId, entry.get("requestId"));
        else assertDoesNotThrow(() -> UUID.fromString((String) entry.get("requestId")));
        var retained = new ObjectMapper().readTree((String) entry.get("evidence"));
        assertEquals("test", retained.path("seal").path("keyId").asText());
        assertEquals("1", retained.path("metadata").path("actorId").asText());
        assertEquals("approved", retained.path("reason").asText());
        assertFalse(evidence.containsKey("metadata"));
        assertFalse(evidence.containsKey("seal"));
    }

    @Test
    void sealingFailureDoesNotWriteAnUnsealedRecord() {
        when(signatures.seal(anyMap())).thenThrow(new IllegalStateException("signing unavailable"));
        failure(ErrorCode.AUDIT_UNAVAILABLE, () -> change(Map.of("reason", "approved")));
        verifyNoInteractions(repository);
    }

    @Test
    void databaseFailureIsReportedAsAuditUnavailable() {
        when(signatures.seal(anyMap())).thenReturn(Map.of("keyId", "test"));
        doThrow(new IllegalStateException("database unavailable")).when(repository).insert(anyMap());
        failure(ErrorCode.AUDIT_UNAVAILABLE, () -> change(Map.of()));
    }

    @Test
    void oversizedEvidenceIsNotInserted() {
        when(signatures.seal(anyMap())).thenReturn(Map.of("keyId", "test"));
        failure(ErrorCode.AUDIT_UNAVAILABLE, () -> change(Map.of("reason", "x".repeat(1_000_001))));
        verifyNoInteractions(repository);
    }

    @Test
    void optionalResourceEvidenceCanBeOmitted() {
        when(signatures.seal(anyMap())).thenReturn(Map.of("keyId", "test"));
        service.resourceChange("ROLE", "ROLE_UPDATE", 1, 2, "reason", null, null, null);
        service.resourceChange("ROLE", "ROLE_UPDATE", 1, 2, "reason", Map.of(), Map.of(), Map.of());
        verify(repository, times(2)).insert(anyMap());
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3})
    void unsupportedLoginTypesAreRejected(int type) {
        failure(ErrorCode.INVALID_ARGUMENT, () -> service.login(1L, type, true, null));
        verifyNoInteractions(repository);
    }

    @Test
    void loginFailureCodesMustBeSafeAndStorageFailureIsVisible() {
        failure(
                ErrorCode.INVALID_ARGUMENT,
                () -> service.login(1L, 1, false, "contains private detail"));
        doThrow(new IllegalStateException()).when(repository).login(1L, 2, 0, "INVALID_CREDENTIALS");
        failure(
                ErrorCode.AUDIT_UNAVAILABLE,
                () -> service.login(1L, 2, false, "INVALID_CREDENTIALS"));
        service.login(1L, 1, true, null);
        verify(repository).login(1L, 1, 1, null);
    }

    private void change(Map<String, Object> evidence) {
        service.change("ROLE_UPDATE", 1L, "ROLE", 2L, null, evidence);
    }

    private static void failure(ErrorCode code, org.junit.jupiter.api.function.Executable action) {
        assertEquals(code, assertThrows(BusinessException.class, action).errorCode());
    }
}
