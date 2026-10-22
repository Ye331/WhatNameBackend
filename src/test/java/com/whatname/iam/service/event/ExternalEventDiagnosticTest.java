package com.whatname.iam.service.event;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.security.SensitiveDataCodec;
import com.whatname.iam.mapper.event.ExternalEventMapper;
import com.whatname.iam.service.department.DepartmentAccessService;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

class ExternalEventDiagnosticTest {
    @Test
    void unknownAndFailureRecordErrorsRemainDiagnosableWithoutLoggingInput() {
        var logger = (Logger) LoggerFactory.getLogger(ExternalEventService.class);
        var previousLevel = logger.getLevel();
        // 前面的局部应用可能关闭根日志；本测试自行设置并恢复捕获级别。
        logger.setLevel(ch.qos.logback.classic.Level.WARN);
        var captured = new ListAppender<ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            Fixture persisted = new Fixture(false);
            BusinessException first =
                    assertThrows(
                            BusinessException.class,
                            () -> persisted.execute("private-event-one", "private-payload-marker"));
            assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, first.errorCode());
            assertEquals("PENDING_RECOVERY", persisted.row.get().get("status"));

            Fixture unavailable = new Fixture(true);
            BusinessException second =
                    assertThrows(
                            BusinessException.class,
                            () ->
                                    unavailable.execute(
                                            "private-event-two", "private-payload-marker"));
            assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, second.errorCode());
            assertEquals("PROCESSING", unavailable.row.get().get("status"));

            String diagnostics =
                    captured.list.stream()
                            .map(ILoggingEvent::getFormattedMessage)
                            .filter(message -> message.startsWith("External event "))
                            .reduce("", (a, b) -> a + "\n" + b);
            assertTrue(
                    diagnostics.contains(
                            "failure=IllegalArgumentException state=PENDING_RECOVERY"));
            assertTrue(
                    diagnostics.contains(
                            "failure=IllegalArgumentException"
                                    + " recordFailure=IllegalStateException"));
            assertFalse(diagnostics.contains("private-event-one"));
            assertFalse(diagnostics.contains("private-event-two"));
            assertFalse(diagnostics.contains("private-payload-marker"));
            assertFalse(diagnostics.contains("private-record-marker"));
        } finally {
            logger.setLevel(previousLevel);
            logger.detachAppender(captured);
            captured.stop();
        }
    }

    private static final class Fixture {
        private final AtomicReference<Map<String, Object>> row = new AtomicReference<>();
        private final ExternalEventService service;

        Fixture(boolean failRecord) {
            var mapper = mock(ExternalEventMapper.class);
            var codec = mock(SensitiveDataCodec.class);
            when(codec.encrypt(anyString())).thenReturn("encrypted");
            when(mapper.lock(anyString(), anyString())).thenAnswer(call -> row.get());
            when(mapper.insert(
                            anyString(),
                            anyString(),
                            anyString(),
                            nullable(Long.class),
                            anyString(),
                            anyString(),
                            anyString(),
                            anyString(),
                            nullable(java.time.LocalDateTime.class)))
                    .thenAnswer(
                            call -> {
                                Map<String, Object> inserted = new HashMap<>();
                                inserted.put("id", 17L);
                                inserted.put("digest", call.getArgument(6));
                                inserted.put("status", "PROCESSING");
                                row.set(inserted);
                                return 1;
                            });
            if (failRecord) {
                when(mapper.failed(anyLong(), anyString(), anyString()))
                        .thenThrow(new IllegalStateException("private-record-marker"));
            } else {
                when(mapper.failed(anyLong(), anyString(), anyString()))
                        .thenAnswer(
                                call -> {
                                    row.get().put("status", call.getArgument(1));
                                    return 1;
                                });
            }
            service =
                    new ExternalEventService(
                            mapper,
                            codec,
                            mock(DepartmentAccessService.class),
                            new ObjectMapper(),
                            new MemoryTransactionManager(),
                            mock(com.whatname.iam.service.permission.ServiceOaCaller.class));
        }

        Map<String, Object> execute(String eventId, String privateMessage) {
            var context = new TrustedEventContext("OA", "test-client", "test-request");
            var command =
                    new ExternalEventService.Command(
                            eventId,
                            "OA_DECISION",
                            null,
                            "USER",
                            "private-target",
                            null,
                            Map.of("decision", "APPROVE"));
            return service.execute(
                    context,
                    command,
                    () -> {
                        throw new IllegalArgumentException(privateMessage);
                    });
        }
    }

    private static final class MemoryTransactionManager extends AbstractPlatformTransactionManager {
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
