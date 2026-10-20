package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.service.ServiceIdentityMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.ContentCachingRequestWrapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;
import static com.whatname.iam.service.permission.SignedDataType.SERVICE_IDENTITY;

/** End-to-end verification of a trusted-service request: header presence, clock skew,
 * identity status, key_id/app/scope binding, Ed25519 signature over the canonical message,
 * then atomic nonce consumption. The public key is read from sys_service_identity.public_key;
 * the server's data-fact-signing private key is never involved. Fails closed on every step. */
@Service
public class ServiceRequestAuthenticator {
    private final ServiceIdentityMapper identities;
    private final NonceStore nonces;
    private final DataSignatureService signatures;
    private final Clock clock = Clock.systemUTC();
    private final ConcurrentMap<String, AuthenticatedRequest> authenticated = new ConcurrentHashMap<>();

    public ServiceRequestAuthenticator(ServiceIdentityMapper identities, NonceStore nonces,
                                      DataSignatureService signatures) {
        this.identities = identities; this.nonces = nonces; this.signatures = signatures;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Map<String,Object> authenticate(HttpServletRequest request, byte[] rawBody, String requiredScope) {
        if (requiredScope == null || !Set.of("API_AUTH", "OA_DECISION", "HR_SYNC").contains(requiredScope))
            throw new BusinessException(INVALID_SERVICE);
        String clientId = required(request, ServiceRequestSignature.CLIENT_ID_HEADER, 64);
        String keyId = required(request, ServiceRequestSignature.KEY_ID_HEADER, 64);
        String nonce = required(request, ServiceRequestSignature.NONCE_HEADER, 128);
        String signature = required(request, ServiceRequestSignature.SIGNATURE_HEADER, 512);
        long timestamp;
        try {
            timestamp = Long.parseLong(required(request, ServiceRequestSignature.TIMESTAMP_HEADER, 20));
        } catch (NumberFormatException bad) {
            throw new BusinessException(INVALID_SERVICE);
        }
        if (Math.abs(clock.instant().getEpochSecond() - timestamp) > ServiceRequestSignature.MAX_CLOCK_SKEW_SECONDS)
            throw new BusinessException(INVALID_SERVICE);

        Map<String,Object> identity = identities.find(clientId);
        verifyIdentityFact(identity);
        if (identity == null || !(identity.get("status") instanceof Number status) || status.intValue() != 1
                || !requiredScope.equals(identity.get("allowed_scope")))
            throw new BusinessException(INVALID_SERVICE);
        if (!keyId.equals(identity.get("key_id")))
            throw new BusinessException(INVALID_SERVICE);
        boolean boundToApp = identity.get("app_id") != null;
        if ("API_AUTH".equals(requiredScope)) {
            if (!boundToApp) throw new BusinessException(INVALID_SERVICE);
        } else if (boundToApp) {
            throw new BusinessException(INVALID_SERVICE);
        }

        String rawPathAndQuery = request.getRequestURI();
        String query = request.getQueryString();
        if (query != null && !query.isEmpty()) rawPathAndQuery += "?" + query;
        String message = ServiceRequestSignature.message(clientId, keyId, timestamp, nonce,
                request.getMethod(), rawPathAndQuery, ServiceRequestSignature.sha256Hex(rawBody));
        if (!ServiceRequestSignature.verify((String) identity.get("public_key"), signature, message))
            throw new BusinessException(INVALID_SERVICE);

        LocalDateTime expiresAt = LocalDateTime.ofInstant(
                clock.instant().plusSeconds(ServiceRequestSignature.NONCE_RETENTION_SECONDS), ZoneOffset.ofHours(8));
        nonces.consume(clientId, nonce, expiresAt);
        authenticated.put(proofKey(clientId, nonce), new AuthenticatedRequest(
                clientId, keyId, timestamp, nonce, request.getMethod(), rawPathAndQuery,
                ServiceRequestSignature.sha256Hex(rawBody), requiredScope,
                clock.instant().plusSeconds(ServiceRequestSignature.NONCE_RETENTION_SECONDS).getEpochSecond()));
        return identity;
    }

    /** HR/department MVC handlers call this after argument binding; the filter preserves exact body bytes. */
    public Map<String,Object> authenticate(HttpServletRequest request, String requiredScope) {
        if (!(request instanceof ContentCachingRequestWrapper cached))
            throw new BusinessException(INVALID_SERVICE);
        return authenticate(request, cached.getContentAsByteArray(), requiredScope);
    }

    /** Re-validates a context issued only after successful request authentication. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void verifyAuthenticatedContext(com.whatname.iam.service.event.TrustedEventContext context,
                                           String requiredScope) {
        if (context == null || blank(context.clientId()) || blank(context.requestId()))
            throw new BusinessException(INVALID_SERVICE);
        String key = proofKey(context.clientId(), context.requestId());
        AuthenticatedRequest proof = authenticated.get(key);
        if (proof == null || proof.expiresAtEpochSecond() < clock.instant().getEpochSecond()
                || !requiredScope.equals(proof.requiredScope())) {
            authenticated.remove(key, proof);
            throw new BusinessException(INVALID_SERVICE);
        }
        Map<String,Object> identity = identities.find(context.clientId());
        verifyIdentityFact(identity);
        if (!proof.keyId().equals(identity.get("key_id"))
                || !requiredScope.equals(identity.get("allowed_scope"))
                || ("API_AUTH".equals(requiredScope) != (identity.get("app_id") != null))
                || !(identity.get("status") instanceof Number status) || status.intValue() != 1)
            throw new BusinessException(INVALID_SERVICE);
    }

    private static String required(HttpServletRequest request, String name, int maxLength) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || value.length() > maxLength)
            throw new BusinessException(INVALID_SERVICE);
        return value;
    }

    private void verifyIdentityFact(Map<String,Object> identity) {
        try {
            if (identity == null || !(identity.get("id") instanceof Number id) || id.longValue() <= 0)
                throw new BusinessException(INVALID_SERVICE);
            signatures.verify(SERVICE_IDENTITY, id.longValue());
        } catch (BusinessException failure) {
            throw new BusinessException(INVALID_SERVICE, failure);
        } catch (RuntimeException failure) {
            throw new BusinessException(INVALID_SERVICE, failure);
        }
    }

    private static String proofKey(String clientId, String nonce) { return clientId + "\u0000" + nonce; }
    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private record AuthenticatedRequest(String clientId, String keyId, long timestamp, String nonce,
                                        String method, String pathAndQuery, String bodyDigest,
                                        String requiredScope, long expiresAtEpochSecond) { }
}
