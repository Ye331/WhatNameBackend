package com.whatname.iam.service.admin;

import static com.whatname.iam.common.api.ErrorCode.AUTH_STATE_UNAVAILABLE;
import static com.whatname.iam.service.admin.AdminRulesTest.rejects;

import static org.mockito.Mockito.mock;

import com.whatname.iam.entity.admin.AdminData.Department;
import com.whatname.iam.service.audit.AuditService;
import com.whatname.iam.service.event.TrustedEventContext;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.*;

class AdminOrganizationProtocolTest {
    @Test
    void finalFactsMustStillMatchTheReviewedSnapshotEvenIfCallerSignsLaterChanges() {
        var db = new AdminTestFixture();
        // No facts change at review, so no audit write is needed. Final integrity is deliberately
        // accepting: a later valid signature must not substitute for the actual scope review.
        var beans = new StaticListableBeanFactory(Map.of("audit", mock(AuditService.class)));
        var service =
                new AdminOrganizationLifecycle(
                        db,
                        new AdminRules(),
                        db.integrity,
                        beans.getBeanProvider(AuditService.class));
        TransactionSynchronizationManager.initSynchronization();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionIsolationLevel(
                java.sql.Connection.TRANSACTION_SERIALIZABLE);
        try {
            service.prepare(
                    new TrustedEventContext("HR", "test-client", "test-request"), "test-event");
            service.organizationChanged(Set.of(20L), List.of());
            db.departments.put(99L, new Department(99L, 20L, 1));
            rejects(
                    AUTH_STATE_UNAVAILABLE,
                    () ->
                            TransactionSynchronizationManager.getSynchronizations()
                                    .forEach(sync -> sync.beforeCommit(false)));
        } finally {
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(1));
            TransactionSynchronizationManager.clear();
        }
    }
}
