package com.whatname.iam.common.security;

import com.whatname.iam.common.api.ErrorCode;
import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.service.admin.AdminPorts;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** A implementation of C's existing identity port. This verifies the request token only;
 * C calls this before M5 locks, so it must not open a database read view here.
 * The Functions integration must still validate the current session after those locks.
 * Not automatically registered: the remaining production security ports still refuse. */
public final class ARequestIdentity implements AdminPorts.Identity {
    private final JwtTokenService tokens;

    public ARequestIdentity(JwtTokenService tokens) { this.tokens = tokens; }

    @Override public long currentUserId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes))
            throw invalid();
        var headers = attributes.getRequest().getHeaders("Authorization");
        if (headers == null || !headers.hasMoreElements()) throw invalid();
        String bearer = headers.nextElement();
        if (headers.hasMoreElements()) throw invalid();
        return tokens.verifyBearer(bearer).userId();
    }

    private static BusinessException invalid() { return new BusinessException(ErrorCode.INVALID_TOKEN); }
}
