package com.whatname.iam.common.security;

import com.whatname.iam.entity.user.UserAccount;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * A-only insert provenance. Register the generated-key object immediately after INSERT; neither
 * timestamps nor a missing old signature permit initialization.
 */
public final class AAccountCreation {
    private AAccountCreation() {}

    public static void inserted(UserAccount account) {
        if (account.id == null
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive())
            throw new IllegalStateException("A account insert transaction required");
        TransactionSynchronizationManager.registerSynchronization(new Inserted(account));
    }

    public static boolean contains(UserAccount account) {
        return TransactionSynchronizationManager.isSynchronizationActive()
                && TransactionSynchronizationManager.getSynchronizations().stream()
                        .anyMatch(
                                sync ->
                                        sync instanceof Inserted inserted
                                                && inserted.account == account
                                                && inserted.id == account.id.longValue());
    }

    private static final class Inserted implements TransactionSynchronization {
        final UserAccount account;
        final long id;

        Inserted(UserAccount account) {
            this.account = account;
            this.id = account.id;
        }
    }
}
