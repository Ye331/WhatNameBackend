package com.whatname.iam.service.permission;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Stage two supplies service request signature/replay validation. Never trust an appCode header.
 */
public interface TrustedApiCaller {
    long authenticatedApplication(HttpServletRequest request, byte[] rawBody);
}
