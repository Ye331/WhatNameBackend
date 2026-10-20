package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import java.util.Collection;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** D-owned resource integrity port for B. B calls it inside its existing write transaction. */
@Service
public class ResourceDataSecurity {
    private final DataSignatureService signatures;
    public ResourceDataSecurity(DataSignatureService signatures) { this.signatures=signatures; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void verifyBeforeMutation(SignedDataType type,long id) {
        requireResource(type); signatures.verify(type,id);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void signAfterMutation(SignedDataType type,long id) {
        requireResource(type); signatures.sign(type,id);
    }
    /** First signature for a newly inserted resource; the insert must already be visible in this transaction. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void createInitial(SignedDataType type,long id) {
        requireResource(type); signatures.sign(type,id);
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void verifyBeforeMutation(Collection<ResourceRef> resources) {
        if (resources==null) throw new BusinessException(com.whatname.iam.common.api.ErrorCode.INTEGRITY_FAILURE);
        for (ResourceRef resource:resources) verifyBeforeMutation(resource.type(),resource.id());
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public void signAfterMutation(Collection<ResourceRef> resources) {
        if (resources==null) throw new BusinessException(com.whatname.iam.common.api.ErrorCode.INTEGRITY_FAILURE);
        for (ResourceRef resource:resources) signAfterMutation(resource.type(),resource.id());
    }
    private static void requireResource(SignedDataType type) {
        if(type==null || !java.util.Set.of(SignedDataType.RESOURCE_APPLICATION,SignedDataType.RESOURCE_API,
                SignedDataType.RESOURCE_MENU,SignedDataType.MENU_APIS,SignedDataType.ROLE_MENUS).contains(type))
            throw new BusinessException(com.whatname.iam.common.api.ErrorCode.INVALID_ARGUMENT);
    }
    public record ResourceRef(SignedDataType type,long id) {}
}
