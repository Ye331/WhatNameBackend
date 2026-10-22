package com.whatname.iam.service.event;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.SensitiveDataCodec;
import com.whatname.iam.mapper.event.ExternalEventMapper;
import com.whatname.iam.service.department.DepartmentAccessService;
import com.whatname.iam.service.permission.ServiceOaCaller;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;

class ExternalEventAuthenticationTest {
    @Test
    void oaUsesOnlyOaAuthorityAtIngressAndInsideTheWriteTransaction() {
        var f = new Fixture();
        doThrow(new BusinessException(ErrorCode.AUTH_STATE_UNAVAILABLE))
                .when(f.hr)
                .verifyTrustedEvent(any(), anyString());
        var actor = actor("OA");
        var command = command("OA_DECISION");
        assertEquals(
                Map.of("done", true),
                f.service.execute(
                        actor,
                        command,
                        () -> {
                            assertTrue(
                                    TransactionSynchronizationManager.isActualTransactionActive());
                            assertNotNull(
                                    ExternalEventService.applying(
                                            actor, command.eventType(), command.eventId()));
                            return Map.of("done", true);
                        }));
        verify(f.oa, times(2)).verifyTrustedEvent(actor, "OA_DECISION");
        verifyNoInteractions(f.hr);
        assertEquals("APPLIED", f.row.get("status"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {"HR_EMPLOYEE", "HR_TRANSFER", "HR_TERMINATION", "HR_DEPARTMENT", "HR_MERGE"})
    void hrUsesOnlyHrAuthorityAtBothBoundaries(String type) {
        var f = new Fixture();
        doThrow(new BusinessException(ErrorCode.INVALID_SERVICE))
                .when(f.oa)
                .verifyTrustedEvent(any(), anyString());
        var actor = actor("HR");
        assertEquals(
                Map.of("done", true),
                f.service.execute(actor, command(type), () -> Map.of("done", true)));
        verify(f.hr, times(2)).verifyTrustedEvent(actor, type);
        verifyNoInteractions(f.oa);
    }

    @Test
    void oaAuthorityLostAfterReceiptPreventsBusinessAndRecordsRejection() {
        var f = new Fixture();
        doAnswer(
                        call -> {
                            if (TransactionSynchronizationManager.isActualTransactionActive())
                                throw new BusinessException(ErrorCode.INVALID_SERVICE);
                            return null;
                        })
                .when(f.oa)
                .verifyTrustedEvent(any(), anyString());
        var error =
                assertThrows(
                        BusinessException.class,
                        () ->
                                f.service.execute(
                                        actor("OA"),
                                        command("OA_DECISION"),
                                        () -> {
                                            fail(
                                                    "Business must not run after transaction-time"
                                                            + " authentication fails");
                                            return Map.of();
                                        }));
        assertEquals(ErrorCode.INVALID_SERVICE, error.errorCode());
        assertEquals("REJECTED", f.row.get("status"));
        verify(f.mapper, never()).applied(anyLong(), anyString());
        verifyNoInteractions(f.hr);
    }

    @Test
    void oaTerminalRecoveryUsesOnlyOaVerificationAndRecoveryAuthorization() {
        var f = new Fixture();
        var actor = actor("OA");
        var command = command("OA_DECISION");
        f.service.execute(actor, command, () -> Map.of("done", true));
        clearInvocations(f.oa, f.hr);
        assertEquals(
                Map.of("done", true),
                f.service.recover(
                        actor,
                        command,
                        () -> {
                            fail("Recovery must replay the committed result");
                            return Map.of();
                        }));
        verify(f.oa).verifyTrustedEvent(actor, "OA_DECISION");
        verify(f.oa).authorizeRecovery(actor, "OA_DECISION");
        verifyNoInteractions(f.hr);
    }

    @Test
    void hrTerminalRecoveryUsesOnlyHrVerificationAndRecoveryAuthorization() {
        var f = new Fixture();
        var actor = actor("HR");
        var command = command("HR_EMPLOYEE");
        f.service.execute(actor, command, () -> Map.of("done", true));
        clearInvocations(f.oa, f.hr);
        assertEquals(
                Map.of("done", true),
                f.service.recover(
                        actor,
                        command,
                        () -> {
                            fail("Recovery must replay the committed result");
                            return Map.of();
                        }));
        verify(f.hr).verifyTrustedEvent(actor, "HR_EMPLOYEE");
        verify(f.hr).authorizeRecovery(actor, "HR_EMPLOYEE");
        verifyNoInteractions(f.oa);
    }

    @Test
    void rejectedOaCallerNeverFallsBackToHr() {
        var f = new Fixture();
        var oa = mock(ServiceOaCaller.class);
        doThrow(new BusinessException(ErrorCode.INVALID_SERVICE))
                .when(oa)
                .verifyTrustedEvent(any(), any());
        var service =
                new ExternalEventService(
                        f.mapper, f.codec, f.hr, new ObjectMapper(), new MemoryTransactions(), oa);
        var error =
                assertThrows(
                        BusinessException.class,
                        () -> service.execute(actor("OA"), command("OA_DECISION"), Map::of));
        assertEquals(ErrorCode.INVALID_SERVICE, error.errorCode());
        verifyNoInteractions(f.mapper, f.hr);
    }

    @Test
    void oaCannotAuthenticateAnHrEventType() {
        var f = new Fixture();
        var error =
                assertThrows(
                        BusinessException.class,
                        () -> f.service.execute(actor("OA"), command("HR_TRANSFER"), Map::of));
        assertEquals(ErrorCode.INVALID_SERVICE, error.errorCode());
        verifyNoInteractions(f.mapper, f.oa, f.hr);
    }

    private static TrustedEventContext actor(String source) {
        return new TrustedEventContext(source, "test-client", "test-request");
    }

    private static ExternalEventService.Command command(String type) {
        return new ExternalEventService.Command(
                "test-event",
                type,
                type.startsWith("HR_") ? 1L : null,
                "USER",
                "test-subject",
                OffsetDateTime.parse("2026-10-20T00:00:00+08:00"),
                Map.of("decision", "APPROVE"));
    }

    private static final class Fixture {
        final ExternalEventMapper mapper = mock(ExternalEventMapper.class);
        final SensitiveDataCodec codec = mock(SensitiveDataCodec.class);
        final DepartmentAccessService hr = mock(DepartmentAccessService.class);
        final ServiceOaCaller oa = mock(ServiceOaCaller.class);
        Map<String, Object> row;
        final ExternalEventService service;

        Fixture() {
            when(codec.encrypt(anyString())).thenReturn("encrypted");
            when(mapper.lock(anyString(), anyString())).thenAnswer(call -> row);
            when(mapper.insert(
                            anyString(),
                            anyString(),
                            anyString(),
                            nullable(Long.class),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            any()))
                    .thenAnswer(
                            call -> {
                                row = new HashMap<>();
                                row.put("id", 17L);
                                row.put("digest", call.getArgument(6));
                                row.put("status", "PROCESSING");
                                return 1;
                            });
            when(mapper.applied(anyLong(), anyString()))
                    .thenAnswer(
                            call -> {
                                row.put("status", "APPLIED");
                                row.put("result", call.getArgument(1));
                                return 1;
                            });
            when(mapper.failed(anyLong(), anyString(), anyString()))
                    .thenAnswer(
                            call -> {
                                row.put("status", call.getArgument(1));
                                return 1;
                            });
            service =
                    new ExternalEventService(
                            mapper, codec, hr, new ObjectMapper(), new MemoryTransactions(), oa);
        }
    }

    private static final class MemoryTransactions extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
