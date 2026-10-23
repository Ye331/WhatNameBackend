package com.whatname.iam.service.event;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.repository.event.ExternalEventRepository;
import com.whatname.iam.service.department.DepartmentAccessService;
import com.whatname.iam.service.permission.ServiceOaCaller;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.*;
import java.util.*;

class ExternalEventDiagnosticTest {
    @Test
    void businessFailureRollsBackReceiptAndNeverCreatesARecoveryState() {
        var repository = mock(ExternalEventRepository.class);
        var transactions = new MemoryTransactions();
        when(repository.lock(anyString(), anyString())).thenAnswer(call -> transactions.row);
        when(repository.insert(anyString(), anyString(), anyString(), nullable(Long.class),
                anyString(), anyString(), anyString(), anyString(), any())).thenAnswer(call -> {
            transactions.row = new HashMap<>(Map.of("id", 1L, "status", "PROCESSING", "digest", call.getArgument(6)));
            return 1;
        });
        var service = new ExternalEventService(repository, mock(DepartmentAccessService.class),
                new ObjectMapper(), transactions, mock(ServiceOaCaller.class));
        var command = new ExternalEventService.Command("example", "OA_DECISION", null,
                "USER", "1", null, Map.of("action", "APPROVE"));
        var failure = assertThrows(BusinessException.class, () -> service.execute(
                new TrustedEventContext("OA", "client", "request"), command,
                () -> { throw new IllegalStateException("business failure"); }));
        assertEquals(ErrorCode.AUTH_STATE_UNAVAILABLE, failure.errorCode());
        assertTrue(transactions.rolledBack);
        assertNull(transactions.row);
        verify(repository, never()).failed(anyLong(), anyString(), anyString());
        verify(repository, never()).applied(anyLong(), anyString());
    }

    static class MemoryTransactions extends AbstractPlatformTransactionManager {
        Map<String, Object> row;
        boolean rolledBack;
        protected Object doGetTransaction() { return new Object(); }
        protected void doBegin(Object tx, TransactionDefinition definition) {}
        protected void doCommit(DefaultTransactionStatus status) {}
        protected void doRollback(DefaultTransactionStatus status) { rolledBack = true; row = null; }
    }
}
