package com.whatname.iam.service.event;

/**
 * Issued/validated by ServiceRequestAuthenticator after service signature, client/key/scope and
 * nonce verification. The opaque proof is intentionally not serializable into a request and is
 * checked again before an event business transaction. The three-argument constructor remains for
 * non-production test fixtures; it creates an unissued context and therefore cannot pass production
 * verification.
 */
public record TrustedEventContext(
        String sourceSystem, String clientId, String requestId, Object authenticationProof) {
    public TrustedEventContext(String sourceSystem, String clientId, String requestId) {
        this(sourceSystem, clientId, requestId, null);
    }

    public static TrustedEventContext issued(
            String sourceSystem, String clientId, String requestId, Object proof) {
        return new TrustedEventContext(sourceSystem, clientId, requestId, proof);
    }

    public boolean isIssuedBy(Object proof) {
        return authenticationProof != null && authenticationProof == proof;
    }
}
