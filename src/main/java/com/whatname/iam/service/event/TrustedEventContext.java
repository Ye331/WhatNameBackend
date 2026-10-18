package com.whatname.iam.service.event;

/** Issued/validated by D after service signature, client/key/scope and nonce verification.
 * Constructing this value alone confers no trust: every event call invokes the integration port.
 */
public record TrustedEventContext(String sourceSystem, String clientId, String requestId) { }
