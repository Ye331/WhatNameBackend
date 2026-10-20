package com.whatname.iam.service.permission;

import com.whatname.iam.common.exception.BusinessException;
import com.whatname.iam.mapper.service.ServiceIdentityMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.springframework.stereotype.Service;
import static com.whatname.iam.common.api.ErrorCode.INVALID_SERVICE;

/** End-to-end verification of a trusted-service request: header presence, clock skew,
 * identity status, key_id/app/scope binding, Ed25519 signature over the canonical message,
 * then atomic nonce consumption. The public key is read from sys_service_identity.public_key;
 * the server's data-fact-signing private key is never involved. Fails closed on every step. */
@Service
public class ServiceRequestAuthenticator {
    private final ServiceIdentityMapper identities;
    private final NonceStore nonces;
    private final Clock clock = Clock.systemUTC();

    public ServiceRequestAuthenticator(ServiceIdentityMapper identities, NonceStore nonces) {
        this.identities = identities;
        this.nonces = nonces;
    }

    public Map<String,Object> authenticate(HttpServletRequest request, byte[] rawBody, String requiredScope) {
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
        if (identity == null || ((Number) identity.get("status")).intValue() != 1
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
        return identity;
    }

    private static String required(HttpServletRequest request, String name, int maxLength) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank() || value.length() > maxLength)
            throw new BusinessException(INVALID_SERVICE);
        return value;
    }
}
