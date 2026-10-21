package com.whatname.iam.service.grant;

import static com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.dto.grant.GrantDtos.*;
import com.whatname.iam.service.admin.AdminPorts;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 单人 HTTP 写入操作保留强制的乐观版本检查。 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class GrantService {
    private final GrantOperations operations;
    private final AdminPorts.Identity identity;

    public GrantService(GrantOperations operations, AdminPorts.Identity identity) {
        this.operations = operations;
        this.identity = identity;
    }

    public Roles roles(String userId) {
        return operations.roles(identity.currentUserId(), userId);
    }

    public Sources sources(String userId, String roleId) {
        return operations.sources(identity.currentUserId(), userId, roleId);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Granted grant(GrantRequest request) {
        if (request == null) throw new BusinessException(INVALID_ARGUMENT);
        GrantOperations.revision(request.expectedVersion());
        return operations.grant(identity.currentUserId(), request);
    }

    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public Revoked revoke(RevokeRequest request) {
        if (request == null) throw new BusinessException(INVALID_ARGUMENT);
        GrantOperations.revision(request.expectedVersion());
        return operations.revoke(identity.currentUserId(), request);
    }
}
