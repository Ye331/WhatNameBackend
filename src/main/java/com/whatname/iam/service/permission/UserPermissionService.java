package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.permission.SignatureMapper;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import static com.whatname.iam.common.api.ErrorCode.*;

@Service
public class UserPermissionService {
    private final PermissionReadScope scope;
    private final PermissionService permissions;
    private final SignatureMapper facts;
    public UserPermissionService(PermissionReadScope scope, PermissionService permissions, SignatureMapper facts) {
        this.scope = scope; this.permissions = permissions; this.facts = facts;
    }
    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
    public Map<String,Object> menus(String authorization, String userId, String appCode) {
        long id = id(userId);
        var allowed = scope.departments(authorization, "IAM_USERS_VIEW");
        var user = facts.record(SignedDataType.USER_IDENTITY, id);
        if (user == null || !allowed.departments().contains(((Number) user.get("dept_id")).longValue()))
            throw new BusinessException(RESOURCE_NOT_FOUND);
        return permissions.userMenus(id, appCode);
    }
    public static long id(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw new NumberFormatException();
            return Long.parseLong(value);
        } catch (NumberFormatException e) { throw new BusinessException(INVALID_ARGUMENT); }
    }
}
