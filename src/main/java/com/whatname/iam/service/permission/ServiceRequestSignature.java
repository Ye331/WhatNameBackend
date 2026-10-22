package com.whatname.iam.service.permission;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Frozen v1 service-request signature primitive (docs/contracts/02-api-spec.md §2.4). The canonical
 * message is the newline join of clientId, keyId, unix seconds, nonce, the UPPERCASE HTTP method,
 * the raw path + query string, and the lowercase SHA-256 hex digest of the raw body. Ed25519 signs
 * the UTF-8 bytes; the signature is Base64. The verifying key is sys_service_identity.public_key —
 * never the server data-signing key.
 */
public final class ServiceRequestSignature {
    public static final String CLIENT_ID_HEADER = "X-Client-Id";
    public static final String KEY_ID_HEADER = "X-Key-Id";
    public static final String TIMESTAMP_HEADER = "X-Request-Timestamp";
    public static final String NONCE_HEADER = "X-Request-Nonce";
    public static final String SIGNATURE_HEADER = "X-Request-Signature";
    public static final long MAX_CLOCK_SKEW_SECONDS = 300;
    public static final long NONCE_RETENTION_SECONDS = 600;

    private ServiceRequestSignature() {}

    public static String message(
            String clientId,
            String keyId,
            long unixSeconds,
            String nonce,
            String method,
            String rawPathAndQuery,
            String bodySha256Hex) {
        return String.join(
                "\n",
                clientId,
                keyId,
                Long.toString(unixSeconds),
                nonce,
                method.toUpperCase(Locale.ROOT),
                rawPathAndQuery,
                bodySha256Hex);
    }

    public static String sha256Hex(byte[] body) {
        byte[] bytes = body == null ? new byte[0] : body;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * True only for a 64-byte Ed25519 signature over message; returns false on any bad input.
     * Accepts both bare Base64 X.509 SPKI and PEM-armored keys.
     */
    public static boolean verify(String publicKeyBase64, String signatureBase64, String message) {
        try {
            String normalized =
                    publicKeyBase64
                            .replaceAll("-----BEGIN PUBLIC KEY-----", "")
                            .replaceAll("-----END PUBLIC KEY-----", "")
                            .replaceAll("\\s", "");
            PublicKey key =
                    KeyFactory.getInstance("Ed25519")
                            .generatePublic(
                                    new X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
            byte[] signature = Base64.getDecoder().decode(signatureBase64);
            if (signature.length != 64) return false;
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(message.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception error) {
            return false;
        }
    }
}
