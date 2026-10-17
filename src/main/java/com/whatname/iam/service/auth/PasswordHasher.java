package com.whatname.iam.service.auth;

import java.util.Base64;
import java.util.regex.Pattern;
import org.springframework.security.crypto.scrypt.SCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/** A内部密码编码组件；不访问数据库，不记录密码或编码。 */
@Service
public final class PasswordHasher {
    public static final int MAX_PASSWORD_CODE_POINTS = 64;
    // N=2^17, r=8, p=1: about 128 MiB working memory per scrypt operation.
    private static final int CPU_COST = 1 << 17;
    private static final int BLOCK_SIZE = 8;
    private static final int PARALLELIZATION = 1;
    private static final int KEY_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int ENCODED_LENGTH = 77;
    // Spring's raw scrypt format: $hex(log2(N)<<16 | r<<8 | p)$saltBase64$keyBase64.
    // Accept only this bounded profile before allowing the library to allocate memory.
    private static final Pattern ENCODING = Pattern.compile(
            "\\$110801\\$([A-Za-z0-9+/]{22}==)\\$([A-Za-z0-9+/]{43}=)");

    private final SCryptPasswordEncoder encoder = new SCryptPasswordEncoder(
            CPU_COST, BLOCK_SIZE, PARALLELIZATION, KEY_BYTES, SALT_BYTES);

    public enum VerificationResult {
        MATCH, MISMATCH, INVALID_PASSWORD, INVALID_HASH, VERIFICATION_FAILED
    }

    /**
     * Generates a fresh random salt. This primitive does not enforce business complexity rules;
     * callers setting a password must first pass PasswordPolicy.validate.
     * Null/empty or over-64-code-point input is rejected without including it in an error.
     */
    public String hash(String rawPassword) {
        if (!hasValidInputSize(rawPassword)) {
            throw new IllegalArgumentException("Password must contain 1 to 64 code points");
        }
        try {
            String encoded = encoder.encode(rawPassword);
            if (!isSupportedEncoding(encoded)) {
                throw new IllegalStateException("Unexpected password encoding");
            }
            return encoded;
        } catch (RuntimeException exception) {
            // Do not propagate a library exception that might contain the supplied value.
            throw new IllegalStateException("Password hashing unavailable");
        }
    }

    /** True only on a successful verification; every invalid/error result returns false. */
    public boolean matches(String rawPassword, String encodedPassword) {
        return verify(rawPassword, encodedPassword) == VerificationResult.MATCH;
    }

    /** Distinguishes corrupted history from an ordinary mismatch, without logging input. */
    public VerificationResult verify(String rawPassword, String encodedPassword) {
        if (!hasValidInputSize(rawPassword)) {
            return VerificationResult.INVALID_PASSWORD;
        }
        if (!isSupportedEncoding(encodedPassword)) {
            return VerificationResult.INVALID_HASH;
        }
        try {
            return encoder.matches(rawPassword, encodedPassword)
                    ? VerificationResult.MATCH : VerificationResult.MISMATCH;
        } catch (RuntimeException exception) {
            return VerificationResult.VERIFICATION_FAILED;
        }
    }

    private static boolean hasValidInputSize(String password) {
        return password != null && !password.isEmpty()
                && password.length() <= MAX_PASSWORD_CODE_POINTS * 2
                && password.codePointCount(0, password.length()) <= MAX_PASSWORD_CODE_POINTS;
    }

    private static boolean isSupportedEncoding(String encoded) {
        if (encoded == null || encoded.length() != ENCODED_LENGTH) {
            return false;
        }
        var matcher = ENCODING.matcher(encoded);
        if (!matcher.matches()) {
            return false;
        }
        try {
            return isCanonicalBase64(matcher.group(1), SALT_BYTES)
                    && isCanonicalBase64(matcher.group(2), KEY_BYTES);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isCanonicalBase64(String text, int expectedBytes) {
        byte[] decoded = Base64.getDecoder().decode(text);
        return decoded.length == expectedBytes
                && Base64.getEncoder().encodeToString(decoded).equals(text);
    }
}
