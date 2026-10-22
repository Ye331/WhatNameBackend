package com.whatname.iam.service.grant;

import static com.whatname.iam.service.permission.SignedDataType.*;

import com.whatname.iam.service.permission.DataSignatureService;

import org.springframework.stereotype.Service;

/** 用户身份（含授权修订号）及授权来源集合的验签与签名，加入调用方事务。 */
@Service
public class GrantIntegrity {
    private final DataSignatureService signatures;

    public GrantIntegrity(DataSignatureService signatures) {
        this.signatures = signatures;
    }

    public void verify(GrantState state) {
        signatures.verify(USER_IDENTITY, state.userId());
        signatures.verify(USER_GRANTS, state.userId());
    }

    /** 回收最后一份来源后的空集合同样签名。 */
    public void sign(GrantState state) {
        signatures.sign(USER_IDENTITY, state.userId());
        signatures.sign(USER_GRANTS, state.userId());
    }
}
