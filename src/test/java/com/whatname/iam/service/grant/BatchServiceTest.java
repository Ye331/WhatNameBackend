package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.BatchDtos.*;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.admin.RequestSecurity;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Checks batch orchestration; real database atomicity remains covered by GrantIT. */
class BatchServiceTest {
    private final GrantOperations grants = mock(GrantOperations.class);
    private final RequestSecurity security = mock(RequestSecurity.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final BatchService service = new BatchService(grants, security, manager);

    @BeforeEach
    void configureTransactions() {
        when(security.currentUserId()).thenReturn(17L);
        when(manager.getTransaction(any())).thenAnswer(call -> new SimpleTransactionStatus());
    }

    private Request grantRequest() {
        return new Request("GRANT", "2", List.of(
                new Target("1", "MANUAL", "form-1", null, null),
                new Target("2", "MANUAL", "form-2", null, null),
                new Target("3", "MANUAL", "form-3", null, null)), "coursework", false);
    }

    private void successfulGrants() {
        when(grants.grant(eq(17L), any())).thenAnswer(call -> {
            GrantRequest request = call.getArgument(1);
            return new Granted(request.userId(), "2", "source-" + request.userId(), true, "v2");
        });
    }

    @Test
    void eachUserCommitsIndependentlyAndUsesTheHttpActor() {
        successfulGrants();
        var result = service.submit(null, grantRequest());
        assertEquals("COMPLETED", result.processingStatus());
        assertEquals(3, result.succeeded());
        assertEquals(0, result.failed());
        assertEquals(3, result.total());
        verify(manager, times(3)).getTransaction(argThat(definition ->
                definition.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                && definition.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ));
        verify(manager, times(3)).commit(any());
        verify(manager, never()).rollback(any());
        verify(grants, times(3)).grant(eq(17L), argThat(request ->
                request.expectedVersion() == null && "coursework".equals(request.reason())));
    }

    @Test
    void businessFailureRollsBackOneUserAndContinuesWithTheNext() {
        successfulGrants();
        when(grants.grant(eq(17L), argThat(r -> r != null && "2".equals(r.userId()))))
                .thenThrow(new BusinessException(OUT_OF_SCOPE));
        var result = service.submit(null, grantRequest());
        assertEquals("PARTIAL_SUCCESS", result.processingStatus());
        assertEquals(2, result.succeeded());
        assertEquals(1, result.failed());
        assertEquals(List.of("APPLIED", "FAILED", "APPLIED"),
                result.items().stream().map(Item::status).toList());
        assertEquals("OUT_OF_SCOPE", result.items().get(1).code());
        assertNull(result.items().get(1).grantSourceId());
        verify(manager, times(2)).commit(any());
        verify(manager).rollback(any());
    }

    @Test
    void everyUserRejectedProducesFailedInsteadOfCompleted() {
        when(grants.grant(eq(17L), any())).thenThrow(new BusinessException(ROLE_NOT_DELEGABLE));
        var result = service.submit(null, grantRequest());
        assertEquals("FAILED", result.processingStatus());
        assertEquals(0, result.succeeded());
        assertEquals(3, result.failed());
        verify(manager, times(3)).rollback(any());
        verify(manager, never()).commit(any());
    }

    @Test
    void revokeUsesTheSelectedSourceAndReturnsIt() {
        var request = new Request("REVOKE", "2",
                List.of(new Target("1", null, null, null, "51")), "remove", false);
        var result = service.submit(null, request);
        verify(grants).revoke(17L, new RevokeRequest("1", "2", "51", "remove", null));
        verify(grants, never()).grant(anyLong(), any());
        assertEquals("COMPLETED", result.processingStatus());
        assertEquals("51", result.items().getFirst().grantSourceId());
        verify(manager).commit(any());
    }

    @Test
    void integrityFailureStopsRemainingUsersWithoutUndoingEarlierCommit() {
        successfulGrants();
        when(grants.grant(eq(17L), argThat(r -> r != null && "2".equals(r.userId()))))
                .thenThrow(new BusinessException(INTEGRITY_FAILURE));
        assertEquals(INTEGRITY_FAILURE,
                assertThrows(BusinessException.class, () -> service.submit(null, grantRequest())).errorCode());
        verify(manager).commit(any());
        verify(manager).rollback(any());
        verify(grants, never()).grant(anyLong(), argThat(r -> r != null && "3".equals(r.userId())));
    }

    @Test
    void lostCommitAcknowledgementIsUnavailableAndDoesNotContinue() {
        successfulGrants();
        doThrow(new IllegalStateException("connection lost during commit")).when(manager).commit(any());
        var error = assertThrows(BusinessException.class, () -> service.submit(null, grantRequest()));
        assertEquals(AUTH_STATE_UNAVAILABLE, error.errorCode());
        verify(grants, times(1)).grant(anyLong(), any());
        verify(manager, times(1)).commit(any());
    }

    @Test
    void invalidSessionDoesNotStartAnyUserTransaction() {
        when(security.currentUserId()).thenThrow(new BusinessException(INVALID_TOKEN));
        assertEquals(INVALID_TOKEN,
                assertThrows(BusinessException.class, () -> service.submit(null, grantRequest())).errorCode());
        verifyNoInteractions(grants);
        verify(manager, never()).getTransaction(any());
    }

    @Test
    void callerTransactionIsRejectedBeforeReadingIdentityOrWriting() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertEquals(AUTH_STATE_UNAVAILABLE,
                    assertThrows(BusinessException.class, () -> service.submit(null, grantRequest())).errorCode());
            verifyNoInteractions(grants, security);
            verify(manager, never()).getTransaction(any());
        } finally {
            TransactionSynchronizationManager.clear();
        }
    }
}
