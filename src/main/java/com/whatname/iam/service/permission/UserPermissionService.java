package com.whatname.iam.service.permission;

import static com.whatname.iam.common.api.ErrorCode.*;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.common.util.IdUtils;
import com.whatname.iam.repository.permission.SignatureRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

import java.util.Map;

@Service
public class UserPermissionService {
    private final PermissionReadScope scope;
    private final PermissionService permissions;
    private final SignatureRepository facts;

    public UserPermissionService(
            PermissionReadScope scope, PermissionService permissions, SignatureRepository facts) {
        this.scope = scope;
        this.permissions = permissions;
        this.facts = facts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Map<String, Object> menus(String authorization, String userId, String appCode) {
        long id = IdUtils.parse(userId);
        var allowed = scope.departments(authorization, "IAM_USERS_VIEW");
        var user = facts.record(SignedDataType.USER_IDENTITY, id);
        if ((user == null || user.isEmpty())
                || !allowed.departments().contains(((Number) user.get("dept_id")).longValue()))
            throw new BusinessException(RESOURCE_NOT_FOUND);
        return permissions.userMenus(id, appCode);
    }
}
