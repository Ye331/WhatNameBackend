package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.*;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.BatchDtos.*;
import com.whatname.iam.dto.grant.GrantDtos;
import com.whatname.iam.service.admin.RequestSecurity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

/** Executes at most 100 users in this HTTP request; each user has an independent transaction. */
@Service
public class BatchService {
    private final GrantOperations grants;
    private final RequestSecurity security;
    private final TransactionTemplate transaction;

    public BatchService(GrantOperations grants, RequestSecurity security, PlatformTransactionManager manager) {
        this.grants = grants;
        this.security = security;
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public Result submit(String key, Request request) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new BusinessException(AUTH_STATE_UNAVAILABLE);
        var input = BatchInput.parse(key, request);
        long actor = security.currentUserId();
        var results = new ArrayList<Item>();
        for (var target : input.targets()) {
            try {
                Item applied = transaction.execute(tx -> {
                    // GrantOperations rechecks the HTTP session, scope and function after locking.
                    if ("GRANT".equals(input.operation())) {
                        var result = grants.grant(actor, new GrantDtos.GrantRequest(target.userId(),
                                Long.toString(input.roleId()), target.sourceType(), target.sourceRef(),
                                target.sourceDeptId(), input.reason(), null));
                        return new Item(target.userId(), "APPLIED", result.grantSourceId(), null);
                    }
                    grants.revoke(actor, new GrantDtos.RevokeRequest(target.userId(),
                            Long.toString(input.roleId()), target.grantSourceId(), input.reason(), null));
                    return new Item(target.userId(), "APPLIED", target.grantSourceId(), null);
                });
                results.add(Objects.requireNonNull(applied));
            } catch (BusinessException failure) {
                if (failure.errorCode().status().is5xxServerError()) throw failure;
                results.add(new Item(target.userId(), "FAILED", null, failure.errorCode().name()));
            } catch (RuntimeException unavailable) {
                // A connection failure can leave the commit outcome unknown. Do not offer blind retries.
                throw new BusinessException(AUTH_STATE_UNAVAILABLE, unavailable);
            }
        }
        int succeeded = (int) results.stream().filter(i -> "APPLIED".equals(i.status())).count();
        int failed = results.size() - succeeded;
        String status = failed == 0 ? "COMPLETED" : succeeded == 0 ? "FAILED" : "PARTIAL_SUCCESS";
        return new Result(status, succeeded, failed, results.size(), results);
    }
}
