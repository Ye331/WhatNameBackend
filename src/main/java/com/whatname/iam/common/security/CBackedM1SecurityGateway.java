package com.whatname.iam.common.security;

import com.whatname.iam.entity.user.UserAccount;
import com.whatname.iam.service.admin.AManagementPolicy;
import java.util.Set;

/** C is real; all D identity/function/signature/audit capabilities are still mandatory. */
public class CBackedM1SecurityGateway implements M1SecurityGateway {
    private final AManagementPolicy policy;
    private final ManagementDataSecurity data;
    public CBackedM1SecurityGateway(AManagementPolicy policy, ManagementDataSecurity data) {
        this.policy=policy; this.data=data;
    }
    public void prepareManagement(long actorId,long... targets) { policy.prepare(actorId,targets); }
    public void verifyIdentity(UserAccount user) { data.verifyIdentity(user); }
    public Set<Long> authorizeManagement(UserAccount actor,String permission) {
        var scope=policy.scope(actor.id);
        data.requireFunction(actor.id,permission);
        return scope;
    }
    public void signIdentity(UserAccount user) { data.signIdentity(user); }
    public void audit(String operation,Long actor,Long target,String reason) { data.audit(operation,actor,target,reason); }
}
